// src/main/java/tterrag1112/life_in_the_village/Village/Event/VillageEventScheduler.java
package tterrag1112.life_in_the_village.Village.Event;

import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Cultures.Culture;
import tterrag1112.life_in_the_village.Cultures.CultureResolver;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Lore.HistoryTextGenerator;
import tterrag1112.life_in_the_village.Lore.KingdomHistoryData;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Health.HealthCondition;
import tterrag1112.life_in_the_village.Npc.Religion.BuildingFaith;
import tterrag1112.life_in_the_village.Npc.Religion.Religion;
import tterrag1112.life_in_the_village.Npc.Religion.ReligionContent;
import tterrag1112.life_in_the_village.Npc.Religion.ReligionRegistry;
import tterrag1112.life_in_the_village.Npc.Religion.ReligiousCalendar;
import tterrag1112.life_in_the_village.Npc.Religion.Rite;
import tterrag1112.life_in_the_village.Npc.Religion.RiteScheduler;
import tterrag1112.life_in_the_village.Village.Village;
import tterrag1112.life_in_the_village.World.SeasonTracker;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
        checkGrandFestival(level, village, data, currentTick);   // R3d-2 (before holy-day → supersede)
        checkCulturalHolyDay(level, village, data, currentTick);
        checkCalendarVigil(level, village, data, currentTick);   // R3b-2
        checkPurification(level, village, data, currentTick);    // R3b-2
        checkSignatureRite(level, village, data, currentTick);   // R3b-3
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

        // R3d-2 supersede: if today is this village religion's grand-festival
        // principal day, the grand festival (checkGrandFestival, run first)
        // owns the day — don't also fire the generic holy-day blessing.
        if (isGrandFestivalDay(level, village, currentTick)) return;

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

    // ─────────────────────────────────────────────────────────────────
    // Religion Rework R3b-2: Vigil (calendar) + Purification (distress)
    // ─────────────────────────────────────────────────────────────────

    /** Number of MELANCHOLY villagers that triggers a communal purification. */
    private static final int PURIFICATION_DISTRESS_THRESHOLD = 3;
    /** Re-trigger lockout for purification (mirrors the crisis lockout). */
    private static final long PURIFICATION_LOCKOUT_TICKS = 14L * 24000L;

    /**
     * R3b-2 — schedules a VIGIL gathering when today (in the religion's 365-day
     * liturgical calendar) matches one of the village religion's "Vigil"-named
     * holy days (Forge Creed "Anvil Vigil", Tidecall "Storm's Vigil"). Revives
     * the religion calendar's named days, which nothing has consumed since R2a
     * removed the calendar-rite path. The liturgical calendar (365-day,
     * {@link ReligiousCalendar#DAYS_PER_YEAR}) is distinct from the 96-day
     * seasonal cycle, so it uses its own day-of-year here.
     */
    private static void checkCalendarVigil(ServerLevel level, Village village,
                                           VillageSavedData data, long currentTick) {
        if ((currentTick % 24000L) != 0L) return;   // once per day
        // R3e-2b — each present faith's vigil days at its own building. VIGIL is
        // a shared EventType, so the per-day dedup keys on faith (see
        // alreadyScheduledToday) — two faiths' vigils on the same day both fire.
        for (Map.Entry<String, tterrag1112.life_in_the_village.Village.Building> e
                : BuildingFaith.religiousBuildingsByFaith(level, village).entrySet()) {
            scheduleVigilForFaith(level, village, data, currentTick, e.getKey(), e.getValue());
        }
    }

    private static void scheduleVigilForFaith(ServerLevel level, Village village,
                                              VillageSavedData data, long currentTick,
                                              String religionId,
                                              tterrag1112.life_in_the_village.Village.Building venue) {
        Religion religion = ReligionRegistry.get(religionId);
        if (religion == null) return;

        int litDay = (int) ((currentTick / 24000L) % ReligiousCalendar.DAYS_PER_YEAR);
        boolean vigilToday = false;
        for (Map.Entry<String, Integer> e : religion.calendar().holyDaysByName().entrySet()) {
            if (!e.getKey().toLowerCase(java.util.Locale.ROOT).contains("vigil")) continue;
            Integer eff = religion.calendar().effectiveDayOfYear(e.getKey());
            if (eff != null && eff == litDay) { vigilToday = true; break; }
        }
        if (!vigilToday) return;

        if (alreadyScheduledToday(data, village, VillageEvent.EventType.VIGIL, religionId, currentTick)) return;
        scheduleEvent(level, village, data, VillageEvent.EventType.VIGIL, currentTick, religionId, venue);
    }

    /**
     * R3b-2 — distress-driven communal purification. When enough villagers carry
     * MELANCHOLY and the village religion ritualises PURIFICATION, schedules a
     * PURIFICATION gathering with the afflicted as required attendees (they flow
     * into the blessing rite's participants, which clears their MELANCHOLY). A
     * lockout bounds re-scheduling (the unpruned ledger / event store); not
     * officiant-gated (mirrors the crisis path — the gathering is the
     * observance, the rite is its best-effort blessing).
     */
    private static void checkPurification(ServerLevel level, Village village,
                                          VillageSavedData data, long currentTick) {
        if ((currentTick % 24000L) != 0L) return;   // once per day
        if (!RiteScheduler.villageRitualises(level, village, Rite.PURIFICATION)) return;

        // Lockout: skip if a recent PURIFICATION already ran for this village.
        boolean recent = data.getAllEvents().stream()
                .filter(ev -> ev.getVillageId().equals(village.getId()))
                .anyMatch(ev -> ev.getType() == VillageEvent.EventType.PURIFICATION
                        && currentTick - ev.getStartTick() < PURIFICATION_LOCKOUT_TICKS);
        if (recent) return;

        List<UUID> afflicted = afflictedVillagers(level, village, data);
        if (afflicted.size() < PURIFICATION_DISTRESS_THRESHOLD) return;

        List<UUID> invited = EventAttendance.villageNpcIds(level, village, data);
        VillageEventScheduler.scheduleLifeEvent(level, village,
                VillageEvent.EventType.PURIFICATION, currentTick + 1200L,
                null, afflicted, invited);
    }

    /**
     * R3b-3 — schedules a faith's named signature gathering on its own calendar
     * day (365-day liturgical axis, reusing the R3b-2 revived-calendar path):
     * Sunstead First Furrow @ Spring Equinox, Loom Thread-Binding @ First
     * Threading, Tidecall Voyage Blessing @ First Catch, Forge Ancestor Oath @
     * Ancestor Day. Faith-gated (only that religion's village schedules its own
     * signature). The shared {@code Rite.SIGNATURE_RITE} blessing
     * (ritualises-gated) differentiates the effect via ReligionContent.
     */
    private static void checkSignatureRite(ServerLevel level, Village village,
                                           VillageSavedData data, long currentTick) {
        if ((currentTick % 24000L) != 0L) return;   // once per day
        // R3e-2b — fire EACH present faith's signature on its own calendar day,
        // at its own building (the dominant at its temple/chapel, a minority at
        // its shrine). Single-faith villages iterate one faith → unchanged.
        for (Map.Entry<String, tterrag1112.life_in_the_village.Village.Building> e
                : BuildingFaith.religiousBuildingsByFaith(level, village).entrySet()) {
            scheduleSignatureForFaith(level, village, data, currentTick, e.getKey(), e.getValue());
        }
    }

    private static void scheduleSignatureForFaith(ServerLevel level, Village village,
                                                  VillageSavedData data, long currentTick,
                                                  String religionId,
                                                  tterrag1112.life_in_the_village.Village.Building venue) {
        Religion religion = ReligionRegistry.get(religionId);
        if (religion == null) return;

        VillageEvent.EventType type;
        String dayName;
        if (religionId.equals(ReligionRegistry.SUNSTEAD)) {
            type = VillageEvent.EventType.FIRST_FURROW;    dayName = "Spring Equinox";
        } else if (religionId.equals(ReligionRegistry.THE_LOOM)) {
            type = VillageEvent.EventType.THREAD_BINDING;  dayName = "First Threading";
        } else if (religionId.equals(ReligionRegistry.TIDECALL)) {
            type = VillageEvent.EventType.VOYAGE_BLESSING; dayName = "First Catch";
        } else if (religionId.equals(ReligionRegistry.FORGE_CREED)) {
            type = VillageEvent.EventType.ANCESTOR_OATH;   dayName = "Ancestor Day";
        } else {
            return;
        }

        Integer eff = religion.calendar().effectiveDayOfYear(dayName);
        if (eff == null) return;
        int litDay = (int) ((currentTick / 24000L) % ReligiousCalendar.DAYS_PER_YEAR);
        if (eff != litDay) return;

        if (alreadyScheduledToday(data, village, type, religionId, currentTick)) return;
        scheduleEvent(level, village, data, type, currentTick, religionId, venue);
    }

    /** Loaded village NPCs currently carrying MELANCHOLY. */
    private static List<UUID> afflictedVillagers(ServerLevel level, Village village,
                                                 VillageSavedData data) {
        List<UUID> out = new ArrayList<>();
        village.getBounds(data).ifPresent(bounds ->
                level.getEntitiesOfClass(TownspersonMob.class, bounds.inflate(32),
                                m -> m.getAssignedVillageName()
                                        .map(n -> n.equals(village.getName())).orElse(false)
                                        && m.getHealthComponent().hasCondition(HealthCondition.MELANCHOLY))
                        .forEach(m -> out.add(m.getUUID())));
        return out;
    }

    // ─────────────────────────────────────────────────────────────────
    // Religion Rework R3d-2: grand annual faith festivals
    // ─────────────────────────────────────────────────────────────────

    /**
     * R3d-2 — schedules a faith's grand annual festival on its principal high
     * holy day (365-day liturgical axis): Sunstead Harvest Home @ Harvest
     * Equinox, Loom Great Weaving @ Fourth Threading, Tidecall Tides' Return @
     * Last Catch, Forge Founding Day @ Founding Day. Faith-gated; the shared
     * {@code Rite.GRAND_FESTIVAL} blessing (ritualises-gated) differentiates the
     * (richer) effect via ReligionContent. RELIGIOUS_RITE → fronted (R3d-1) +
     * congregation (R2b) automatically. On its day it SUPERSEDES the generic
     * holy-day blessing (see {@code checkCulturalHolyDay}).
     */
    private static void checkGrandFestival(ServerLevel level, Village village,
                                           VillageSavedData data, long currentTick) {
        if ((currentTick % 24000L) != 0L) return;   // once per day
        // R3e-2b — each present faith's grand festival at its own building.
        for (Map.Entry<String, tterrag1112.life_in_the_village.Village.Building> e
                : BuildingFaith.religiousBuildingsByFaith(level, village).entrySet()) {
            scheduleGrandForFaith(level, village, data, currentTick, e.getKey(), e.getValue());
        }
    }

    private static void scheduleGrandForFaith(ServerLevel level, Village village,
                                              VillageSavedData data, long currentTick,
                                              String religionId,
                                              tterrag1112.life_in_the_village.Village.Building venue) {
        Religion religion = ReligionRegistry.get(religionId);
        if (religion == null) return;

        VillageEvent.EventType type = grandFestivalType(religionId);
        String dayName = grandFestivalDayName(religionId);
        if (type == null || dayName == null) return;

        Integer eff = religion.calendar().effectiveDayOfYear(dayName);
        if (eff == null) return;
        int litDay = (int) ((currentTick / 24000L) % ReligiousCalendar.DAYS_PER_YEAR);
        if (eff != litDay) return;

        if (alreadyScheduledToday(data, village, type, religionId, currentTick)) return;
        scheduleEvent(level, village, data, type, currentTick, religionId, venue);
    }

    /** True when today (liturgical day) is the village religion's grand-festival
     *  principal day — used to suppress the generic holy-day blessing. */
    private static boolean isGrandFestivalDay(ServerLevel level, Village village,
                                              long currentTick) {
        Religion religion = ReligionRegistry.get(
                ReligionContent.villageReligionId(level, village));
        if (religion == null) return false;
        String dayName = grandFestivalDayName(religion.id());
        if (dayName == null) return false;
        Integer eff = religion.calendar().effectiveDayOfYear(dayName);
        if (eff == null) return false;
        int litDay = (int) ((currentTick / 24000L) % ReligiousCalendar.DAYS_PER_YEAR);
        return eff == litDay;
    }

    /** religionId → its grand-festival EventType (the headline named gathering). */
    private static VillageEvent.EventType grandFestivalType(String religionId) {
        if (ReligionRegistry.SUNSTEAD.equals(religionId))    return VillageEvent.EventType.HARVEST_HOME;
        if (ReligionRegistry.THE_LOOM.equals(religionId))    return VillageEvent.EventType.GREAT_WEAVING;
        if (ReligionRegistry.TIDECALL.equals(religionId))    return VillageEvent.EventType.TIDES_RETURN;
        if (ReligionRegistry.FORGE_CREED.equals(religionId)) return VillageEvent.EventType.FOUNDING_DAY;
        return null;
    }

    /**
     * R3e-2b — shared per-faith daily dedup: has a gathering of {@code type}
     * stamped with {@code faithId} already fired in this village today? For a
     * faith-unique EventType (signature/grand) the faith filter is a harmless
     * no-op; for a shared type (VIGIL) it separates faiths so each fires once.
     */
    private static boolean alreadyScheduledToday(VillageSavedData data, Village village,
                                                 VillageEvent.EventType type,
                                                 String faithId, long currentTick) {
        return data.getAllEvents().stream()
                .filter(ev -> ev.getVillageId().equals(village.getId()))
                .filter(ev -> ev.getType() == type)
                .filter(ev -> currentTick - ev.getStartTick() < 24000L)
                .anyMatch(ev -> faithId == null
                        || faithId.equals(ev.getEventData().get(CeremonyBlessings.FAITH_KEY)));
    }

    /** religionId → the calendar-day name of its principal high holy day. */
    private static String grandFestivalDayName(String religionId) {
        if (ReligionRegistry.SUNSTEAD.equals(religionId))    return "Harvest Equinox";
        if (ReligionRegistry.THE_LOOM.equals(religionId))    return "Fourth Threading";
        if (ReligionRegistry.TIDECALL.equals(religionId))    return "Last Catch";
        if (ReligionRegistry.FORGE_CREED.equals(religionId)) return "Founding Day";
        return null;
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
        scheduleEvent(level, village, data, type, currentTick, null, null);
    }

    /**
     * R3e-2b — faith-aware scheduling. A per-faith calendar gathering stamps its
     * faith ({@link CeremonyBlessings#FAITH_KEY}) and pins its location to the
     * faith's {@code venue} building (a shrine for a minority faith), so the
     * blessing rite ({@link CeremonyBlessings#attach}) is gated/tuned to that
     * faith and the officiant + congregation converge there. Both null → the
     * legacy dominant-at-temple path (identical to before).
     */
    private static void scheduleEvent(ServerLevel level,
                                      Village village,
                                      VillageSavedData data,
                                      VillageEvent.EventType type,
                                      long currentTick,
                                      String faithId,
                                      tterrag1112.life_in_the_village.Village.Building venue) {
        VillageEvent event = VillageEvent.create(village.getId(), type, currentTick);
        if (faithId != null) event.putEventData(CeremonyBlessings.FAITH_KEY, faithId);
        if (venue != null && venue.getShape().getOrigin() != null) {
            event.setLocation(venue.getShape().getOrigin());
        }
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