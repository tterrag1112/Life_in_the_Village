package tterrag1112.life_in_the_village.Village.Roads.Proposal;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Networking.VillageRoadsSavedData;
import tterrag1112.life_in_the_village.Networking.WorldRoadSavedData;
import tterrag1112.life_in_the_village.World.Atlas.WorldAtlas;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadEdge;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadNode;
import tterrag1112.life_in_the_village.Village.Roads.Graph.VillageRoadGraph;
import tterrag1112.life_in_the_village.Village.Roads.Graph.VillageRoadNode;
import tterrag1112.life_in_the_village.Village.Roads.Graph.WorldRoadGraph;
import tterrag1112.life_in_the_village.Village.Economy.Trade.AtlasRouteRouter;
import tterrag1112.life_in_the_village.Village.Economy.Trade.TradeRouteManager;
import tterrag1112.life_in_the_village.Village.Roads.Planning.CorridorAttractorBuilder;
import tterrag1112.life_in_the_village.Village.Roads.Docking.VillageDockingPoint;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Track C3.1 — peer of {@link tterrag1112.life_in_the_village.Village.Roads.Planning.ConnectorPlanner}
 * that plans (and optionally commits) a connector edge between two
 * <em>specific</em> villages, rather than the closest existing graph
 * infrastructure.
 *
 * <p>Used by {@link RoadProposalManager}:
 * <ul>
 *   <li>{@link #dryRun} — runs the routing pipeline without mutating
 *       the world graph. Returns the cellPath the connector would take
 *       (or a no-route reason).</li>
 *   <li>{@link #commit} — runs the same pipeline but produces a real
 *       {@link RoadEdge} and adds it to {@link WorldRoadGraph}.</li>
 * </ul>
 *
 * <h3>Design note</h3>
 * Rather than threading a {@code dryRun} boolean through
 * {@code ConnectorPlanner.planConnector} (which already serves the
 * worldgen path, finds candidates by proximity, and has its own
 * isolated-dock fallback semantics), this is a focused two-village
 * peer. It uses the same {@link AtlasRouteRouter}, the same corridor
 * attractor builder, and the same surface-snap + docking helpers, so
 * a player-built connector is graph-indistinguishable from a worldgen
 * one once committed.
 */
public final class RoadProposalRouter {

    private RoadProposalRouter() {}

    /** Dry-run result: either a routed cellPath or a structured reason for failure. */
    public sealed interface RouteResult {

        record Routed(List<Long> cellPath, BlockPos fromAnchor, BlockPos toAnchor)
                implements RouteResult {}

        record NoRoute(String reason) implements RouteResult {}
    }

    private static final int CORRIDOR_PADDING = 4;

    /**
     * Plans a connector from villageA to villageB without committing
     * any graph change. The returned cellPath is the same one a real
     * commit would use (deterministic given identical world state).
     */
    public static RouteResult dryRun(ServerLevel level,
                                      Village villageA, Village villageB) {
        Objects.requireNonNull(villageA, "villageA");
        Objects.requireNonNull(villageB, "villageB");
        if (villageA.getId().equals(villageB.getId())) {
            return new RouteResult.NoRoute("Cannot route a village to itself.");
        }
        BlockPos anchorA = villageA.getAnchorPos();
        BlockPos anchorB = villageB.getAnchorPos();
        if (anchorA == null || anchorB == null) {
            return new RouteResult.NoRoute(
                    "One or both villages have no anchor position.");
        }

        VillageSavedData vdata = VillageSavedData.get(level);
        WorldRoadGraph graph = WorldRoadSavedData.get(level).getGraph();

        // Resolve docking anchors. Prefer gateway TERMINUS positions
        // if present (Track C2 villages); fall back to the legacy
        // VillageDockingPoint computation otherwise.
        DockResolution dockA = resolveDock(level, vdata, graph, villageA, anchorB);
        DockResolution dockB = resolveDock(level, vdata, graph, villageB, anchorA);
        if (dockA == null || dockB == null) {
            return new RouteResult.NoRoute(
                    "Could not resolve a docking anchor for one of the villages.");
        }

        // Reject duplicate-edge proposals: if the world graph already
        // has a TERMINUS / VILLAGE_DOCK pair for both villages and an
        // edge between them, the connector exists already.
        if (alreadyConnected(graph, villageA.getId(), villageB.getId())) {
            return new RouteResult.NoRoute(
                    "A connector between these villages already exists.");
        }

        WorldAtlas atlas = WorldAtlas.get(level);
        TradeRouteManager.prefillAtlasCorridor(level, atlas,
                dockA.dockingAnchor, dockB.dockingAnchor);

        CorridorAttractorBuilder.CorridorAttractors attractors =
                CorridorAttractorBuilder.buildAttractors(
                        graph, dockA.dockingAnchor, dockB.dockingAnchor,
                        CORRIDOR_PADDING);

        Map<Long, Float> discounts = new HashMap<>(attractors.discounts());

        List<Long> cellPath = AtlasRouteRouter.findRoute(
                atlas, dockA.dockingAnchor, dockB.dockingAnchor,
                attractors.cellKeys(), discounts);

        if (cellPath.isEmpty()) {
            return new RouteResult.NoRoute(
                    "No viable path between the two villages within the routing budget.");
        }
        return new RouteResult.Routed(cellPath, dockA.dockingAnchor, dockB.dockingAnchor);
    }

    /**
     * Commits a previously-planned cellPath as a real {@link RoadEdge}
     * connecting both villages' dock nodes.
     *
     * <p>Both endpoints' VILLAGE_DOCK / TERMINUS nodes are looked up
     * (or created via {@code getDockNode}) so the new edge ends at the
     * same nodes a worldgen connector would. The edge is created with
     * tier {@code CONNECTOR}, both villages added as
     * {@code maintainerVillageIds}, and the world graph marked dirty.
     *
     * @return the edge id of the newly created connector
     */
    public static UUID commit(ServerLevel level,
                              Village villageA, Village villageB,
                              List<Long> cellPath,
                              RoadEdge.EdgeTier tier) {
        VillageSavedData vdata = VillageSavedData.get(level);
        WorldRoadGraph graph = WorldRoadSavedData.get(level).getGraph();

        BlockPos firstCell = AtlasRouteRouter.cellKeyToBlockCenter(cellPath.get(0));
        BlockPos lastCell  = AtlasRouteRouter.cellKeyToBlockCenter(cellPath.get(cellPath.size() - 1));

        DockResolution dockA = resolveDock(level, vdata, graph, villageA, lastCell);
        DockResolution dockB = resolveDock(level, vdata, graph, villageB, firstCell);

        UUID nodeIdA = ensureDockNodeId(graph, vdata, villageA, dockA);
        UUID nodeIdB = ensureDockNodeId(graph, vdata, villageB, dockB);

        long meanderSeed = (long) villageA.getId().getMostSignificantBits()
                ^ (long) villageB.getId().getLeastSignificantBits();
        RoadEdge edge = RoadEdge.create(
                nodeIdA, nodeIdB,
                cellPath, tier,
                new RoadEdge.MeanderProfile(3.0f, 0.1f, meanderSeed));
        edge.setMaintenance(100);
        edge.getMaintainerVillageIds().add(villageA.getId());
        edge.getMaintainerVillageIds().add(villageB.getId());
        graph.addEdge(edge);

        WorldRoadSavedData.get(level).markDirty();
        return edge.getEdgeId();
    }

    // =========================================================================
    // Internals
    // =========================================================================

    private record DockResolution(BlockPos dockingAnchor,
                                   /* null if a fresh node will be created */
                                   UUID existingNodeId) {}

    /**
     * Resolves a docking anchor for {@code village} pointing toward
     * {@code targetPos}. Preferred order:
     * <ol>
     *   <li>The gateway TERMINUS (Track C2) closest to {@code targetPos},
     *       reusing its position + node id.</li>
     *   <li>The current {@code village.dockNodeId} TERMINUS / VILLAGE_DOCK
     *       on the world graph.</li>
     *   <li>A fresh {@link VillageDockingPoint#compute} fallback.</li>
     * </ol>
     */
    private static DockResolution resolveDock(ServerLevel level,
                                                VillageSavedData vdata,
                                                WorldRoadGraph graph,
                                                Village village,
                                                BlockPos targetPos) {
        // Track C2 — try gateway terminus first
        VillageRoadGraph vGraph = VillageRoadsSavedData.get(level)
                .getOrCreate(village.getId());
        VillageRoadNode bestGw = null;
        long bestDistSq = Long.MAX_VALUE;
        for (VillageRoadNode g : vGraph.gateways()) {
            BlockPos arm = g.gatewayInfo().map(VillageRoadNode.GatewayInfo::armEndpoint)
                    .orElse(g.position());
            long dx = arm.getX() - targetPos.getX();
            long dz = arm.getZ() - targetPos.getZ();
            long dSq = dx * dx + dz * dz;
            if (dSq < bestDistSq) { bestDistSq = dSq; bestGw = g; }
        }
        if (bestGw != null) {
            BlockPos arm = bestGw.gatewayInfo().map(VillageRoadNode.GatewayInfo::armEndpoint)
                    .orElse(bestGw.position());
            UUID worldNodeId = bestGw.gatewayInfo()
                    .flatMap(VillageRoadNode.GatewayInfo::worldNodeId)
                    .orElse(null);
            return new DockResolution(arm, worldNodeId);
        }

        // Pre-C2: use the existing dock node if available
        UUID dockId = village.getDockNodeId().orElse(null);
        if (dockId != null) {
            RoadNode dockNode = graph.getNode(dockId);
            if (dockNode != null) {
                return new DockResolution(dockNode.position(), dockId);
            }
        }

        // Final fallback — VillageDockingPoint.compute
        VillageDockingPoint dock = VillageDockingPoint.compute(village, targetPos, level, vdata);
        return new DockResolution(dock.dockingAnchor(), null);
    }

    /**
     * Ensures the village has a node in the world graph at the docking
     * anchor and returns its UUID. If a gateway terminus already
     * matches, returns that id; otherwise creates a fresh
     * {@code VILLAGE_DOCK} node and stamps {@code village.dockNodeId}.
     */
    private static UUID ensureDockNodeId(WorldRoadGraph graph,
                                          VillageSavedData vdata,
                                          Village village,
                                          DockResolution dock) {
        if (dock.existingNodeId != null) {
            RoadNode existing = graph.getNode(dock.existingNodeId);
            if (existing != null) return existing.nodeId();
        }
        // Walk all nodes for an exact-position TERMINUS already gateway-linked
        // to this village (defends against missing existingNodeId).
        for (RoadNode candidate : graph.allNodes()) {
            if (candidate.type() != RoadNode.NodeType.TERMINUS) continue;
            if (!candidate.position().equals(dock.dockingAnchor)) continue;
            Optional<RoadNode.GatewayLink> link = candidate.gatewayLink();
            if (link.isPresent() && link.get().villageId().equals(village.getId())) {
                village.setDockNodeId(candidate.nodeId());
                vdata.markDirty();
                return candidate.nodeId();
            }
        }
        RoadNode dockNode = new RoadNode(UUID.randomUUID(), dock.dockingAnchor,
                RoadNode.NodeType.VILLAGE_DOCK, Optional.empty());
        graph.addNode(dockNode);
        village.setDockNodeId(dockNode.nodeId());
        vdata.setDirty();
        return dockNode.nodeId();
    }

    /**
     * Returns true if the world graph already contains an edge whose two
     * endpoints are both linked (via {@code GatewayLink} or
     * {@code VILLAGE_DOCK} type and a stored {@code dockNodeId}) to
     * villages {@code a} and {@code b} respectively.
     */
    private static boolean alreadyConnected(WorldRoadGraph graph, UUID a, UUID b) {
        for (RoadEdge edge : graph.allEdges()) {
            UUID nodeA = edge.getNodeAId();
            UUID nodeB = edge.getNodeBId();
            UUID viaA  = villageOf(graph.getNode(nodeA));
            UUID viaB  = villageOf(graph.getNode(nodeB));
            if (viaA == null || viaB == null) continue;
            if ((viaA.equals(a) && viaB.equals(b))
                    || (viaA.equals(b) && viaB.equals(a))) {
                return true;
            }
        }
        return false;
    }

    private static UUID villageOf(RoadNode node) {
        if (node == null) return null;
        return node.gatewayLink().map(RoadNode.GatewayLink::villageId).orElse(null);
    }
}
