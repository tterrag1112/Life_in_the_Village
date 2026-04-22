package tterrag1112.life_in_the_village.Village.Roads.Realization;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Economy.Trade.AtlasRouteRouter;
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

        String shortId = edge.getEdgeId().toString().substring(0, 8);
        System.out.println("[EdgeRealizer] Realizing edge " + shortId
                + ", tier=" + edge.getTier()
                + ", cellPath=" + edge.getCellPath().size() + " cells");

        RoadNode nodeA = graph.getNode(edge.getNodeAId());
        RoadNode nodeB = graph.getNode(edge.getNodeBId());
        if (nodeA == null || nodeB == null) {
            System.out.println("[EdgeRealizer] FAILED: node not found in graph"
                    + " (nodeA=" + (nodeA == null ? "MISSING" : "ok")
                    + ", nodeB=" + (nodeB == null ? "MISSING" : "ok") + ")");
            return;
        }

        BlockPos posA = resolveEndpoint(nodeA, nodeB, level, data);
        BlockPos posB = resolveEndpoint(nodeB, nodeA, level, data);

        System.out.println("[EdgeRealizer] Endpoints:"
                + " nodeA=" + edge.getNodeAId().toString().substring(0, 8)
                + "(" + nodeA.type() + ") at " + nodeA.position().toShortString()
                + ", resolved startPos=" + posA.toShortString()
                + "; nodeB=" + edge.getNodeBId().toString().substring(0, 8)
                + "(" + nodeB.type() + ") at " + nodeB.position().toShortString()
                + ", resolved endPos=" + posB.toShortString());

        RoadRouter.RoadQuality quality = roadQualityFor(edge.getTier());
        List<BlockPos> placed = RouteRealiser.realiseBetween(
                level, edge.getCellPath(), posA, posB, quality, data);

        if (placed.isEmpty()) {
            List<Long> cp = edge.getCellPath();
            StringBuilder cellDbg = new StringBuilder("[");
            int limit = Math.min(3, cp.size());
            for (int i = 0; i < limit; i++) {
                if (i > 0) cellDbg.append(", ");
                BlockPos c = AtlasRouteRouter.cellKeyToBlockCenter(cp.get(i));
                cellDbg.append("(").append(c.getX()).append(",").append(c.getZ()).append(")");
            }
            if (cp.size() > limit) cellDbg.append(", +").append(cp.size() - limit).append(" more");
            cellDbg.append("]");
            System.out.println("[EdgeRealizer] FAILED: realiseBetween returned empty."
                    + " cellPath=" + cellDbg + ", quality=" + quality);
            return;
        }

        System.out.println("[EdgeRealizer] Result: " + placed.size() + " blocks placed."
                + " First block=" + placed.get(0).toShortString()
                + ", last block=" + placed.get(placed.size() - 1).toShortString());

        double distFirst = Math.sqrt(placed.get(0).distSqr(posA));
        double distLast  = Math.sqrt(placed.get(placed.size() - 1).distSqr(posB));
        if (distFirst > 6.0) {
            System.out.println("[EdgeRealizer] WARNING: realized block path terminates "
                    + String.format("%.1f", distFirst)
                    + " blocks from expected startPos " + posA.toShortString());
        }
        if (distLast > 6.0) {
            System.out.println("[EdgeRealizer] WARNING: realized block path terminates "
                    + String.format("%.1f", distLast)
                    + " blocks from expected endPos " + posB.toShortString());
        }

        edge.markRealized(placed);
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
