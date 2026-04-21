package tterrag1112.life_in_the_village.Village.Roads.Realization;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Economy.Trade.RoadRouter;
import tterrag1112.life_in_the_village.Village.Economy.Trade.RouteRealiser;
import tterrag1112.life_in_the_village.Village.Roads.Docking.VillageDockingPoint;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadEdge;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadNode;
import tterrag1112.life_in_the_village.Village.Roads.Graph.WorldRoadGraph;

import java.util.List;

/**
 * Converts a graph {@link RoadEdge}'s cell path into placed blocks.
 *
 * <p>Endpoint resolution is type-aware: {@link RoadNode.NodeType#VILLAGE_DOCK}
 * nodes look up the owning village and call {@link VillageDockingPoint#compute}
 * to get the docking anchor (30 blocks outside the gate). Every other node
 * type uses the node's stored position directly.
 *
 * <p>On success the edge is marked realized via {@link RoadEdge#markRealized}.
 * Callers are responsible for calling {@code WorldRoadSavedData.get(level).markDirty()}
 * after a successful realization.
 */
public final class EdgeRealizer {

    private EdgeRealizer() {}

    public static void realizeEdge(ServerLevel level,
                                   RoadEdge edge,
                                   WorldRoadGraph graph,
                                   VillageSavedData data) {
        if (edge.isRealized()) return;
        if (edge.getCellPath().isEmpty()) return;

        RoadNode nodeA = graph.getNode(edge.getNodeAId());
        RoadNode nodeB = graph.getNode(edge.getNodeBId());
        if (nodeA == null || nodeB == null) return;

        BlockPos posA = resolveEndpoint(nodeA, nodeB, level, data);
        BlockPos posB = resolveEndpoint(nodeB, nodeA, level, data);

        List<BlockPos> placed = RouteRealiser.realiseBetween(
                level, edge.getCellPath(), posA, posB,
                roadQualityFor(edge.getTier()), data);

        if (!placed.isEmpty()) {
            edge.markRealized(placed);
        }
    }

    // =========================================================================
    // Endpoint resolution
    // =========================================================================

    private static BlockPos resolveEndpoint(RoadNode node,
                                             RoadNode otherNode,
                                             ServerLevel level,
                                             VillageSavedData data) {
        if (node.type() == RoadNode.NodeType.VILLAGE_DOCK) {
            // Find the village whose dockNodeId matches this node
            return data.getAllVillages().stream()
                    .filter(v -> v.getDockNodeId()
                            .filter(node.nodeId()::equals).isPresent())
                    .findFirst()
                    .map(v -> VillageDockingPoint.compute(
                            v, otherNode.position(), level, data).dockingAnchor())
                    .orElse(node.position());
        }
        return node.position();
    }

    // =========================================================================
    // Tier → quality mapping
    // =========================================================================

    private static RoadRouter.RoadQuality roadQualityFor(RoadEdge.EdgeTier tier) {
        return RoadRouter.RoadQuality.COBBLESTONE;
    }
}
