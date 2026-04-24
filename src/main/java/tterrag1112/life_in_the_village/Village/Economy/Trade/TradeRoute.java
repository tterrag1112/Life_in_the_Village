package tterrag1112.life_in_the_village.Village.Economy.Trade;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
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
                            .optionalFieldOf("connectionId")
                            .forGetter(r -> Optional.ofNullable(r.connectionId)),
                    RouteStatus.CODEC.fieldOf("status")
                            .forGetter(TradeRoute::getStatus),
                    RouteType.CODEC.fieldOf("routeType")
                            .forGetter(TradeRoute::getRouteType),
                    Codec.LONG.fieldOf("establishedTick")
                            .forGetter(TradeRoute::getEstablishedTick),
                    Codec.LONG.fieldOf("lastCaravanTick")
                            .forGetter(TradeRoute::getLastCaravanTick),
                    Codec.DOUBLE.fieldOf("tradePenalty")
                            .forGetter(TradeRoute::getTradePenalty),
                    Codec.STRING.xmap(UUID::fromString, UUID::toString).listOf()
                            .optionalFieldOf("edgeIds", List.of())
                            .forGetter(r -> new ArrayList<>(r.edgeIds)),
                    Codec.STRING.xmap(UUID::fromString, UUID::toString)
                            .optionalFieldOf("routeStartNodeId")
                            .forGetter(r -> Optional.ofNullable(r.routeStartNodeId)),
                    RouteSegment.CODEC.listOf()
                            .optionalFieldOf("segments", List.of())
                            .forGetter(r -> new ArrayList<>(r.segments))
            ).apply(instance, TradeRoute::fromCodec));

    // Codecs for enums
    static {
        // These are defined inline via xmap in the record codec
    }

    private final UUID routeId;
    private final UUID villageA;
    private final UUID villageB;
    /** Nullable — null for graph-based routes that have no legacy TradeRoad. */
    private final UUID connectionId;
    private RouteStatus status;
    private final RouteType routeType;
    private final long establishedTick;
    private long lastCaravanTick;
    private double tradePenalty; // 0.0 = no penalty, 1.0 = blocked

    /** Ordered edge IDs for graph-based routes. Empty for legacy routes. */
    private final List<UUID> edgeIds;
    /** Node ID at village-A's end of the first edge; null for legacy routes. */
    private final UUID routeStartNodeId;
    /** Ordered route segments (world edges + village traversals). Supersedes edgeIds when non-empty. */
    private final List<RouteSegment> segments;

    private TradeRoute(UUID routeId, UUID villageA, UUID villageB,
                       UUID connectionId, RouteStatus status,
                       RouteType routeType, long establishedTick,
                       long lastCaravanTick, double tradePenalty,
                       List<UUID> edgeIds, UUID routeStartNodeId,
                       List<RouteSegment> segments) {
        this.routeId          = routeId;
        this.villageA         = villageA;
        this.villageB         = villageB;
        this.connectionId     = connectionId;
        this.status           = status;
        this.routeType        = routeType;
        this.establishedTick  = establishedTick;
        this.lastCaravanTick  = lastCaravanTick;
        this.tradePenalty     = tradePenalty;
        this.edgeIds          = new ArrayList<>(edgeIds);
        this.routeStartNodeId = routeStartNodeId;
        this.segments         = new ArrayList<>(segments);
    }

    /** Legacy constructor — for code that creates TradeRoutes with a TradeRoad connectionId. */
    public TradeRoute(UUID routeId, UUID villageA, UUID villageB,
                      UUID roadId, RouteStatus status,
                      RouteType routeType, long establishedTick,
                      long lastCaravanTick, double tradePenalty) {
        this(routeId, villageA, villageB, roadId, status, routeType,
                establishedTick, lastCaravanTick, tradePenalty, List.of(), null, List.of());
    }

    static TradeRoute fromCodec(UUID routeId, UUID villageA, UUID villageB,
                                Optional<UUID> connectionId, RouteStatus status, RouteType routeType,
                                long establishedTick, long lastCaravanTick, double tradePenalty,
                                List<UUID> edgeIds, Optional<UUID> routeStartNodeId,
                                List<RouteSegment> segments) {
        return new TradeRoute(routeId, villageA, villageB, connectionId.orElse(null), status, routeType,
                establishedTick, lastCaravanTick, tradePenalty, edgeIds, routeStartNodeId.orElse(null),
                segments);
    }

    /** Creates a graph-based route with a known edge path and no legacy connectionId. */
    public static TradeRoute createGraph(UUID villageA, UUID villageB,
                                         RouteType type, long currentTick,
                                         List<UUID> edgeIds, UUID routeStartNodeId) {
        double penalty = switch (type) {
            case KINGDOM_INTERNAL -> 0.0;
            case NEUTRAL          -> 0.1;
            case CROSS_KINGDOM    -> 0.25;
        };
        return new TradeRoute(UUID.randomUUID(), villageA, villageB, null, RouteStatus.ACTIVE, type,
                currentTick, 0L, penalty, edgeIds, routeStartNodeId, List.of());
    }

    /** Creates a segment-based route. The {@code segments} list encodes the full traversal. */
    public static TradeRoute createSegmented(UUID villageA, UUID villageB,
                                              RouteType type, long currentTick,
                                              List<RouteSegment> segments) {
        double penalty = switch (type) {
            case KINGDOM_INTERNAL -> 0.0;
            case NEUTRAL          -> 0.1;
            case CROSS_KINGDOM    -> 0.25;
        };
        return new TradeRoute(UUID.randomUUID(), villageA, villageB, null, RouteStatus.ACTIVE, type,
                currentTick, 0L, penalty, List.of(), null, segments);
    }

    public static TradeRoute create(UUID villageA, UUID villageB,
                                    UUID connectionId, RouteType type,
                                    long currentTick) {
        double penalty = switch (type) {
            case KINGDOM_INTERNAL -> 0.0;
            case NEUTRAL          -> 0.1;
            case CROSS_KINGDOM    -> 0.25;
        };

        return new TradeRoute(
                UUID.randomUUID(),
                villageA, villageB,
                connectionId,
                RouteStatus.ACTIVE,
                type,
                currentTick,
                0L,
                penalty,
                List.of(), null, List.of()
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
    /** Returns the legacy TradeRoad ID, or null for graph-based routes. */
    public UUID getConnectionId()          { return connectionId; }
    /** Returns the ordered edge IDs for graph-based routes. Empty for legacy routes. */
    public List<UUID> getEdgeIds()         { return Collections.unmodifiableList(edgeIds); }
    /** Returns the graph node ID at village-A's end. Null for legacy routes. */
    public UUID getRouteStartNodeId()      { return routeStartNodeId; }
    /** True when this route is backed by WorldRoadGraph edges rather than a legacy TradeRoad. */
    public boolean hasGraphPath()          { return !edgeIds.isEmpty(); }
    /** Returns the ordered route segments (world edges + village traversals). */
    public List<RouteSegment> getSegments() { return Collections.unmodifiableList(segments); }
    /** True when this route is backed by RouteSegments rather than (or in addition to) legacy edgeIds. */
    public boolean hasSegments()           { return !segments.isEmpty(); }
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