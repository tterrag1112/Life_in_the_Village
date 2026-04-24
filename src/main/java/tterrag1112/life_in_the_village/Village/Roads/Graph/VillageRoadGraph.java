package tterrag1112.life_in_the_village.Village.Roads.Graph;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;

import java.util.*;

/**
 * Village-internal road graph. Each village owns one instance; the graph may
 * be empty (no internal roads generated yet) or fully populated by Slice 2.
 *
 * <h3>Scale</h3>
 * A typical village has 5–20 nodes and 10–40 edges. No aggressive spatial
 * indexing is needed; all proximity queries do a simple O(n) scan.
 *
 * <h3>Serialization</h3>
 * The codec covers nodes + edges only.  The graph is stored inside
 * {@link tterrag1112.life_in_the_village.Networking.VillageRoadsSavedData}
 * keyed by village UUID.
 *
 * <h3>Invariants</h3>
 * Call {@link #validateInvariants()} at load time or from a debug command.
 * It returns a list of violation descriptions; an empty list means the graph
 * is structurally valid.
 */
public final class VillageRoadGraph {

    // =========================================================================
    // Codec — nodes + edges only; villageId is the map key in the saved data
    // =========================================================================

    private static final Codec<UUID> UUID_CODEC =
            Codec.STRING.xmap(UUID::fromString, UUID::toString);

    /** On-wire form: lists of nodes and edges. villageId supplied by the container. */
    public record Wire(List<VillageRoadNode> nodes, List<VillageRoadEdge> edges) {
        public static final Codec<Wire> CODEC = RecordCodecBuilder.create(i -> i.group(
                VillageRoadNode.CODEC.listOf()
                        .optionalFieldOf("nodes", new ArrayList<>())
                        .forGetter(Wire::nodes),
                VillageRoadEdge.CODEC.listOf()
                        .optionalFieldOf("edges", new ArrayList<>())
                        .forGetter(Wire::edges)
        ).apply(i, Wire::new));
    }

    public Wire toWire() {
        return new Wire(new ArrayList<>(nodes.values()), new ArrayList<>(edges.values()));
    }

    public static VillageRoadGraph fromWire(UUID villageId, Wire wire) {
        VillageRoadGraph g = new VillageRoadGraph(villageId);
        for (VillageRoadNode n : wire.nodes()) g.nodes.put(n.nodeId(), n);
        for (VillageRoadEdge e : wire.edges()) {
            g.edges.put(e.edgeId(), e);
            g.incidenceIndex.computeIfAbsent(e.fromNodeId(), k -> new HashSet<>()).add(e.edgeId());
            g.incidenceIndex.computeIfAbsent(e.toNodeId(),   k -> new HashSet<>()).add(e.edgeId());
        }
        return g;
    }

    // =========================================================================
    // State
    // =========================================================================

    private final UUID villageId;
    private final Map<UUID, VillageRoadNode> nodes = new LinkedHashMap<>();
    private final Map<UUID, VillageRoadEdge> edges = new LinkedHashMap<>();

    /**
     * Transient reverse-map: nodeId → set of incident edge UUIDs.
     * Rebuilt from edge list on {@link #fromWire}; kept in sync by
     * {@link #addEdge} and {@link #removeEdge}.
     */
    private final Map<UUID, Set<UUID>> incidenceIndex = new HashMap<>();

    // =========================================================================
    // Constructor
    // =========================================================================

    public VillageRoadGraph(UUID villageId) {
        this.villageId = villageId;
    }

    // =========================================================================
    // Node operations
    // =========================================================================

    /** Adds the node. Returns its UUID. */
    public UUID addNode(VillageRoadNode node) {
        nodes.put(node.nodeId(), node);
        return node.nodeId();
    }

    /**
     * Replaces the node with the same UUID in-place (metadata update only).
     * Does not modify edges or the incidence index — use only for node-metadata
     * changes (e.g. updating {@code gatewayInfo.worldNodeId} after backlink creation).
     */
    public void replaceNode(VillageRoadNode node) {
        if (nodes.containsKey(node.nodeId())) {
            nodes.put(node.nodeId(), node);
        }
    }

    public Optional<VillageRoadNode> getNode(UUID nodeId) {
        return Optional.ofNullable(nodes.get(nodeId));
    }

