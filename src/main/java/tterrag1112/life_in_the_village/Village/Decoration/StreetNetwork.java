// src/main/java/tterrag1112/life_in_the_village/Village/Decoration/StreetNetwork.java
package tterrag1112.life_in_the_village.Village.Decoration;

import net.minecraft.core.BlockPos;

import java.util.*;

/**
 * A graph of street segments connecting buildings in a village.
 *
 * <h3>Design</h3>
 * Rather than routing every building back to the town square
 * (hub-and-spoke, which looks artificial), the network builds
 * hierarchically:
 * <ol>
 *   <li>PRIMARY streets connect the town square to civic and
 *       production buildings directly.</li>
 *   <li>SECONDARY streets branch from the nearest PRIMARY node
 *       to secondary buildings.</li>
 *   <li>TERTIARY alleys branch from the nearest SECONDARY node
 *       to houses and farms.</li>
 * </ol>
 *
 * <p>This produces the characteristic medieval branching street
 * pattern rather than a wagon-wheel.
 */
public class StreetNetwork {

    // ── Node ─────────────────────────────────────────────────────────────────

    public static class Node {
        public final UUID       id;
        public final BlockPos   pos;
        public final StreetTier tier;
        /** The building entrance this node serves, or null for intersections. */
        public final UUID       buildingId;

        private final List<Edge> edges = new ArrayList<>();

        public Node(BlockPos pos, StreetTier tier, UUID buildingId) {
            this.id         = UUID.randomUUID();
            this.pos        = pos;
            this.tier       = tier;
            this.buildingId = buildingId;
        }

        public List<Edge> getEdges() { return Collections.unmodifiableList(edges); }

        void addEdge(Edge e) { edges.add(e); }
    }

    // ── Edge ─────────────────────────────────────────────────────────────────

    public static class Edge {
        public final Node       from;
        public final Node       to;
        public final StreetTier tier;
        /** The actual placed block positions along this segment. */
        public final List<BlockPos> blocks;

        public Edge(Node from, Node to, StreetTier tier,
                    List<BlockPos> blocks) {
            this.from   = from;
            this.to     = to;
            this.tier   = tier;
            this.blocks = List.copyOf(blocks);
        }
    }

    // ── Network state ─────────────────────────────────────────────────────────

    private final Map<UUID, Node> nodes = new LinkedHashMap<>();
    private final List<Edge>      edges = new ArrayList<>();
    /** Hub node — the town square centre. */
    private Node hub;

    // =========================================================================
    // Construction
    // =========================================================================

    public void setHub(BlockPos hubPos) {
        hub = new Node(hubPos, StreetTier.PRIMARY, null);
        nodes.put(hub.id, hub);
    }

    public Node addNode(BlockPos pos, StreetTier tier, UUID buildingId) {
        Node n = new Node(pos, tier, buildingId);
        nodes.put(n.id, n);
        return n;
    }

    /**
     * Connects {@code from} to {@code to} with a street segment of the
     * given tier. The caller provides the already-placed block list.
     */
    public Edge connect(Node from, Node to, StreetTier tier,
                        List<BlockPos> blocks) {
        Edge e = new Edge(from, to, tier, blocks);
        edges.add(e);
        from.addEdge(e);
        to.addEdge(e);
        return e;
    }

    // =========================================================================
    // Queries
    // =========================================================================

    public Node getHub() { return hub; }
    public Collection<Node> getAllNodes() { return nodes.values(); }
    public List<Edge>       getAllEdges() { return Collections.unmodifiableList(edges); }

    /**
     * Finds the nearest node of tier ≤ {@code maxTier} to the given
     * position. Used by the branching logic to find where to branch from.
     */
    public Node nearestNodeOfTier(BlockPos pos, StreetTier maxTier) {
        Node best     = hub;
        double bestDist = best == null ? Double.MAX_VALUE
                : best.pos.distSqr(pos);

        for (Node n : nodes.values()) {
            if (n.tier.ordinal() > maxTier.ordinal()) continue;
            double d = n.pos.distSqr(pos);
            if (d < bestDist) {
                bestDist = d;
                best     = n;
            }
        }
        return best;
    }

    /**
     * Returns all block positions in the network that belong to streets
     * of tier ≤ {@code maxTier}, in no particular order.
     * Used to build the {@code protectedXZ} set in VillageDecorator.
     */
    public Set<BlockPos> collectBlocks(StreetTier maxTier) {
        Set<BlockPos> result = new HashSet<>();
        for (Edge e : edges) {
            if (e.tier.ordinal() <= maxTier.ordinal()) {
                result.addAll(e.blocks);
            }
        }
        return result;
    }

    /** Total number of placed street blocks across all edges. */
    public int totalBlockCount() {
        return edges.stream().mapToInt(e -> e.blocks.size()).sum();
    }
}