package tterrag1112.life_in_the_village.Village.Economy.Trade;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Networking.VillageRoadsSavedData;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadEdge;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadNode;
import tterrag1112.life_in_the_village.Village.Roads.Graph.VillageRoadGraph;
import tterrag1112.life_in_the_village.Village.Roads.Graph.VillageRoadNode;
import tterrag1112.life_in_the_village.Village.Roads.Graph.WorldRoadGraph;

import java.util.*;

/**
 * Finds shortest-path routes through the {@link WorldRoadGraph} using
 * Dijkstra and provides the shared helper for converting edge-ID lists
 * to ordered block positions.
 *
 * <h3>Edge weights</h3>
 * GREAT_ROAD×0.6 · TRUNK×0.8 · CONNECTOR×1.0 · LOCAL×1.5, each
 * multiplied by the edge's cell-path length so longer edges cost more.
 *
 * <h3>resolveGraphBlocks</h3>
 * Shared by {@link Caravan#getPath} and
 * {@link tterrag1112.life_in_the_village.Entities.Goals.Profession.Merchant.CaravanMerchantGoal}
 * so both use exactly the same block sequence.
 */
public final class GraphTradeRouteEstablisher {

    private GraphTradeRouteEstablisher() {}

    // =========================================================================
    // Dijkstra
    // =========================================================================

    private record EdgeConnection(UUID edgeId, UUID neighborId, double weight) {}

    /**
     * Returns an ordered list of edge IDs forming the lowest-cost path from
     * {@code fromNodeId} to {@code toNodeId} through the graph, or empty if
     * no path exists.
     */
    public static Optional<List<UUID>> findEdgePath(WorldRoadGraph graph,
                                                     UUID fromNodeId,
                                                     UUID toNodeId) {
        if (fromNodeId.equals(toNodeId)) return Optional.of(List.of());

        // Build adjacency list from edges. Track C3.2: skip edges that
        // touch a POI_STUB endpoint — caravans don't visit dungeons.
        // The same filter applies to findSegmentedPath because it shares
        // this adjacency layer below.
        Map<UUID, List<EdgeConnection>> adj = new HashMap<>();
        for (RoadEdge edge : graph.allEdges()) {
            if (touchesPoiStub(graph, edge)) continue;
            double w = edgeWeight(edge);
            adj.computeIfAbsent(edge.getNodeAId(), k -> new ArrayList<>())
                    .add(new EdgeConnection(edge.getEdgeId(), edge.getNodeBId(), w));
            adj.computeIfAbsent(edge.getNodeBId(), k -> new ArrayList<>())
                    .add(new EdgeConnection(edge.getEdgeId(), edge.getNodeAId(), w));
        }

        Map<UUID, Double> dist    = new HashMap<>();
        Map<UUID, UUID>   prevNode = new HashMap<>();
        Map<UUID, UUID>   prevEdge = new HashMap<>();

        dist.put(fromNodeId, 0.0);
        PriorityQueue<UUID> queue = new PriorityQueue<>(
                Comparator.comparingDouble(n -> dist.getOrDefault(n, Double.MAX_VALUE)));
        queue.add(fromNodeId);

        while (!queue.isEmpty()) {
            UUID u = queue.poll();
            if (u.equals(toNodeId)) break;
            double uDist = dist.getOrDefault(u, Double.MAX_VALUE);

            for (EdgeConnection ec : adj.getOrDefault(u, List.of())) {
                double newDist = uDist + ec.weight();
                if (newDist < dist.getOrDefault(ec.neighborId(), Double.MAX_VALUE)) {
                    dist.put(ec.neighborId(), newDist);
                    prevNode.put(ec.neighborId(), u);
                    prevEdge.put(ec.neighborId(), ec.edgeId());
                    queue.remove(ec.neighborId());
                    queue.add(ec.neighborId());
                }
            }
        }

        if (!prevEdge.containsKey(toNodeId)) return Optional.empty();

        // Reconstruct edge path (prepend to get A→B order)
        List<UUID> edgeIds = new ArrayList<>();
        UUID cur = toNodeId;
        while (prevEdge.containsKey(cur)) {
            edgeIds.add(0, prevEdge.get(cur));
            cur = prevNode.get(cur);
        }
        return Optional.of(edgeIds);
    }