    public List<VillageRoadNode> nodesOfType(VillageRoadNode.NodeType type) {
        return nodes.values().stream().filter(n -> n.type() == type).toList();
    }

    public List<VillageRoadNode> gateways() {
        return nodesOfType(VillageRoadNode.NodeType.GATEWAY);
    }

    /**
     * Removes the node and all incident edges.
     *
     * @return true if the node was present
     */
    public boolean removeNode(UUID nodeId) {
        if (nodes.remove(nodeId) == null) return false;
        // Remove all edges that reference this node
        List<UUID> toRemove = new ArrayList<>(incidenceIndex.getOrDefault(nodeId, Set.of()));
        toRemove.forEach(this::removeEdge);
        incidenceIndex.remove(nodeId);
        return true;
    }

    // =========================================================================
    // Edge operations
    // =========================================================================

    /** Adds the edge and updates the incidence index. Returns its UUID. */
    public UUID addEdge(VillageRoadEdge edge) {
        edges.put(edge.edgeId(), edge);
        incidenceIndex.computeIfAbsent(edge.fromNodeId(), k -> new HashSet<>()).add(edge.edgeId());
        incidenceIndex.computeIfAbsent(edge.toNodeId(),   k -> new HashSet<>()).add(edge.edgeId());
        return edge.edgeId();
    }

    public Optional<VillageRoadEdge> getEdge(UUID edgeId) {
        return Optional.ofNullable(edges.get(edgeId));
    }

