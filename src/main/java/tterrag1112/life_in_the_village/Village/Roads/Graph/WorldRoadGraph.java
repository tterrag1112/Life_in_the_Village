package tterrag1112.life_in_the_village.Village.Roads.Graph;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;

import java.util.*;

/**
 * The canonical world road graph. Holds all {@link RoadNode}s and
 * {@link RoadEdge}s for a dimension and exposes a spatial index for
 * efficient proximity queries.
 *
 * <h3>Source of truth (Invariant 1)</h3>
 * This object is the canonical source of truth for the road network.
 * Block placement is derived from graph state, not the reverse.
 *
 * <h3>Codec covers nodes + edges only</h3>
 * The {@link EdgeGridIndex} is transient and is rebuilt from the edge list
 * on every deserialization. This is intentional: the index is a pure
 * function of the edge cell paths, so rebuilding it is cheaper and simpler
 * than persisting and validating it separately.
 */
public class WorldRoadGraph {

    // ── Codec ────────────────────────────────────────────────────────────────

    public static final Codec<WorldRoadGraph> CODEC = RecordCodecBuilder.create(i -> i.group(
            RoadNode.CODEC.listOf()
                    .optionalFieldOf("nodes", new ArrayList<>())
                    .forGetter(g -> new ArrayList<>(g.nodes.values())),
            RoadEdge.CODEC.listOf()
                    .optionalFieldOf("edges", new ArrayList<>())
                    .forGetter(g -> new ArrayList<>(g.edges.values()))
    ).apply(i, WorldRoadGraph::fromCodec));

    static WorldRoadGraph fromCodec(List<RoadNode> nodeList, List<RoadEdge> edgeList) {
        WorldRoadGraph graph = new WorldRoadGraph();
        for (RoadNode n : nodeList) graph.nodes.put(n.nodeId(), n);
        for (RoadEdge e : edgeList) graph.edges.put(e.getEdgeId(), e);
        graph.rebuildSpatialIndex();
        return graph;
    }

    // ── State ────────────────────────────────────────────────────────────────

    private final Map<UUID, RoadNode> nodes = new LinkedHashMap<>();
    private final Map<UUID, RoadEdge> edges = new LinkedHashMap<>();

    /** Transient — rebuilt from edge list on load. Not serialized. */
    private final EdgeGridIndex spatialIndex = new EdgeGridIndex();

    // ── Graph mutation ───────────────────────────────────────────────────────

    public void addNode(RoadNode node) {
        nodes.put(node.nodeId(), node);
    }

    /** Adds an edge and registers it in the spatial index. */
    public void addEdge(RoadEdge edge) {
        edges.put(edge.getEdgeId(), edge);
        spatialIndex.addEdge(edge);
    }

    /** Removes a node by id. Does NOT remove its incident edges — caller's responsibility. */
    public void removeNode(UUID id) {
        nodes.remove(id);
    }

    /** Removes an edge and unregisters it from the spatial index. */
    public void removeEdge(UUID id) {
        if (edges.remove(id) != null) {
            spatialIndex.removeEdge(id);
        }
    }

    // ── Queries ──────────────────────────────────────────────────────────────

    public RoadNode getNode(UUID id) { return nodes.get(id); }
    public RoadEdge getEdge(UUID id) { return edges.get(id); }

    public Collection<RoadEdge> allEdges() { return Collections.unmodifiableCollection(edges.values()); }
    public Collection<RoadNode> allNodes() { return Collections.unmodifiableCollection(nodes.values()); }

    /** Unmodifiable view of the node map, keyed by node UUID. */
    public Map<UUID, RoadNode> getNodes() { return Collections.unmodifiableMap(nodes); }

    /** Unmodifiable view of the edge map, keyed by edge UUID. */
    public Map<UUID, RoadEdge> getEdges() { return Collections.unmodifiableMap(edges); }

