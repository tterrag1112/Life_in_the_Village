// src/main/java/tterrag1112/life_in_the_village/Village/Event/VillageEventScheduler.java
package tterrag1112.life_in_the_village.Village.Event;

import net.minecraft.server.level.ServerLevel;
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

        // Don't pile up events — wait until the current one finishes
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
                return; // Only one event at a time
            }
        }
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
                EventEffects.onEventEnd(level, event, village, data);
                data.setDirty();
            }
        }

        data.removeEndedEvents();
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