    public List<VillageRoadEdge> edgesIncidentTo(UUID nodeId) {
        Set<UUID> ids = incidenceIndex.getOrDefault(nodeId, Set.of());
        List<VillageRoadEdge> result = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            VillageRoadEdge e = edges.get(id);
            if (e != null) result.add(e);
        }
        return result;
    }

    public List<VillageRoadEdge> allEdges() {
        return List.copyOf(edges.values());
    }

    public List<VillageRoadNode> allNodes() {
        return List.copyOf(nodes.values());
    }

    /**
     * Removes the edge and updates the incidence index.
     *
     * @return true if the edge was present
     */
    public boolean removeEdge(UUID edgeId) {
        VillageRoadEdge e = edges.remove(edgeId);
        if (e == null) return false;
        removeFromIncidence(e.fromNodeId(), edgeId);
        removeFromIncidence(e.toNodeId(),   edgeId);
        return true;
    }

    private void removeFromIncidence(UUID nodeId, UUID edgeId) {
        Set<UUID> set = incidenceIndex.get(nodeId);
        if (set != null) {
            set.remove(edgeId);
            if (set.isEmpty()) incidenceIndex.remove(nodeId);
        }
    }

    // =========================================================================
    // Spatial queries — O(n); village graphs are tiny
    // =========================================================================

    /**
     * Returns edges whose cellPath contains at least one position within
     * {@code radius} blocks of (x, z). O(n×m) where n = edges, m = path length.
     */
    public List<VillageRoadEdge> edgesNear(int x, int z, int radius) {
        long rSq = (long) radius * radius;
        List<VillageRoadEdge> result = new ArrayList<>();
        for (VillageRoadEdge e : edges.values()) {
            for (BlockPos p : e.cellPath()) {
                long dx = p.getX() - x;
                long dz = p.getZ() - z;
                if (dx * dx + dz * dz <= rSq) { result.add(e); break; }
            }
        }
        return result;
    }

    /**
     * Returns nodes within {@code radius} blocks of (x, z). O(n).
     */
    public List<VillageRoadNode> nodesNear(int x, int z, int radius) {
        long rSq = (long) radius * radius;
        List<VillageRoadNode> result = new ArrayList<>();
        for (VillageRoadNode n : nodes.values()) {
            long dx = n.position().getX() - x;
            long dz = n.position().getZ() - z;
            if (dx * dx + dz * dz <= rSq) result.add(n);
        }
        return result;
    }

    // =========================================================================
    // Path query — BFS; used by future caravan pathing
    // =========================================================================

    /**
     * Finds a path from {@code fromNodeId} to {@code toNodeId} using BFS.
     * Returns a list of node UUIDs (including start and end), or empty if
     * no path exists or either node is absent.
     */
    public Optional<List<UUID>> findPath(UUID fromNodeId, UUID toNodeId) {
        if (!nodes.containsKey(fromNodeId) || !nodes.containsKey(toNodeId)) {
            return Optional.empty();
        }
        if (fromNodeId.equals(toNodeId)) return Optional.of(List.of(fromNodeId));

        Map<UUID, UUID> parent = new HashMap<>();
        Deque<UUID> queue = new ArrayDeque<>();
        queue.add(fromNodeId);
        parent.put(fromNodeId, null);

        while (!queue.isEmpty()) {
            UUID current = queue.poll();
            if (current.equals(toNodeId)) {
                // Reconstruct path
                List<UUID> path = new ArrayList<>();
                UUID node = toNodeId;
                while (node != null) {
                    path.add(node);
                    node = parent.get(node);
                }
                Collections.reverse(path);
                return Optional.of(path);
            }
            for (VillageRoadEdge e : edgesIncidentTo(current)) {
                if (!e.isTraversable()) continue;
                UUID neighbor = e.fromNodeId().equals(current) ? e.toNodeId() : e.fromNodeId();
                if (!parent.containsKey(neighbor)) {
                    parent.put(neighbor, current);
                    queue.add(neighbor);
                }
            }
        }
        return Optional.empty();
    }

    // =========================================================================
    // Identity
    // =========================================================================

    public UUID getVillageId() { return villageId; }

    public boolean isEmpty() { return nodes.isEmpty() && edges.isEmpty(); }

    public int nodeCount() { return nodes.size(); }
    public int edgeCount() { return edges.size(); }

    // =========================================================================
    // Invariant validation
    // =========================================================================

    /**
     * Validates structural invariants of this graph.
     *
     * @return list of violation descriptions; empty if the graph is valid
     */
    public List<String> validateInvariants() {
        List<String> violations = new ArrayList<>();

        // 1. villageId must be non-null (always is for correctly constructed graphs)
        if (villageId == null) {
            violations.add("VillageRoadGraph has null villageId");
        }

        // 2. Every edge's endpoints must reference present nodes
        for (VillageRoadEdge e : edges.values()) {
            if (!nodes.containsKey(e.fromNodeId())) {
                violations.add("Edge " + shortId(e.edgeId()) + " fromNodeId "
                        + shortId(e.fromNodeId()) + " is not present in nodes map");
            }
            if (!nodes.containsKey(e.toNodeId())) {
                violations.add("Edge " + shortId(e.edgeId()) + " toNodeId "
                        + shortId(e.toNodeId()) + " is not present in nodes map");
            }
        }

        // 3. GATEWAY nodes must have GatewayInfo
        for (VillageRoadNode n : nodes.values()) {
            if (n.type() == VillageRoadNode.NodeType.GATEWAY
                    && n.gatewayInfo().isEmpty()) {
                violations.add("GATEWAY node " + shortId(n.nodeId())
                        + " at " + n.position().toShortString() + " has no GatewayInfo");
            }
        }

        // 4. Non-GATEWAY nodes must NOT have GatewayInfo
        for (VillageRoadNode n : nodes.values()) {
            if (n.type() != VillageRoadNode.NodeType.GATEWAY
                    && n.gatewayInfo().isPresent()) {
                violations.add("Non-GATEWAY node " + shortId(n.nodeId())
                        + " (type=" + n.type() + ") has unexpected GatewayInfo");
            }
        }

        // 5. If edges exist, at least one GATEWAY must exist
        if (!edges.isEmpty() && gateways().isEmpty()) {
            violations.add("Graph has " + edges.size()
                    + " edge(s) but no GATEWAY nodes — internal network has no exit");
        }

        // 6. Incidence index is consistent with edge list
        for (VillageRoadEdge e : edges.values()) {
            Set<UUID> fromIncident = incidenceIndex.getOrDefault(e.fromNodeId(), Set.of());
            if (!fromIncident.contains(e.edgeId())) {
                violations.add("Incidence index missing edge " + shortId(e.edgeId())
                        + " for fromNode " + shortId(e.fromNodeId()));
            }
            Set<UUID> toIncident = incidenceIndex.getOrDefault(e.toNodeId(), Set.of());
            if (!toIncident.contains(e.edgeId())) {
                violations.add("Incidence index missing edge " + shortId(e.edgeId())
                        + " for toNode " + shortId(e.toNodeId()));
            }
        }

        return violations;
    }

    private static String shortId(UUID id) {
        return id == null ? "<null>" : id.toString().substring(0, 8);
    }
}
