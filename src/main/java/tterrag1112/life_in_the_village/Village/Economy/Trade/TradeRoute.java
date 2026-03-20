package tterrag1112.life_in_the_village.Village.Economy.Trade;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class TradeRoute {

    public enum RouteStatus {
        ACTIVE,
        SUSPENDED,   // temporarily halted
        BLOCKED;

        public static final Codec<RouteStatus> CODEC = Codec.STRING.xmap(
                RouteStatus::valueOf, RouteStatus::name);// permanently blocked by hostility
    }

    public enum RouteType {
        KINGDOM_INTERNAL,  // same kingdom — free
        CROSS_KINGDOM,     // different kingdoms — requires agreement
        NEUTRAL;           // one or both villages have no kingdom
        public static final Codec<RouteType> CODEC = Codec.STRING.xmap(
                RouteType::valueOf, RouteType::name);
    }

    public static final Codec<TradeRoute> CODEC =
            RecordCodecBuilder.create(instance -> instance.group(
                    Codec.STRING.xmap(UUID::fromString,
                                    UUID::toString)
                            .fieldOf("routeId")
                            .forGetter(TradeRoute::getRouteId),
                    Codec.STRING.xmap(UUID::fromString,
                                    UUID::toString)
                            .fieldOf("villageA")
                            .forGetter(TradeRoute::getVillageA),
                    Codec.STRING.xmap(UUID::fromString,
                                    UUID::toString)
                            .fieldOf("villageB")
                            .forGetter(TradeRoute::getVillageB),
                    Codec.STRING.xmap(UUID::fromString,
                                    UUID::toString)
                            .fieldOf("roadId")
                            .forGetter(TradeRoute::getRoadId),
                    RouteStatus.CODEC.fieldOf("status")
                            .forGetter(TradeRoute::getStatus),
                    RouteType.CODEC.fieldOf("routeType")
                            .forGetter(TradeRoute::getRouteType),
                    Codec.LONG.fieldOf("establishedTick")
                            .forGetter(TradeRoute::getEstablishedTick),
                    Codec.LONG.fieldOf("lastCaravanTick")
                            .forGetter(TradeRoute::getLastCaravanTick),
                    Codec.DOUBLE.fieldOf("tradePenalty")
                            .forGetter(TradeRoute::getTradePenalty)
            ).apply(instance, TradeRoute::new));

    // Codecs for enums
    static {
        // These are defined inline via xmap in the record codec
    }

    private final UUID routeId;
    private final UUID villageA;
    private final UUID villageB;
    private final UUID roadId;
    private RouteStatus status;
    private final RouteType routeType;
    private final long establishedTick;
    private long lastCaravanTick;
    private double tradePenalty; // 0.0 = no penalty, 1.0 = blocked

    public TradeRoute(UUID routeId, UUID villageA, UUID villageB,
                      UUID roadId, RouteStatus status,
                      RouteType routeType, long establishedTick,
                      long lastCaravanTick, double tradePenalty) {
        this.routeId        = routeId;
        this.villageA       = villageA;
        this.villageB       = villageB;
        this.roadId         = roadId;
        this.status         = status;
        this.routeType      = routeType;
        this.establishedTick = establishedTick;
        this.lastCaravanTick = lastCaravanTick;
        this.tradePenalty   = tradePenalty;
    }

    public static TradeRoute create(UUID villageA, UUID villageB,
                                    UUID roadId, RouteType type,
                                    long currentTick) {
        double penalty = switch (type) {
            case KINGDOM_INTERNAL -> 0.0;
            case NEUTRAL          -> 0.1;
            case CROSS_KINGDOM    -> 0.25;
        };

        return new TradeRoute(
                UUID.randomUUID(),
                villageA, villageB,
                roadId,
                RouteStatus.ACTIVE,
                type,
                currentTick,
                0L,
                penalty
        );
    }

    // -------------------------------------------------------------------------
    // Trade calculations
    // -------------------------------------------------------------------------

    public boolean isTradeAllowed() {
        return status == RouteStatus.ACTIVE
                && tradePenalty < 1.0;
    }

    /**
     * Returns effective trade efficiency — how much of the
     * goods actually arrive. Affected by road quality,
     * distance, and kingdom penalty.
     */
    public double getTradeEfficiency(TradeRoad road) {
        if (!isTradeAllowed()) return 0.0;

        double qualityFactor  = road.getQuality() / 100.0;
        double distanceFactor = Math.max(0.3,
                1.0 - (road.getRoadLength() / 10000.0));
        double penaltyFactor  = 1.0 - tradePenalty;

        return qualityFactor * distanceFactor * penaltyFactor;
    }

    /**
     * Returns caravan travel speed multiplier.
     * Quality 100 = normal speed.
     * Quality 0 = half speed.
     */
    public double getCaravanSpeedMultiplier(TradeRoad road) {
        return 0.5 + (road.getQuality() / 100.0) * 0.5;
    }

    /**
     * Returns daily caravan chance based on road quality.
     * Suspended routes never dispatch caravans.
     */
    public float getDailyCaravanChance(TradeRoad road) {
        if (status == RouteStatus.SUSPENDED) return 0f;

        float qualityFactor  = road.getQuality() / 100.0f;
        float distanceFactor = Math.max(0.1f,
                1.0f - (road.getRoadLength() / 5000.0f));
        float penaltyFactor  = (float)(1.0 - tradePenalty);

        return qualityFactor * distanceFactor
                * penaltyFactor * 0.8f;
    }

    public boolean connects(UUID a, UUID b) {
        return (villageA.equals(a) && villageB.equals(b))
                || (villageA.equals(b) && villageB.equals(a));
    }

    // -------------------------------------------------------------------------
    // Getters / setters
    // -------------------------------------------------------------------------

    public UUID getRouteId()               { return routeId; }
    public UUID getVillageA()              { return villageA; }
    public UUID getVillageB()              { return villageB; }
    public UUID getRoadId()                { return roadId; }
    public RouteStatus getStatus()         { return status; }
    public RouteType getRouteType()        { return routeType; }
    public long getEstablishedTick()       { return establishedTick; }
    public long getLastCaravanTick()       { return lastCaravanTick; }
    public double getTradePenalty()        { return tradePenalty; }
    public void setStatus(RouteStatus s)   { this.status = s; }
    public void setLastCaravanTick(long t) { this.lastCaravanTick = t; }
    public void setTradePenalty(double p)  { this.tradePenalty = Math.max(0, Math.min(1, p)); }


    // Add to TradeRoute.java
    public static class ClientRouteCache {
        private static List<TradeRoute> routes = new ArrayList<>();

        public static void setRoutes(List<TradeRoute> list) {
            routes = new ArrayList<>(list);
        }

        public static List<TradeRoute> getRoutes() {
            return Collections.unmodifiableList(routes);
        }

        public static List<TradeRoute> getRoutesForVillage(UUID villageId) {
            return routes.stream()
                    .filter(r -> r.villageA.equals(villageId)
                            || r.villageB.equals(villageId))
                    .toList();
        }
    }
}