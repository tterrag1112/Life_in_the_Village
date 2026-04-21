package tterrag1112.life_in_the_village.Village.Roads.Graph;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

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

    // ── Internal ─────────────────────────────────────────────────────────────

    /** Clears and repopulates the spatial index from current edge state. */
    void rebuildSpatialIndex() {
        spatialIndex.clear();
        for (RoadEdge edge : edges.values()) {
            spatialIndex.addEdge(edge);
        }
    }
}
