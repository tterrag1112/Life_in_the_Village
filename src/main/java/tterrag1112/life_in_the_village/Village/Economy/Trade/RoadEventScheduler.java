// src/main/java/tterrag1112/life_in_the_village/Village/Economy/Trade/RoadEventScheduler.java
package tterrag1112.life_in_the_village.Village.Economy.Trade;

import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Travel.TravellingGroupEngine;

import java.util.List;
import java.util.Random;

/**
 * Owns the lifecycle of {@link RoadEvent}s. Two responsibilities:
 *
 * <ol>
 *   <li><b>Event creation.</b> Periodically rolls new events onto
 *       active roads based on length, quality, and density caps.</li>
 *   <li><b>Engine ticking.</b> Drives every event through
 *       {@link TravellingGroupEngine#tick} every server tick so
 *       spawn/despawn/progress all happen through the shared system.</li>
 * </ol>
 *
 * <h3>Two intervals, one subsystem</h3>
 * Engine ticking runs every 20 ticks (1 second). Event creation runs
 * every 1200 ticks (1 minute). The same TickSubsystem handles both
 * by checking the current tick modulo each interval.
 */
public final class RoadEventScheduler {

    private static final int CREATE_INTERVAL_TICKS = 1200;
    private static final int ENGINE_INTERVAL_TICKS = 20;

    private static final int MAX_EVENTS_PER_ROAD = 3;
    private static final float EVENT_CHANCE_PER_MINUTE = 0.12f;

    private RoadEventScheduler() {}

    // =========================================================================
    // Engine tick — runs every second
    // =========================================================================

    /**
     * Drives every active road event through the travelling group
     * engine. Removes expired and finished events.
     */
    public static void tickEngine(ServerLevel level, VillageSavedData data) {
        long currentTick = level.getGameTime();
        boolean dirty = false;

        for (RoadEvent event : data.getAllRoadEvents()) {
            if (event.isExpired(currentTick) || event.isFinished()) {
                if (event.isSpawned()) event.onDespawn(level);
                data.removeRoadEvent(event.getEventId());
                dirty = true;
                continue;
            }

            if (TravellingGroupEngine.tick(event, level, data, currentTick)) {
                dirty = true;
            }
        }

        if (dirty) data.setDirty();
    }

    // =========================================================================
    // Creation tick — runs every minute
    // =========================================================================

    /**
     * Scans active roads and rolls new events for those under the
     * per-road event cap. Called from the same tick subsystem as
     * {@link #tickEngine} but at a longer interval.
     */
    public static void tickCreate(ServerLevel level, VillageSavedData data) {
        Random rng = new Random(level.getGameTime());

        List<TradeRoad> roads = data.getAllTradeRoads();
        for (TradeRoad road : roads) {
            if (!road.isActive()) continue;
            if (!road.isRealised()) continue;

            int existingEvents = data.getEventsForRoad(road.getRoadId()).size();
            if (existingEvents >= MAX_EVENTS_PER_ROAD) continue;

            float lengthFactor = Math.min(2.0f, road.getRoadLength() / 500f);
            float chance = EVENT_CHANCE_PER_MINUTE * lengthFactor;
            if (rng.nextFloat() > chance) continue;

            RoadEvent event = rollEventForRoad(road, level.getGameTime(), rng);
            if (event != null) {
                data.addRoadEvent(event);
                System.out.println("RoadEventScheduler: scheduled "
                        + event.getType()
                        + " on road "
                        + road.getRoadId().toString().substring(0, 8));
            }
        }
    }

    // =========================================================================
    // Combined tick entry for the TickSubsystem wrapper
    // =========================================================================

    /**
     * Single entry point for the registered TickSubsystem. Runs the
     * engine on every call and the creation pass at its own interval.
     */
    public static void tick(ServerLevel level, VillageSavedData data) {
        long currentTick = level.getGameTime();

        if (currentTick % ENGINE_INTERVAL_TICKS == 0) {
            tickEngine(level, data);
        }

        if (currentTick % CREATE_INTERVAL_TICKS == 0) {
            tickCreate(level, data);
        }
    }

    // =========================================================================
    // Event rolling — unchanged from Phase 7b
    // =========================================================================

    private static RoadEvent rollEventForRoad(TradeRoad road,
                                              long currentTick,
                                              Random rng) {
        int quality = road.getQuality();

        int banditWeight, traderWeight, pilgrimWeight;
        if (quality < 40) {
            banditWeight  = 5;
            traderWeight  = 1;
            pilgrimWeight = 2;
        } else if (quality < 80) {
            banditWeight  = 2;
            traderWeight  = 3;
            pilgrimWeight = 3;
        } else {
            banditWeight  = 1;
            traderWeight  = 4;
            pilgrimWeight = 4;
        }

        int totalWeight = banditWeight + traderWeight + pilgrimWeight;
        int roll = rng.nextInt(totalWeight);

        if (roll < banditWeight) {
            float pos = 0.2f + rng.nextFloat() * 0.6f;
            int count = 2 + rng.nextInt(3);
            return RoadEvent.banditAmbush(
                    road.getRoadId(), pos, currentTick, count);
        } else if (roll < banditWeight + traderWeight) {
            return RoadEvent.wanderingTrader(road.getRoadId(), currentTick);
        } else {
            int count = 3 + rng.nextInt(4);
            return RoadEvent.pilgrimGroup(road.getRoadId(), currentTick, count);
        }
    }
}