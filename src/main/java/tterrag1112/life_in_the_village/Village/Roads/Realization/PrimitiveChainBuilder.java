package tterrag1112.life_in_the_village.Village.Roads.Realization;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.RoadShape;
import tterrag1112.life_in_the_village.Village.Economy.Trade.AtlasRouteRouter;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.RoadPrimitive;
import tterrag1112.life_in_the_village.Village.Roads.Docking.VillageDockingPoint;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadEdge;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadNode;
import tterrag1112.life_in_the_village.Village.Roads.Graph.WorldRoadGraph;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Builds the {@link RoadPrimitive} chain for a {@link RoadEdge}.
 *
 * <h3>Chain structure</h3>
 * <ol>
 *   <li>Optional {@link RoadPrimitive.ArmApproach} for node A if it is a
 *       {@link RoadNode.NodeType#VILLAGE_DOCK}.</li>
 *   <li>{@link RoadPrimitive.SmoothedPath} spanning the full cell path between
 *       the two resolved outer endpoints (docking anchors for VILLAGE_DOCK nodes,
 *       node positions for all other types).</li>
 *   <li>Optional {@link RoadPrimitive.ArmApproach} for node B if it is a
 *       {@link RoadNode.NodeType#VILLAGE_DOCK}.</li>
 * </ol>
 *
 * <h3>Endpoint resolution</h3>
 * VILLAGE_DOCK nodes produce a {@link VillageDockingPoint}. The outer boundary
 * for the SmoothedPath is the {@code dockingAnchor}; the ArmApproach covers
 * the gap from {@code dockingAnchor} to {@code armEndpoint} using the village's
 * own path material during placement.
 */
public final class PrimitiveChainBuilder {

    private PrimitiveChainBuilder() {}

    /**
     * Builds a primitive chain for the given edge.
     *
     * @return the chain, or an empty list if the edge cannot be processed
     *         (missing nodes, empty cell path)
     */
    public static List<RoadPrimitive> buildPrimitivesForEdge(
            ServerLevel level,
            WorldRoadGraph graph,
            VillageSavedData data,
            RoadEdge edge) {

        if (edge.getCellPath().isEmpty()) return List.of();

        RoadNode nodeA = graph.getNode(edge.getNodeAId());
        RoadNode nodeB = graph.getNode(edge.getNodeBId());
        if (nodeA == null || nodeB == null) return List.of();

        RoadShape.RoadTier roadTier = edgeTierToRoadTier(edge.getTier());

        // Resolve docking geometry for each VILLAGE_DOCK endpoint
        Optional<VillageDockingPoint> dockA = resolveDock(nodeA, nodeB, level, data);
        Optional<VillageDockingPoint> dockB = resolveDock(nodeB, nodeA, level, data);

        BlockPos outerA = dockA.map(VillageDockingPoint::dockingAnchor).orElse(nodeA.position());
        BlockPos outerB = dockB.map(VillageDockingPoint::dockingAnchor).orElse(nodeB.position());

        // Build SmoothedPath waypoints: outerA → cell centres → outerB
        List<BlockPos> waypoints = buildWaypoints(edge, outerA, outerB);

        // Character-driven drift for great roads; zero drift for all other tiers
        double driftAmp = 0.0;
        long primSeed   = 0L;
        if (edge.getTier() == RoadEdge.EdgeTier.GREAT_ROAD) {
            RoadEdge.MeanderProfile mp = edge.getMeanderProfile();
            driftAmp = mp.amplitude();
            primSeed = mp.seed();
        }

        List<RoadPrimitive> chain = new ArrayList<>();

        dockA.ifPresent(dock -> chain.add(new RoadPrimitive.ArmApproach(
                dock.dockingAnchor(), dock.armEndpoint(), dock.villageId(), roadTier)));

        chain.add(new RoadPrimitive.SmoothedPath(waypoints, 0.5f, driftAmp, roadTier, primSeed));

        dockB.ifPresent(dock -> chain.add(new RoadPrimitive.ArmApproach(
                dock.dockingAnchor(), dock.armEndpoint(), dock.villageId(), roadTier)));

        return chain;
    }

    // =========================================================================
    // Waypoint assembly
    // =========================================================================

    private static List<BlockPos> buildWaypoints(RoadEdge edge,
                                                  BlockPos outerA,
                                                  BlockPos outerB) {
        List<Long> cellPath = edge.getCellPath();
        List<BlockPos> waypoints = new ArrayList<>(cellPath.size() + 2);
        waypoints.add(outerA);
        for (long cellKey : cellPath) {
            waypoints.add(AtlasRouteRouter.cellKeyToBlockCenter(cellKey));
        }
        waypoints.add(outerB);
        return waypoints;
    }

    // =========================================================================
    // Endpoint resolution
    // =========================================================================

    private static Optional<VillageDockingPoint> resolveDock(RoadNode node,
                                                              RoadNode other,
                                                              ServerLevel level,
                                                              VillageSavedData data) {
        if (node.type() != RoadNode.NodeType.VILLAGE_DOCK) return Optional.empty();
        Village village = data.getAllVillages().stream()
                .filter(v -> v.getDockNodeId().filter(node.nodeId()::equals).isPresent())
                .findFirst()
                .orElse(null);
        if (village == null) return Optional.empty();
        return Optional.of(VillageDockingPoint.compute(village, other.position(), level, data));
    }

    // =========================================================================
    // Tier mapping
    // =========================================================================

    static RoadShape.RoadTier edgeTierToRoadTier(RoadEdge.EdgeTier tier) {
        return switch (tier) {
            case GREAT_ROAD -> RoadShape.RoadTier.CAPITAL_ROAD;
            case TRUNK      -> RoadShape.RoadTier.TOWN_ROAD;
            case CONNECTOR  -> RoadShape.RoadTier.VILLAGE_ROAD;
            case LOCAL      -> RoadShape.RoadTier.VILLAGE_PATH;
            // Track C3.3 — SEA edges never reach the road-shape
            // pipeline (EdgeRealizer skips them), but the enum
            // requires exhaustive coverage. FOOTPATH is a harmless
            // sentinel.
            case SEA        -> RoadShape.RoadTier.FOOTPATH;
        };
    }
}
