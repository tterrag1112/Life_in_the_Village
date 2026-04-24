package tterrag1112.life_in_the_village.Village.Roads.Graph;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;

import java.util.Optional;
import java.util.UUID;

/**
 * A node in a village's internal road graph.
 *
 * <p>Most nodes are {@link NodeType#INTERIOR} junctions or endpoints.
 * {@link NodeType#GATEWAY} nodes represent the village boundary where an
 * internal path exits into the world road network. Each gateway carries a
 * {@link GatewayInfo} record with directional and role metadata.
 *
 * <p>This record is separate from {@link RoadNode} (the world-trade-graph node)
 * because village road graphs are scoped to a single village, far smaller in
 * scale, and carry different metadata (gateway orientation, arm endpoint, role).
 */
public record VillageRoadNode(
        UUID nodeId,
        BlockPos position,
        NodeType type,
        Optional<GatewayInfo> gatewayInfo
) {

    // =========================================================================
    // Node type
    // =========================================================================

    public enum NodeType {
        /** Ordinary village intersection or dead-end endpoint. */
        INTERIOR,
        /** Village boundary point that links to the world road network. */
        GATEWAY,
        /** Notable internal node (central plaza, bell tower). Reserved for future use. */
        LANDMARK;

        public static final Codec<NodeType> CODEC =
                Codec.STRING.xmap(NodeType::valueOf, NodeType::name);
    }

    // =========================================================================
    // Gateway metadata
    // =========================================================================

    /**
     * Extra information carried by {@link NodeType#GATEWAY} nodes.
     *
     * @param worldNodeId        matching TERMINUS node UUID in WorldRoadGraph;
     *                           empty until Slice 3 wires the bidirectional link
     * @param outwardDirection   compass direction the gateway faces away from the village
     * @param armEndpoint        position 20–40 blocks outside the gateway where the
     *                           external connector's router should target
     * @param role               PRIMARY, SIDE, or REAR; governs connector priority
     */
    public record GatewayInfo(
            Optional<UUID> worldNodeId,
            OutwardDirection outwardDirection,
            BlockPos armEndpoint,
            GatewayRole role
    ) {
        private static final Codec<UUID> UUID_CODEC =
                Codec.STRING.xmap(UUID::fromString, UUID::toString);

        public static final Codec<GatewayInfo> CODEC = RecordCodecBuilder.create(i -> i.group(
                UUID_CODEC.optionalFieldOf("worldNodeId")
                        .forGetter(GatewayInfo::worldNodeId),
                OutwardDirection.CODEC.fieldOf("outwardDirection")
                        .forGetter(GatewayInfo::outwardDirection),
                BlockPos.CODEC.fieldOf("armEndpoint")
                        .forGetter(GatewayInfo::armEndpoint),
                GatewayRole.CODEC.fieldOf("role")
                        .forGetter(GatewayInfo::role)
        ).apply(i, GatewayInfo::new));
    }

    // =========================================================================
    // Outward direction
    // =========================================================================

    public enum OutwardDirection {
        NORTH(Math.PI * 1.5),
        NORTHEAST(Math.PI * 1.75),
        EAST(0.0),
        SOUTHEAST(Math.PI * 0.25),
        SOUTH(Math.PI * 0.5),
        SOUTHWEST(Math.PI * 0.75),
        WEST(Math.PI),
        NORTHWEST(Math.PI * 1.25);

        private final double radians;

        OutwardDirection(double radians) { this.radians = radians; }

        public double toRadians() { return radians; }

        public static OutwardDirection fromAngle(double radians) {
            // Normalize to [0, 2π)
            double r = ((radians % (2 * Math.PI)) + 2 * Math.PI) % (2 * Math.PI);
            // Each of 8 sectors spans π/4 radians
            int sector = (int)(r / (Math.PI / 4.0) + 0.5) % 8;
            return values()[sector];
        }

        public static final Codec<OutwardDirection> CODEC =
                Codec.STRING.xmap(OutwardDirection::valueOf, OutwardDirection::name);
    }

    // =========================================================================
    // Gateway role
    // =========================================================================

    public enum GatewayRole {
        /** Main gateway; one per village. Receives connectors first. */
        PRIMARY,
        /** Side gateway; 0–N per village depending on layout. */
        SIDE,
        /** Back-side gateway; rare, 0–1 per village. */
        REAR;

        public static final Codec<GatewayRole> CODEC =
                Codec.STRING.xmap(GatewayRole::valueOf, GatewayRole::name);
    }

    // =========================================================================
    // Codec
    // =========================================================================

    private static final Codec<UUID> UUID_CODEC =
            Codec.STRING.xmap(UUID::fromString, UUID::toString);

    public static final Codec<VillageRoadNode> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUID_CODEC.fieldOf("nodeId")
                    .forGetter(VillageRoadNode::nodeId),
            BlockPos.CODEC.fieldOf("position")
                    .forGetter(VillageRoadNode::position),
            NodeType.CODEC.fieldOf("type")
                    .forGetter(VillageRoadNode::type),
            GatewayInfo.CODEC.optionalFieldOf("gatewayInfo")
                    .forGetter(VillageRoadNode::gatewayInfo)
    ).apply(i, VillageRoadNode::new));

    // =========================================================================
    // Convenience
    // =========================================================================

    public boolean isGateway() { return type == NodeType.GATEWAY; }

    public static VillageRoadNode interior(BlockPos position) {
        return new VillageRoadNode(UUID.randomUUID(), position,
                NodeType.INTERIOR, Optional.empty());
    }

    public static VillageRoadNode gateway(BlockPos position, GatewayInfo info) {
        return new VillageRoadNode(UUID.randomUUID(), position,
                NodeType.GATEWAY, Optional.of(info));
    }
}
