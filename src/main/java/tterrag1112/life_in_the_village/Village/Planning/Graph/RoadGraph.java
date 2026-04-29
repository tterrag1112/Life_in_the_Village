package tterrag1112.life_in_the_village.Village.Planning.Graph;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.RoadShape;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.RoadPrimitive;

import java.util.*;

public final class RoadGraph {

    public record Node(int id, BlockPos pos, NodeKind kind, RoadShape.RoadTier tier) {}

    public record Edge(int id, int fromNodeId, int toNodeId,
                       RoadPrimitive primitive,
                       List<BlockPos> centerline,
                       EdgeRole role) {}

    // ── Storage (deterministic-order lists, plus an adjacency map) ───────
    private final List<Node> nodes = new ArrayList<>();
    private final List<Edge> edges = new ArrayList<>();
    private final Map<Integer, List<Integer>> adjacency = new HashMap<>();
    private final List<Integer> gateNodeIds = new ArrayList<>();

    // ── Mutators ────────────────────────────────────────────────────────

    public int addNode(BlockPos pos, NodeKind kind, RoadShape.RoadTier tier) {
        int id = nodes.size();
        nodes.add(new Node(id, pos, kind, tier));
        if (kind == NodeKind.GATE) {
            gateNodeIds.add(id);
        }
        return id;
    }

    public int addEdge(int fromNodeId, int toNodeId,
                       RoadPrimitive primitive,
                       List<BlockPos> centerline,
                       EdgeRole role) {
        if (fromNodeId < 0 || fromNodeId >= nodes.size()) {
            throw new IllegalArgumentException("fromNodeId " + fromNodeId + " does not exist (node count: " + nodes.size() + ")");
        }
        if (toNodeId < 0 || toNodeId >= nodes.size()) {
            throw new IllegalArgumentException("toNodeId " + toNodeId + " does not exist (node count: " + nodes.size() + ")");
        }
        int id = edges.size();
        edges.add(new Edge(id, fromNodeId, toNodeId, primitive, List.copyOf(centerline), role));
        adjacency.computeIfAbsent(fromNodeId, k -> new ArrayList<>()).add(id);
        adjacency.computeIfAbsent(toNodeId, k -> new ArrayList<>()).add(id);
        return id;
    }

    // ── Queries ─────────────────────────────────────────────────────────

    public Node node(int id) {
        return nodes.get(id);
    }

    public Edge edge(int id) {
        return edges.get(id);
    }

    public List<Edge> edgesAt(int nodeId) {
        List<Integer> edgeIds = adjacency.get(nodeId);
        if (edgeIds == null || edgeIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<Edge> result = new ArrayList<>(edgeIds.size());
        for (int eid : edgeIds) {
            result.add(edges.get(eid));
        }
        return Collections.unmodifiableList(result);
    }

    public Edge edgeNearest(BlockPos pos) {
        if (edges.isEmpty()) return null;
        Edge nearest = null;
        long minDistSq = Long.MAX_VALUE;
        for (Edge e : edges) {
            for (BlockPos p : e.centerline()) {
                long dx = pos.getX() - p.getX();
                long dz = pos.getZ() - p.getZ();
                long distSq = dx * dx + dz * dz;
                if (distSq < minDistSq) {
                    minDistSq = distSq;
                    nearest = e;
                }
            }
        }
        return nearest;
    }

    public boolean isOnAnyEdge(BlockPos pos, int slack) {
        for (Edge e : edges) {
            for (BlockPos p : e.centerline()) {
                double dx = pos.getX() - p.getX();
                double dz = pos.getZ() - p.getZ();
                if (Math.hypot(dx, dz) <= slack) {
                    return true;
                }
            }
        }
        return false;
    }

    public List<Node> gates() {
        List<Node> result = new ArrayList<>(gateNodeIds.size());
        for (int id : gateNodeIds) {
            result.add(nodes.get(id));
        }
        return Collections.unmodifiableList(result);
    }

    public Node mainGate() {
        if (gateNodeIds.isEmpty()) return null;
        return nodes.get(gateNodeIds.get(0));
    }

    public Iterable<Edge> allEdges() {
        return Collections.unmodifiableList(edges);
    }

    public Iterable<Node> allNodes() {
        return Collections.unmodifiableList(nodes);
    }

    public int nodeCount() {
        return nodes.size();
    }

    public int edgeCount() {
        return edges.size();
    }
}
