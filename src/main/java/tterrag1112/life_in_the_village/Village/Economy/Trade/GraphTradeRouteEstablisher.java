package tterrag1112.life_in_the_village.Village.Economy.Trade;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadEdge;
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

        // Build adjacency list from edges
        Map<UUID, List<EdgeConnection>> adj = new HashMap<>();
        for (RoadEdge edge : graph.allEdges()) {
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
