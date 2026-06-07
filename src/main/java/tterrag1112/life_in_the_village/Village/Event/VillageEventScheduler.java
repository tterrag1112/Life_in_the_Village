// src/main/java/tterrag1112/life_in_the_village/Village/Event/VillageEventScheduler.java
package tterrag1112.life_in_the_village.Village.Event;

import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Cultures.Culture;
import tterrag1112.life_in_the_village.Cultures.CultureResolver;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Lore.HistoryTextGenerator;
import tterrag1112.life_in_the_village.Lore.KingdomHistoryData;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Village;
import tterrag1112.life_in_the_village.World.SeasonTracker;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class VillageEventScheduler {

    // =========================================================================
    // Scheduling tick — called once per day from NeedsUpdateEvent
    // =========================================================================

    /**
     * Checks whether any event should be scheduled for this village on
     * the current game tick.
     *
     * <h3>Annual events</h3>
     * Instead of matching a fixed day-of-year number, annual events now fire
     * on the first tick of the appropriate season transition:
     * <ul>
     *   <li>{@code HARVEST_FESTIVAL} — fires at the start of AUTUMN each year</li>
     *   <li>{@code FESTIVAL_OF_LIGHTS} — fires at the start of WINTER each year</li>
     * </ul>
     * This means the timing is driven by {@link SeasonTracker} rather than an
     * arbitrary hard-coded day constant, so changing the season length
     * automatically adjusts the festival calendar.
     *
     * <h3>Random events</h3>
     * Non-annual events are rolled for each day using the prosperity score as
     * a gate, exactly as before.
     */
    public static void tick(ServerLevel level, Village village,
                            VillageSavedData data, long currentTick) {

        // Phase 5 doc 32 calendar / crisis sources fire BEFORE the
        // single-active-event guard; they're triggered (not rolled) and
        // the scheduler tolerates concurrent events of different
        // categories from Phase 5 onward.
        checkCulturalHolyDay(level, village, data, currentTick);
        checkCrises(level, village, data, currentTick);

        // Don't pile up Phase-3-style ambient events — wait until the
        // current one finishes before random-rolling another.
        boolean hasActive = data.getActiveEventsForVillage(village.getId())
                .stream()
                .anyMatch(e -> e.isActive() || e.isAnnounced());
        if (hasActive) return;

        int prosperity = computeProsperity(level, village, data);

        // ── Annual events: season-transition based ────────────────────────────
        // isSeasonTransitionTick is true only on the very first tick of a
        // new season, so this block fires exactly once per season change.
        if (SeasonTracker.isSeasonTransitionTick(currentTick)) {
            SeasonTracker.Season season = SeasonTracker.currentSeason(currentTick);

            switch (season) {
                case AUTUMN -> {
                    // Harvest Festival at the start of every Autumn
                    scheduleEvent(level, village, data,
                            VillageEvent.EventType.HARVEST_FESTIVAL, currentTick);
                    return;
                }
                case WINTER -> {
                    // Festival of Lights at the start of every Winter
                    scheduleEvent(level, village, data,
                            VillageEvent.EventType.FESTIVAL_OF_LIGHTS, currentTick);
                    return;
                }
                default -> {
                    // Spring and Summer have no mandatory annual events.
                    // Fall through to random event roll below.
                }
            }
        }

        // ── Random events: prosperity-gated daily roll ────────────────────────
        for (VillageEvent.EventType type : VillageEvent.EventType.values()) {
            // Annual events are handled above by season transition — skip here
            if (type.isAnnual()) continue;
            if (prosperity < type.minProsperity()) continue;
            if (level.getRandom().nextFloat() < type.randomChance()) {
                scheduleEvent(level, village, data, type, currentTick);
                return; // Only one ambient event at a time
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Phase 5 doc 32: cultural holy days
    // ─────────────────────────────────────────────────────────────────

    /**
     * Schedules a culture-specific religious event whenever today's
     * day-of-year is a multiple of the culture's
     * {@code holyDayInterval}. Each culture maps to one holy-day
     * EventType; cultures with no interval fire nothing.
     */
    private static void checkCulturalHolyDay(ServerLevel level, Village village,
                                             VillageSavedData data, long currentTick) {
        // Only run on the first tick of a day to avoid scheduling
        // multiple holy-day events per day.
        if ((currentTick % 24000L) != 0L) return;

        Culture culture;
        try { culture = CultureResolver.of(level, village); }
        catch (RuntimeException ex) { return; }
        if (culture == null) return;

        Optional<Integer> intervalOpt = culture.schedule().holyDayInterval();
        if (intervalOpt.isEmpty()) return;
        int interval = intervalOpt.get();
        if (interval <= 0) return;

        int day = SeasonTracker.dayOfYear(currentTick);
        if (day % interval != 0) return;

        VillageEvent.EventType type = holyDayTypeFor(culture.id());
        if (type == null) return;

        // De-dupe: skip if today already has a holy-day event for this
        // village.
        boolean already = data.getAllEvents().stream()
                .filter(e -> e.getVillageId().equals(village.getId()))
                .anyMatch(e -> e.getType() == type
                        && currentTick - e.getStartTick() < 24000L);
        if (already) return;

        scheduleEvent(level, village, data, type, currentTick);
    }

    private static VillageEvent.EventType holyDayTypeFor(String cultureId) {
        if (cultureId == null) return null;
        return switch (cultureId.toLowerCase(java.util.Locale.ROOT)) {
            case "plainfolk" -> VillageEvent.EventType.SUNSTEAD_EQUINOX;
            case "highmarch" -> VillageEvent.EventType.FORGE_CREED_KINGDOM_DAY;
            case "silkwood"  -> VillageEvent.EventType.LOOM_THREADING;
            case "tidereach" -> VillageEvent.EventType.TIDECALL_FULL_MOON;
            default          -> null;
        };
    }

    // ─────────────────────────────────────────────────────────────────
    // Phase 5 doc 32: crisis polling (FAMINE, FIRE)
    // ─────────────────────────────────────────────────────────────────

    /**
     * Polls the village state once per day for crisis triggers.
     * FAMINE fires when the village's food need has been CRITICAL for
     * the past few days (we approximate "for N days" with a single
     * critical reading + a 7-day re-trigger lockout). FIRE is a low-
     * probability random roll.
     */
    private static void checkCrises(ServerLevel level, Village village,
                                    VillageSavedData data, long currentTick) {
        if ((currentTick % 24000L) != 0L) return;
        long lockout = 7L * 24000L;

        // FAMINE
        try {
            if (village.isFoodCritical()) {
                boolean recent = data.getAllEvents().stream()
                        .filter(e -> e.getVillageId().equals(village.getId()))
                        .anyMatch(e -> e.getType() == VillageEvent.EventType.FAMINE
                                && currentTick - e.getStartTick() < lockout);
                if (!recent) {
                    scheduleEvent(level, village, data,
                            VillageEvent.EventType.FAMINE, currentTick);
                }
            }
        } catch (RuntimeException ignored) {}

        // FIRE — 0.5% per day, lockout 7 days. Building density is a
        // future refinement (spec line 161).
        if (level.getRandom().nextFloat() < 0.005f) {
            boolean recent = data.getAllEvents().stream()
                    .filter(e -> e.getVillageId().equals(village.getId()))
                    .anyMatch(e -> e.getType() == VillageEvent.EventType.FIRE
                            && currentTick - e.getStartTick() < lockout);
            if (!recent) {
                scheduleEvent(level, village, data,
                        VillageEvent.EventType.FIRE, currentTick);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Phase 5 doc 32: public scheduling API for life events / crises
    // ─────────────────────────────────────────────────────────────────

    /**
     * Schedule an event with explicit attendees. Used by
     * {@link EventLifeEventProducer}, the plague subsystem, and the
     * {@code /event} debug commands. Returns the new event so callers
     * can mutate type-specific {@code eventData} before persistence.
     */
    public static VillageEvent scheduleLifeEvent(ServerLevel level, Village village,
                                                  VillageEvent.EventType type,
                                                  long scheduledTick,
                                                  UUID primarySubjectId,
                                                  List<UUID> requiredAttendees,
                                                  List<UUID> invitedAttendees) {
        if (level == null || village == null || type == null) return null;
        VillageEvent event = VillageEvent.create(village.getId(), type,
                scheduledTick, primarySubjectId,
                requiredAttendees == null ? List.of() : requiredAttendees,
                invitedAttendees  == null ? List.of() : invitedAttendees);
        VillageSavedData.get(level).addEvent(event);
        // R2a — attach the coordinated blessing rite (life-event ceremonies).
        CeremonyBlessings.attach(level, village, event);
        return event;
    }

    /** Public no-attendees overload for crises and the random-roll path. */
    public static VillageEvent scheduleSimple(ServerLevel level, Village village,
                                              VillageSavedData data,
                                              VillageEvent.EventType type,
                                              long currentTick) {
        scheduleEvent(level, village, data, type, currentTick);
        return data.getActiveEventsForVillage(village.getId()).stream()
                .filter(e -> e.getType() == type)
                .findFirst().orElse(null);
    }

    // =========================================================================
    // Event state tick — called every server tick to advance active events
    // =========================================================================

    /**
     * Advances ANNOUNCED events to ACTIVE and ACTIVE events to ENDED.
     * Called from the server tick handler every tick (not just once per day).
     */
    public static void tickEvents(ServerLevel level,
                                  VillageSavedData data,
                                  long currentTick) {
        List<VillageEvent> snapshot = new ArrayList<>(data.getAllEvents());

        for (VillageEvent event : snapshot) {
            Village village = data.getVillageById(event.getVillageId())
                    .orElse(null);
            if (village == null) continue;

            if (event.shouldStart(currentTick)) {
                event.setStatus(VillageEvent.EventStatus.ACTIVE);
                EventEffects.onEventStart(level, event, village, data);
                data.setDirty();
            } else if (event.shouldEnd(currentTick)) {
                event.setStatus(VillageEvent.EventStatus.ENDED);
                event.setCompletedTick(currentTick);
                EventAttendance.clearOverrides(level, event);
                EventEffects.onEventEnd(level, event, village, data);
                data.setDirty();
            }
        }

        // Phase 5 doc 32: keep ENDED / CANCELLED / DISRUPTED events for
        // 365 in-game days so they remain visible in /event list and
        // reachable for debug. After that they're dropped — the
        // village-history archive keeps their summary indefinitely.
        data.pruneOldCompletedEvents(currentTick);
    }

    // =========================================================================
    // Schedule a specific event
    // =========================================================================

    private static void scheduleEvent(ServerLevel level,
                                      Village village,
                                      VillageSavedData data,
                                      VillageEvent.EventType type,
                                      long currentTick) {
        VillageEvent event = VillageEvent.create(village.getId(), type, currentTick);
        data.addEvent(event);
        // R2a — attach the coordinated blessing rite (seasonal harvest +
        // per-culture holy days map to a rite; festivals/markets/crises don't).
        CeremonyBlessings.attach(level, village, event);

        // Record in kingdom history
        data.getKingdomForVillage(village.getId()).ifPresent(k -> {
            KingdomHistoryData.HistoryEventType histType = switch (type) {
                case HARVEST_FESTIVAL   -> KingdomHistoryData.HistoryEventType.GREAT_HARVEST;
                case MARKET_DAY,
                     VILLAGE_FAIR       -> KingdomHistoryData.HistoryEventType.FESTIVAL_HELD;
                default                 -> KingdomHistoryData.HistoryEventType.FESTIVAL_HELD;
            };

            k.getHistory().recordEvent(
                    HistoryTextGenerator.festivalHeld(
                            village.getName(),
                            type.name().replace("_", " ").toLowerCase(),
                            currentTick),
                    k.getName(),
                    k.getRulerName(level));
            data.setDirty();
        });

        System.out.println("VillageEventScheduler: scheduled "
                + type + " for village " + village.getName()
                + " (season: " + SeasonTracker.currentSeason(currentTick).displayName + ")");

        // Announce to nearby players
        village.getBounds(data).ifPresent(bounds ->
                level.players().stream()
                        .filter(p -> bounds.inflate(128).contains(
                                p.getX(), p.getY(), p.getZ()))
                        .forEach(p -> p.displayClientMessage(
                                net.minecraft.network.chat.Component.literal(
                                                "A " + type.name().replace("_", " ")
                                                        + " will begin soon in "
                                                        + village.getName() + "!")
                                        .withStyle(net.minecraft.ChatFormatting.YELLOW),
                                false))
        );
    }

    // =========================================================================
    // Prosperity score — used for random event gating and the status book
    // =========================================================================

    /**
     * Computes a prosperity score 0–100 for the given village.
     * Called externally by the {@code /building needs} command and the
     * village status book.
     */
    public static int getProsperity(ServerLevel level, Village village,
                                    VillageSavedData data) {
        return computeProsperity(level, village, data);
    }

    private static int computeProsperity(ServerLevel level,
                                         Village village,
                                         VillageSavedData data) {
        java.util.concurrent.atomic.AtomicInteger score =
                new java.util.concurrent.atomic.AtomicInteger(0);

        // Population
        long pop = level.getEntitiesOfClass(
                TownspersonMob.class,
                village.getBounds(data)
                        .map(b -> b.inflate(32))
                        .orElse(new net.minecraft.world.phys.AABB(0, 0, 0, 0, 0, 0)),
                mob -> mob.getAssignedVillageName()
                        .map(n -> n.equals(village.getName()))
                        .orElse(false)
        ).size();
        score.addAndGet((int) Math.min(30, pop * 3));

        // Building count
        score.addAndGet(Math.min(30, village.getBuildingIds().size() * 3));

        // Needs satisfaction
        village.getNeeds().forEach((cat, need) -> {
            switch (need.getLevel()) {
                case SURPLUS   -> score.addAndGet(10);
                case SATISFIED -> score.addAndGet(5);
                case LOW       -> score.addAndGet(-5);
                case CRITICAL  -> score.addAndGet(-15);
            }
        });

        // Treasury
        long treasury = village.getTreasuryBronze();
        if      (treasury > 1000) score.addAndGet(10);
        else if (treasury >  500) score.addAndGet(5);

        // Season bonus: prosperous summers and autumns lift the score slightly
        SeasonTracker.Season season = SeasonTracker.currentSeason(level);
        switch (season) {
            case SUMMER -> score.addAndGet(5);
            case AUTUMN -> score.addAndGet(3);
            case WINTER -> score.addAndGet(-5);
            default     -> {}
        }

        return Math.max(0, Math.min(100, score.get()));
    }
}