    private static double edgeWeight(RoadEdge edge) {
        double tierFactor = switch (edge.getTier()) {
            case GREAT_ROAD -> 0.6;
            case TRUNK      -> 0.8;
            case CONNECTOR  -> 1.0;
            case LOCAL      -> 1.5;
        };
        return tierFactor * Math.max(1, edge.getCellPath().size());
    }

    /**
     * Track C3.2 — true if either endpoint of {@code edge} is a
     * {@link tterrag1112.life_in_the_village.Village.Roads.Graph.RoadNode.NodeType#POI_STUB}
     * node. POI subroads are excluded from caravan routing because
     * caravans don't visit dungeons / shrines. The check is
     * defensive against missing nodes (returns false if either
     * endpoint can't be resolved — those edges are already broken
     * and the validator handles them).
     */
    private static boolean touchesPoiStub(WorldRoadGraph graph, RoadEdge edge) {
        var a = graph.getNode(edge.getNodeAId());
        var b = graph.getNode(edge.getNodeBId());
        return (a != null && a.type()
                == tterrag1112.life_in_the_village.Village.Roads.Graph.RoadNode.NodeType.POI_STUB)
                || (b != null && b.type()
                == tterrag1112.life_in_the_village.Village.Roads.Graph.RoadNode.NodeType.POI_STUB);
    }

    // =========================================================================
    // Track C2 — segmented Dijkstra (through-village traversal)
    // =========================================================================