    /**
     * Returns all edges whose cell path passes through any 256-block bucket
     * that overlaps the given radius around (blockX, blockZ). Result may
     * include edges slightly outside the exact radius — callers that need
     * exact distance filtering must apply it themselves.
     */
    public List<RoadEdge> edgesNear(int blockX, int blockZ, int radiusBlocks) {
        Set<UUID> ids = spatialIndex.edgesNear(blockX, blockZ, radiusBlocks);
        List<RoadEdge> result = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            RoadEdge e = edges.get(id);
            if (e != null) result.add(e);
        }
        return result;
    }

    /** Exposes the spatial index for validator sanity checks. */
    public EdgeGridIndex getSpatialIndex() { return spatialIndex; }

    /**
     * Returns all nodes whose stored position is within {@code radiusBlocks}
     * of (blockX, blockZ). Linear scan over all nodes — acceptable for the
     * small node counts expected in Phase 3b.
     */
    public List<RoadNode> nodesNear(int blockX, int blockZ, int radiusBlocks) {
        long rSqL = (long) radiusBlocks * radiusBlocks;
        List<RoadNode> result = new ArrayList<>();
        for (RoadNode node : nodes.values()) {
            long dx = node.position().getX() - blockX;
            long dz = node.position().getZ() - blockZ;
            if (dx * dx + dz * dz <= rSqL) result.add(node);
        }
        return result;
    }

    /**
     * Result of a {@link #splitEdgeAtCell} operation.
     *
     * @param junctionNodeId the new TRUNK_JUNCTION node inserted at the split point
     * @param newEdgeAId     the half-edge from original nodeA to the junction
     * @param newEdgeBId     the half-edge from the junction to original nodeB
     */
    public record SplitResult(UUID junctionNodeId, UUID newEdgeAId, UUID newEdgeBId) {}

    /**
     * Splits the edge at the given cell key. Inserts a {@link RoadNode.NodeType#TRUNK_JUNCTION}
     * at {@code junctionPos} (caller is responsible for surface-snapping). Preserves tier,
     * meanderProfile, maintenance, and maintainerVillageIds on both halves. If the original
     * edge was realized, splits the block path at the block closest to junctionPos.
     *
     * @return the split result, or empty if the edge is not found or splitCellKey is not in its path
     */
    public Optional<SplitResult> splitEdgeAtCell(UUID edgeId, long splitCellKey,
                                                  BlockPos junctionPos) {
        RoadEdge edge = edges.get(edgeId);
        if (edge == null) return Optional.empty();

        List<Long> cellPath = edge.getCellPath();
        int splitIdx = cellPath.indexOf(splitCellKey);
        if (splitIdx < 0) return Optional.empty();

        // Build half-paths (junction cell included in both to preserve continuity)
        List<Long> halfPathA = new ArrayList<>(cellPath.subList(0, splitIdx + 1));
        List<Long> halfPathB = new ArrayList<>(cellPath.subList(splitIdx, cellPath.size()));

        // Split block path proportionally by nearest block to junctionPos
        List<BlockPos> blockA = new ArrayList<>();
        List<BlockPos> blockB = new ArrayList<>();
        if (edge.isRealized() && !edge.getBlockPath().isEmpty()) {
            List<BlockPos> bp = edge.getBlockPath();
            int splitBlockIdx = 0;
            double bestDist = Double.MAX_VALUE;
            for (int i = 0; i < bp.size(); i++) {
                double d = bp.get(i).distSqr(junctionPos);
                if (d < bestDist) { bestDist = d; splitBlockIdx = i; }
            }
            blockA = new ArrayList<>(bp.subList(0, splitBlockIdx + 1));
            blockB = new ArrayList<>(bp.subList(splitBlockIdx, bp.size()));
        }

        // Distribute stale cells to the half that covers them
        Set<Long> halfASet = new HashSet<>(halfPathA);
        Set<Long> halfBSet = new HashSet<>(halfPathB);

        RoadNode junctionNode = new RoadNode(UUID.randomUUID(), junctionPos,
                RoadNode.NodeType.TRUNK_JUNCTION, Optional.empty());

        RoadEdge halfA = RoadEdge.create(
                edge.getNodeAId(), junctionNode.nodeId(),
                halfPathA, edge.getTier(), edge.getMeanderProfile());
        halfA.setMaintenance(edge.getMaintenance());
        halfA.getMaintainerVillageIds().addAll(edge.getMaintainerVillageIds());
        for (long stale : edge.getStaleCells()) {
            if (halfASet.contains(stale)) halfA.markCellStale(stale);
        }
        if (!blockA.isEmpty()) halfA.markRealized(blockA);
        halfA.clearPrimitives();

        RoadEdge halfB = RoadEdge.create(
                junctionNode.nodeId(), edge.getNodeBId(),
                halfPathB, edge.getTier(), edge.getMeanderProfile());
        halfB.setMaintenance(edge.getMaintenance());
        halfB.getMaintainerVillageIds().addAll(edge.getMaintainerVillageIds());
        for (long stale : edge.getStaleCells()) {
            if (halfBSet.contains(stale)) halfB.markCellStale(stale);
        }
        if (!blockB.isEmpty()) halfB.markRealized(blockB);
        halfB.clearPrimitives();

        removeEdge(edgeId);
        addNode(junctionNode);
        addEdge(halfA);
        addEdge(halfB);

        return Optional.of(new SplitResult(junctionNode.nodeId(), halfA.getEdgeId(), halfB.getEdgeId()));
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    /** Clears and repopulates the spatial index from current edge state. */
    void rebuildSpatialIndex() {
        spatialIndex.clear();
        for (RoadEdge edge : edges.values()) {
            spatialIndex.addEdge(edge);
        }
    }
}
