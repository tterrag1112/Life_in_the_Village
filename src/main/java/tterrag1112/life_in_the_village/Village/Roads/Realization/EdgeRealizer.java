package tterrag1112.life_in_the_village.Village.Roads.Realization;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.OrganicRoadPlacer;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.PathMaterial;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.RoadShape;
import tterrag1112.life_in_the_village.Village.Decoration.VillageBiomeStyle;
import tterrag1112.life_in_the_village.Village.Economy.Trade.AtlasRouteRouter;
import tterrag1112.life_in_the_village.Village.Economy.Trade.RoadRouter;
import tterrag1112.life_in_the_village.Village.Economy.Trade.RouteRealiser;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.RoadPrimitive;
import tterrag1112.life_in_the_village.Village.Roads.Decoration.ConnectorAllee;
import tterrag1112.life_in_the_village.Village.Roads.Docking.VillageDockingPoint;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadEdge;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadNode;
import tterrag1112.life_in_the_village.Village.Roads.Graph.WorldRoadGraph;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Converts a graph {@link RoadEdge}'s cell path into placed blocks.
 *
 * <p>Endpoint resolution is type-aware: {@link RoadNode.NodeType#VILLAGE_DOCK}
 * nodes look up the owning village and call {@link VillageDockingPoint#compute}
 * to get the docking anchor (30 blocks outside the gate). Every other node
 * type uses the node's stored position directly.
 *
 * <h3>Approach segments (Phase 2 / Fix 2)</h3>
 * After the main trade-road segment is placed (anchor-to-anchor via
 * {@link RouteRealiser#realiseBetween}), each {@code VILLAGE_DOCK} endpoint
 * receives a short approach segment placed with the village's own material
 * and a roadside allée via {@link ConnectorAllee}. This mirrors what
 * {@code RouteRealisationSystem.placeApproach} does for legacy TradeRoads.
 *
 * <p>This logic cannot live in {@code realiseBetween} because that method
 * receives only positions, not node types. {@code EdgeRealizer} is the
 * correct layer — it already has access to both nodes and their types.
 *
 * <p>On success the edge is marked realized via {@link RoadEdge#markRealized}.
 * Callers are responsible for calling {@code WorldRoadSavedData.get(level).markDirty()}
 * after a successful realization.
 */
public final class EdgeRealizer {

    /** Drift amplitude for approach segments — low to keep them visually straight. */
    private static final double APPROACH_DRIFT = 2.0;

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

        // ── Phase 2 approach segments for VILLAGE_DOCK endpoints ─────────────
        // placed goes posA→posB. For a typical CONNECTOR edge:
        //   nodeA = VILLAGE_DOCK  → posA = dockingAnchor  → placed[0]  ≈ dockingAnchor
        //   nodeB = TRUNK_JUNCTION → posB = junction pos  → placed[N]  ≈ junctionPos
        //
        // We prepend [armEndpoint … dockingAnchor] before placed (reversed approach),
        // and append [dockingAnchor … armEndpoint] after placed for nodeB if it is also
        // a VILLAGE_DOCK. This keeps the blockPath direction: armA→…→junctionPos→…→armB.
        List<BlockPos> approachA = null;
        List<BlockPos> approachB = null;

        if (nodeA.type() == RoadNode.NodeType.VILLAGE_DOCK) {
            approachA = placeApproach(level, nodeA, nodeB.position(), data);
        }
        if (nodeB.type() == RoadNode.NodeType.VILLAGE_DOCK) {
            approachB = placeApproach(level, nodeB, nodeA.position(), data);
        }

        // Assemble full block path
        List<BlockPos> fullPath = new ArrayList<>(placed);

        if (approachA != null && approachA.size() > 1) {
            // approachA is ordered dockingAnchor→armEndpoint; reverse → armEndpoint→dockingAnchor.
            // Drop the last element of reversed (= dockingAnchor ≈ fullPath[0]) to avoid duplicate.
            List<BlockPos> rA = new ArrayList<>(approachA);
            Collections.reverse(rA);
            List<BlockPos> prepend = rA.subList(0, rA.size() - 1);
            List<BlockPos> combined = new ArrayList<>(prepend);
            combined.addAll(fullPath);
            fullPath = combined;
        }

        if (approachB != null && approachB.size() > 1) {
            // approachB[0] = dockingAnchor ≈ fullPath[last]; skip it to avoid duplicate.
            fullPath.addAll(approachB.subList(1, approachB.size()));
        }

        edge.markRealized(fullPath);
    }

    // =========================================================================
    // Approach segment placement
    // =========================================================================

    /**
     * Places the approach segment for a VILLAGE_DOCK node and returns its
     * centerline (ordered dockingAnchor → armEndpoint).
     *
     * <p>Mirrors {@code RouteRealisationSystem.placeApproach} for graph edges.
     * Returns an empty list when the owning village cannot be found or the
     * centerline is degenerate.
     */
    private static List<BlockPos> placeApproach(
            ServerLevel level,
            RoadNode dockNode,
            BlockPos otherSidePos,
            VillageSavedData data) {

        Village village = data.getAllVillages().stream()
                .filter(v -> v.getDockNodeId()
                        .filter(dockNode.nodeId()::equals).isPresent())
                .findFirst()
                .orElse(null);

        if (village == null) {
            System.out.println("[EdgeRealizer] WARNING: no village found for VILLAGE_DOCK "
                    + dockNode.nodeId().toString().substring(0, 8)
                    + "; skipping approach segment.");
            return List.of();
        }

        VillageDockingPoint dock = VillageDockingPoint.compute(
                village, otherSidePos, level, data);

        RoadPrimitive.StraightRoad primitive = new RoadPrimitive.StraightRoad(
                dock.dockingAnchor(), dock.armEndpoint(),
                APPROACH_DRIFT,
                RoadShape.RoadTier.VILLAGE_ROAD);
        List<BlockPos> centerline = primitive.computeCenterline(level, level.getSeed());

        if (centerline.size() < 2) return List.of();

        // Clear trees first (same pass as the trade-road pipeline)
        for (BlockPos p : centerline) {
            RoadRouter.clearTreesAt(level, p.getX(), p.getZ(), p.getY());
        }

        VillageBiomeStyle biome    = VillageBiomeStyle.detect(level, dock.armEndpoint());
        PathMaterial      material = PathMaterial.forBiomeAndTier(biome, village.getPathTier());
        RandomSource rng = RandomSource.create(
                dock.armEndpoint().asLong() ^ dock.dockingAnchor().asLong());

        OrganicRoadPlacer.place(level, centerline, material,
                RoadShape.RoadTier.VILLAGE_ROAD, null, rng);

        ConnectorAllee.place(level, dock, centerline, village.getVillageType());

        System.out.println("[EdgeRealizer] Placed approach for village '"
                + village.getName() + "': " + centerline.size() + " blocks"
                + ", anchor=" + dock.dockingAnchor().toShortString()
                + " → arm=" + dock.armEndpoint().toShortString());

        return centerline;
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
