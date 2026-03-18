package tterrag1112.life_in_the_village.Village.Event;

import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Lore.HistoryTextGenerator;
import tterrag1112.life_in_the_village.Lore.KingdomHistoryData;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class VillageEventScheduler {

    private static final long TICKS_PER_YEAR = 24000L * 365;

    /**
     * Called once per day from NeedsUpdateEvent.
     * Checks for annual events and rolls for random events.
     */
    public static void tick(ServerLevel level, Village village,
                            VillageSavedData data, long currentTick) {

        // Don't schedule if event already active or announced
        boolean hasActive = data.getActiveEventsForVillage(village.getId())
                .stream()
                .anyMatch(e -> e.isActive() || e.isAnnounced());
        if (hasActive) return;

        long dayOfYear = (currentTick / 24000L) % 365;
        int prosperity = computeProsperity(level, village, data);

        // Check annual events
        for (VillageEvent.EventType type : VillageEvent.EventType.values()) {
            if (!type.isAnnual()) continue;
            if (dayOfYear == type.annualDay()) {
                scheduleEvent(level, village, data, type, currentTick);
                return;
            }
        }

        // Roll for random events
        for (VillageEvent.EventType type : VillageEvent.EventType.values()) {
            if (type.isAnnual()) continue;
            if (prosperity < type.minProsperity()) continue;
            if (level.getRandom().nextFloat() < type.randomChance()) {
                scheduleEvent(level, village, data, type, currentTick);
                return; // only one event at a time
            }
        }
    }

    /**
     * Called every server tick to advance event state.
     */
    public static void tickEvents(ServerLevel level,
                                  VillageSavedData data,
                                  long currentTick) {
        // Take a snapshot to avoid ConcurrentModificationException
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

        // Remove ended events after iteration is complete
        data.removeEndedEvents();
    }

    private static void scheduleEvent(ServerLevel level,
                                      Village village,
                                      VillageSavedData data,
                                      VillageEvent.EventType type,
                                      long currentTick) {
        VillageEvent event = VillageEvent.create(
                village.getId(), type, currentTick);
        data.addEvent(event);
        data.getKingdomForVillage(village.getId())
                .ifPresent(k -> {
                    KingdomHistoryData.HistoryEventType histType =
                            switch (type) {
                                case HARVEST_FESTIVAL ->
                                        KingdomHistoryData
                                                .HistoryEventType
                                                .GREAT_HARVEST;
                                case MARKET_DAY,
                                     VILLAGE_FAIR ->
                                        KingdomHistoryData
                                                .HistoryEventType
                                                .FESTIVAL_HELD;
                                default ->
                                        KingdomHistoryData
                                                .HistoryEventType
                                                .FESTIVAL_HELD;
                            };

                    k.getHistory().recordEvent(
                            HistoryTextGenerator.festivalHeld(village.getName(), type.name().replace("_", " ")
                                    .toLowerCase(), currentTick), k.getName(), k.getRulerName(level));
                    data.setDirty();
                });
        data.setDirty();

        System.out.println("Scheduled " + type
                + " for village " + village.getName());

        // Announce upcoming event to nearby players
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

    /**
     * Prosperity score 0-100 based on village state.
     */
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
                        .orElse(new net.minecraft.world.phys.AABB(0,0,0,0,0,0)),
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
        if (treasury > 1000) score.addAndGet(10);
        else if (treasury > 500) score.addAndGet(5);

        return Math.max(0, Math.min(100, score.get()));
    }

    public static int getProsperity(ServerLevel level, Village village,
                                    VillageSavedData data) {
        return computeProsperity(level, village, data);
    }
}