    /**
     * Like {@link #findEdgePath} but augments Dijkstra with virtual hops
     * between TERMINUS nodes that belong to the same village's gateway set.
     * The result is a list of {@link RouteSegment}s mixing
     * {@link RouteSegment.WorldEdge} (real edge traversals) with
     * {@link RouteSegment.VillageTraversal} (entries through one gateway,
     * exits from another).
     *
     * <p>Returns empty if no path exists. Falls back gracefully to a
     * single-WorldEdge sequence when no village is on the path — callers
     * can use the resulting segment list verbatim either way.
     *
     * <h3>Cost model for village hops</h3>
     * The hop cost is the BFS-distance between gateways within the
     * village's internal graph (sum of edge {@code length()} values).
     * If the internal graph has no path, the hop is rejected. If the
     * village graph is missing or empty, the hop costs zero — equivalent
     * to a teleport, only used as a defensive fallback so single-gateway
     * villages still produce valid paths.
     */
    public static Optional<List<RouteSegment>> findSegmentedPath(
            WorldRoadGraph graph,
            VillageRoadsSavedData villageRoads,
            UUID fromNodeId,
            UUID toNodeId) {

        if (fromNodeId.equals(toNodeId)) return Optional.of(List.of());

        // Gateway-to-village lookup: which village (if any) is this
        // TERMINUS node a gateway of, and what's the village's graph?
        Map<UUID, UUID> nodeToVillage = new HashMap<>();
        for (RoadNode n : graph.allNodes()) {
            if (n.type() != RoadNode.NodeType.TERMINUS) continue;
            n.gatewayLink().ifPresent(link ->
                    nodeToVillage.put(n.nodeId(), link.villageId()));
        }

        // Adjacency from real edges, like findEdgePath. Track C3.2: skip
        // edges with POI_STUB endpoints so caravans don't route via
        // POI subroads.
        Map<UUID, List<EdgeConnection>> adj = new HashMap<>();
        for (RoadEdge edge : graph.allEdges()) {
            if (touchesPoiStub(graph, edge)) continue;
            double w = edgeWeight(edge);
            adj.computeIfAbsent(edge.getNodeAId(), k -> new ArrayList<>())
                    .add(new EdgeConnection(edge.getEdgeId(), edge.getNodeBId(), w));
            adj.computeIfAbsent(edge.getNodeBId(), k -> new ArrayList<>())
                    .add(new EdgeConnection(edge.getEdgeId(), edge.getNodeAId(), w));
        }

        Map<UUID, Double> dist     = new HashMap<>();
        Map<UUID, UUID>   prevNode = new HashMap<>();
        // For each visited node, what was the *kind* of step taken to reach it.
        Map<UUID, StepKind> stepKind = new HashMap<>();
        Map<UUID, UUID>   prevEdge  = new HashMap<>(); // for WORLD_EDGE steps
        Map<UUID, UUID>   prevVillage = new HashMap<>(); // for VILLAGE_HOP steps

        dist.put(fromNodeId, 0.0);
        PriorityQueue<UUID> queue = new PriorityQueue<>(
                Comparator.comparingDouble(n -> dist.getOrDefault(n, Double.MAX_VALUE)));
        queue.add(fromNodeId);

        while (!queue.isEmpty()) {
            UUID u = queue.poll();
            if (u.equals(toNodeId)) break;
            double uDist = dist.getOrDefault(u, Double.MAX_VALUE);

            // World-edge expansions
            for (EdgeConnection ec : adj.getOrDefault(u, List.of())) {
                double newDist = uDist + ec.weight();
                if (newDist < dist.getOrDefault(ec.neighborId(), Double.MAX_VALUE)) {
                    dist.put(ec.neighborId(), newDist);
                    prevNode.put(ec.neighborId(), u);
                    stepKind.put(ec.neighborId(), StepKind.WORLD_EDGE);
                    prevEdge.put(ec.neighborId(), ec.edgeId());
                    prevVillage.remove(ec.neighborId());
                    queue.remove(ec.neighborId());
                    queue.add(ec.neighborId());
                }
            }

            // Village-hop expansions: if u is a gateway TERMINUS, every
            // other gateway TERMINUS of the same village is reachable at
            // BFS-distance cost through the internal village graph.
            UUID villageId = nodeToVillage.get(u);
            if (villageId == null) continue;
            // Don't re-enter the village we came in through if our most
            // recent step was already a village hop on this same village
            // (prevents A→B→A ping-pong inside Dijkstra).
            if (stepKind.get(u) == StepKind.VILLAGE_HOP
                    && villageId.equals(prevVillage.get(u))) {
                continue;
            }

            VillageRoadGraph vGraph = villageRoads.getGraph(villageId).orElse(null);
            if (vGraph == null) continue;

            VillageRoadNode entryGw = findGatewayByWorldNodeId(vGraph, u);
            if (entryGw == null) continue;

            for (RoadNode otherDock : graph.allNodes()) {
                if (otherDock.type() != RoadNode.NodeType.TERMINUS) continue;
                if (otherDock.nodeId().equals(u)) continue;
                Optional<RoadNode.GatewayLink> link = otherDock.gatewayLink();
                if (link.isEmpty()) continue;
                if (!link.get().villageId().equals(villageId)) continue;

                VillageRoadNode exitGw = findGatewayByWorldNodeId(vGraph, otherDock.nodeId());
                if (exitGw == null) continue;

                double hopCost = villageHopCost(vGraph, entryGw.nodeId(), exitGw.nodeId());
                if (hopCost < 0) continue; // unreachable inside village

                double newDist = uDist + hopCost;
                if (newDist < dist.getOrDefault(otherDock.nodeId(), Double.MAX_VALUE)) {
                    dist.put(otherDock.nodeId(), newDist);
                    prevNode.put(otherDock.nodeId(), u);
                    stepKind.put(otherDock.nodeId(), StepKind.VILLAGE_HOP);
                    prevEdge.remove(otherDock.nodeId());
                    prevVillage.put(otherDock.nodeId(), villageId);
                    queue.remove(otherDock.nodeId());
                    queue.add(otherDock.nodeId());
                }
            }
        }

        if (!stepKind.containsKey(toNodeId)) return Optional.empty();

        // Reconstruct segments in reverse, then flip.
        List<RouteSegment> segments = new ArrayList<>();
        UUID cur = toNodeId;
        while (stepKind.containsKey(cur)) {
            UUID prev = prevNode.get(cur);
            switch (stepKind.get(cur)) {
                case WORLD_EDGE -> segments.add(0, new RouteSegment.WorldEdge(
                        prevEdge.get(cur), prev));
                case VILLAGE_HOP -> {
                    UUID villageId = prevVillage.get(cur);
                    VillageRoadGraph vGraph = villageRoads.getGraph(villageId).orElse(null);
                    UUID entryGwId = vGraph == null ? null
                            : Optional.ofNullable(findGatewayByWorldNodeId(vGraph, prev))
                                    .map(VillageRoadNode::nodeId).orElse(null);
                    UUID exitGwId  = vGraph == null ? null
                            : Optional.ofNullable(findGatewayByWorldNodeId(vGraph, cur))
                                    .map(VillageRoadNode::nodeId).orElse(null);
                    if (entryGwId != null && exitGwId != null) {
                        segments.add(0, new RouteSegment.VillageTraversal(
                                villageId, entryGwId, exitGwId));
                    }
                }
            }
            cur = prev;
        }
        return Optional.of(segments);
    }

