package tterrag1112.life_in_the_village.Village.Economy.Trade;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Networking.VillageRoadsSavedData;
import tterrag1112.life_in_the_village.Networking.WorldRoadSavedData;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadEdge;
import tterrag1112.life_in_the_village.Village.Roads.Graph.VillageRoadGraph;

import java.util.List;
import java.util.UUID;

/**
 * One leg of a composite trade-route path. A route is a list of segments
 * that are concatenated to produce the full caravan block path.
 *
 * <h3>Segment types</h3>
 * <ul>
 *   <li>{@link WorldEdge} — a single edge in the {@link tterrag1112.life_in_the_village.Village.Roads.Graph.WorldRoadGraph}.</li>
 *   <li>{@link VillageTraversal} — a through-village crossing from one gateway to another,
 *       following the village's internal road graph.</li>
 * </ul>
 *
 * <h3>Serialization</h3>
 * The codec uses a {@code "type"} string discriminator field
 * ({@code "world_edge"} or {@code "village_traversal"}).
 */
public sealed interface RouteSegment
        permits RouteSegment.WorldEdge, RouteSegment.VillageTraversal {

    // =========================================================================
    // Codec
    // =========================================================================

    Codec<RouteSegment> CODEC = Codec.STRING.dispatch(
            "type",
            seg -> seg instanceof WorldEdge ? "world_edge" : "village_traversal",
            type -> switch (type) {
                case "world_edge"         -> WorldEdge.MAP_CODEC.codec();
                case "village_traversal"  -> VillageTraversal.MAP_CODEC.codec();
                default -> throw new IllegalArgumentException("unknown RouteSegment type: " + type);
            }
    );

    // =========================================================================
    // Behavior
    // =========================================================================

    /**
     * Resolves this segment to an ordered list of block positions.
     * Returns an empty list if the segment is unrealized or invalid.
     */
    List<BlockPos> resolveBlocks(ServerLevel level);

    // =========================================================================
    // WorldEdge
    // =========================================================================

    /**
     * A traversal of one {@link RoadEdge} in the world road graph.
     *
     * @param edgeId     the world graph edge UUID
     * @param startNodeId the node at the origin end of this edge (used for orientation)
     */
    record WorldEdge(UUID edgeId, UUID startNodeId) implements RouteSegment {

        private static final Codec<UUID> UUID_CODEC =
                Codec.STRING.xmap(UUID::fromString, UUID::toString);

        static final MapCodec<WorldEdge> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                UUID_CODEC.fieldOf("edgeId").forGetter(WorldEdge::edgeId),
                UUID_CODEC.fieldOf("startNodeId").forGetter(WorldEdge::startNodeId)
        ).apply(i, WorldEdge::new));

        @Override
        public List<BlockPos> resolveBlocks(ServerLevel level) {
            RoadEdge edge = WorldRoadSavedData.get(level).getGraph().getEdge(edgeId);
            if (edge == null || !edge.isRealized() || edge.getBlockPath().isEmpty())
                return List.of();
            boolean forward = edge.getNodeAId().equals(startNodeId);
            if (forward) return edge.getBlockPath();
            List<BlockPos> rev = new java.util.ArrayList<>(edge.getBlockPath());
            java.util.Collections.reverse(rev);
            return rev;
        }
    }

    // =========================================================================
    // VillageTraversal
    // =========================================================================

    /**
     * A through-village crossing from one gateway to another, following the
     * village's internal {@link VillageRoadGraph}.
     *
     * @param villageId       the village being traversed
     * @param entryGatewayId  UUID of the entry GATEWAY node
     * @param exitGatewayId   UUID of the exit GATEWAY node
     */
    record VillageTraversal(UUID villageId,
                            UUID entryGatewayId,
                            UUID exitGatewayId) implements RouteSegment {

        private static final Codec<UUID> UUID_CODEC =
                Codec.STRING.xmap(UUID::fromString, UUID::toString);

        static final MapCodec<VillageTraversal> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                UUID_CODEC.fieldOf("villageId").forGetter(VillageTraversal::villageId),
                UUID_CODEC.fieldOf("entryGatewayId").forGetter(VillageTraversal::entryGatewayId),
                UUID_CODEC.fieldOf("exitGatewayId").forGetter(VillageTraversal::exitGatewayId)
        ).apply(i, VillageTraversal::new));

        @Override
        public List<BlockPos> resolveBlocks(ServerLevel level) {
            VillageRoadGraph graph =
                    VillageRoadsSavedData.get(level).getOrCreate(villageId);
            return graph.findGatewayBlockPath(entryGatewayId, exitGatewayId);
        }
    }
}