    private enum StepKind { WORLD_EDGE, VILLAGE_HOP }

    /**
     * BFS distance between two gateways inside a village, summed by edge
     * {@link tterrag1112.life_in_the_village.Village.Roads.Graph.VillageRoadEdge#length()}.
     * Returns -1 if no path exists. Returns 0 if the two gateways are the
     * same node.
     */
    private static double villageHopCost(VillageRoadGraph vGraph,
                                          UUID fromGw, UUID toGw) {
        if (fromGw.equals(toGw)) return 0.0;
        Optional<List<UUID>> nodePath = vGraph.findPath(fromGw, toGw);
        if (nodePath.isEmpty()) return -1.0;
        List<UUID> nodes = nodePath.get();
        double cost = 0.0;
        for (int i = 1; i < nodes.size(); i++) {
            UUID a = nodes.get(i - 1), b = nodes.get(i);
            for (var edge : vGraph.edgesIncidentTo(a)) {
                if (edge.isIncidentTo(b)) {
                    cost += Math.max(1, edge.length());
                    break;
                }
            }
        }
        return cost;
    }

    private static VillageRoadNode findGatewayByWorldNodeId(VillageRoadGraph vGraph,
                                                              UUID worldNodeId) {
        for (VillageRoadNode g : vGraph.gateways()) {
            UUID linked = g.gatewayInfo().flatMap(i -> i.worldNodeId()).orElse(null);
            if (worldNodeId.equals(linked)) return g;
        }
        return null;
    }

    // =========================================================================
    // Block-path resolution
    // =========================================================================

    /**
     * Concatenates the block paths of the given edges in traversal order,
     * trimming the first 3 blocks of each non-first edge to avoid
     * duplicating junction blocks.
     *
     * <p>Always returns the forward (origin→destination) direction.
     * Reversal for returning caravans is handled by the caller.
     *
     * @param graph        the road graph
     * @param edgeIds      ordered edge IDs as returned by {@link #findEdgePath}
     * @param startNodeId  graph node at the origin end of the first edge
     * @return concatenated block positions, or empty if no edge is realized
     */
    public static List<BlockPos> resolveGraphBlocks(WorldRoadGraph graph,
                                                     List<UUID> edgeIds,
                                                     UUID startNodeId) {
        if (edgeIds == null || edgeIds.isEmpty() || startNodeId == null)
            return List.of();

        List<BlockPos> result = new ArrayList<>();
        UUID currentNode = startNodeId;

        for (int i = 0; i < edgeIds.size(); i++) {
            RoadEdge edge = graph.getEdge(edgeIds.get(i));
            if (edge == null || !edge.isRealized() || edge.getBlockPath().isEmpty()) {
                // Advance node pointer even for unrealized edges so direction
                // tracking stays correct for subsequent realized edges.
                if (edge != null) {
                    currentNode = edge.getNodeAId().equals(currentNode)
                            ? edge.getNodeBId() : edge.getNodeAId();
                }
                continue;
            }

            boolean forward = edge.getNodeAId().equals(currentNode);
            List<BlockPos> blocks = edge.getBlockPath();

            List<BlockPos> oriented;
            if (forward) {
                oriented = blocks;
            } else {
                oriented = new ArrayList<>(blocks);
                Collections.reverse(oriented);
            }

            int skip = (i == 0) ? 0 : Math.min(3, oriented.size());
            result.addAll(oriented.subList(skip, oriented.size()));

            currentNode = forward ? edge.getNodeBId() : edge.getNodeAId();
        }

        return result;
    }
}
