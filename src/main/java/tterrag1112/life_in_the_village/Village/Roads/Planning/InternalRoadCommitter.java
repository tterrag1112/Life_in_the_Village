package tterrag1112.life_in_the_village.Village.Roads.Planning;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Networking.VillageRoadsSavedData;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.RoadPrimitive;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.NetworkEdge;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.NetworkNode;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.NetworkSpec;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.NodeKind;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer4.RoadNetwork;
import tterrag1112.life_in_the_village.Village.Roads.Graph.VillageRoadEdge;
import tterrag1112.life_in_the_village.Village.Roads.Graph.VillageRoadGraph;
import tterrag1112.life_in_the_village.Village.Roads.Graph.VillageRoadNode;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Populates a village's {@link VillageRoadGraph} with internal road edges derived from the
 * V2 {@link RoadNetwork}, connecting the GATEWAY nodes already placed by
 * {@link GatewayPopulator}.
 *
 * <h3>When to call</h3>
 * Call {@link #commitFromV2} immediately after {@link GatewayPopulator#populate} in the
 * spawn pipeline. If the graph already has edges, the commit is a no-op (reload protection).
 */
public final class InternalRoadCommitter {

    private InternalRoadCommitter() {}

    // =========================================================================
    // Node lookup / creation helpers
    // =========================================================================

    /**
     * Finds an existing node in the graph at {@code pos} (exact position match),
     * or creates and adds a new INTERIOR node there.
     */
    private static UUID findOrCreateNode(VillageRoadGraph graph, BlockPos pos) {
        for (VillageRoadNode node : graph.allNodes()) {
            if (node.position().equals(pos)) return node.nodeId();
        }
        VillageRoadNode interior = VillageRoadNode.interior(pos);
        return graph.addNode(interior);
    }

    // =========================================================================
    // Track C2 — V2 RoadNetwork commit
    // =========================================================================

    /**
     * Layout Rework Stage 3d — builds the village-internal nav graph by
     * faithfully reproducing the routed {@link RoadNetwork}'s node+edge
     * graph (the {@code BlockServingRouter} CLUSTER network), so the nav
     * graph matches exactly what {@code RoadPainter} painted and the
     * gateways stay connected for caravan/NPC routing.
     *
     * <h3>Mapping</h3>
     * <ul>
     *   <li>Each {@link NetworkNode} → a {@link VillageRoadNode}: a
     *       {@code GATEWAY}-kind node maps to the EXISTING gateway node
     *       (created by {@link GatewayPopulator}) matched by position;
     *       {@code ANCHOR}/{@code JUNCTION}/{@code SYNTHETIC} nodes become
     *       interior nodes.</li>
     *   <li>Each {@link NetworkEdge} → a {@link VillageRoadEdge} between
     *       the two mapped nodes, with {@code cellPath} = the routed
     *       centerline ({@link RoadPrimitive.SmoothedPath#waypoints()} —
     *       never a straight-line placeholder, never empty), and
     *       {@code EdgeCharacter} = {@code THROUGH_VILLAGE} for the wider
     *       trunk edges, else {@code SIDE_PATH}.</li>
     * </ul>
     *
     * <p>The router's own {@code ensureConnected} sweep determines
     * connectivity; this commit does NOT re-route — it reproduces the
     * emitted graph exactly. Bails if the network has fewer than two
     * nodes or no resolvable gateways. No-op if the village graph already
     * has edges (reload protection).
     */
    public static void commitFromV2(ServerLevel level, Village village, RoadNetwork roads) {
        UUID villageId = village.getId();
        VillageRoadsSavedData roadsSaved = VillageRoadsSavedData.get(level);
        VillageRoadGraph graph = roadsSaved.getOrCreate(villageId);

        // Reload protection — if graph has any edges already, do nothing.
        if (!graph.allEdges().isEmpty()) return;

        NetworkSpec spec = roads.skeleton().network();
        if (spec == null || spec.nodes().size() < 2) return;

        // ── Map every NetworkNode id → VillageRoadGraph node id. GATEWAY
        //    nodes map to the EXISTING gateway nodes (by position, the same
        //    positions buildRealizedLayout fed GatewayPopulator); all other
        //    kinds become interior nodes. ─────────────────────────────────
        Map<String, UUID> nodeMap = new HashMap<>();
        int gatewaysResolved = 0;
        for (NetworkNode n : spec.nodes()) {
            UUID id;
            if (n.kind() == NodeKind.GATEWAY) {
                VillageRoadNode gw = findGatewayAt(graph, n.pos());
                if (gw != null) {
                    id = gw.nodeId();
                    gatewaysResolved++;
                } else {
                    // Gateway graph-node missing (degenerate). Fall back to
                    // an interior node so the edge still connects rather
                    // than dropping a whole arm of the network.
                    id = findOrCreateNode(graph, n.pos());
                }
            } else {
                id = findOrCreateNode(graph, n.pos());
            }
            nodeMap.put(n.id(), id);
        }
        if (gatewaysResolved < 1) {
            // No gateways resolved — GraphTradeRouteEstablisher can't find
            // the village's entry/exit. Bail rather than build an
            // un-enterable graph.
            return;
        }

        // ── One VillageRoadEdge per routed NetworkEdge, using the routed
        //    SmoothedPath centerline as the cellPath. ────────────────────
        int edgeCount = 0;
        for (NetworkEdge e : spec.edges()) {
            UUID from = nodeMap.get(e.fromNodeId());
            UUID to   = nodeMap.get(e.toNodeId());
            if (from == null || to == null || from.equals(to)) continue;
            List<BlockPos> cellPath = waypointsOf(e);
            if (cellPath.size() < 2) continue;   // need a walkable path
            VillageRoadEdge.EdgeCharacter character =
                    e.width() >= TRUNK_WIDTH_THRESHOLD
                            ? VillageRoadEdge.EdgeCharacter.THROUGH_VILLAGE
                            : VillageRoadEdge.EdgeCharacter.SIDE_PATH;
            graph.addEdge(VillageRoadEdge.create(from, to, cellPath, character));
            edgeCount++;
        }

        roadsSaved.setDirty();

        System.out.println("[InternalRoadCommitter] V2 village '" + village.getName()
                + "' got " + edgeCount + " routed edge(s) across "
                + spec.nodes().size() + " node(s), " + gatewaysResolved
                + " gateway(s)");
    }

    /** Edges at least this wide are the gateway→core trunk (router
     *  TRUNK_WIDTH = 3); narrower edges are local branches. */
    private static final int TRUNK_WIDTH_THRESHOLD = 3;

    /** The routed centerline for an edge — the {@link RoadPrimitive.SmoothedPath}
     *  waypoints the router emitted. Empty only if the edge somehow isn't a
     *  SmoothedPath (the router emits only SmoothedPath edges). */
    private static List<BlockPos> waypointsOf(NetworkEdge e) {
        return e.primitive() instanceof RoadPrimitive.SmoothedPath sp
                ? sp.waypoints()
                : List.of();
    }

    /**
     * Returns the GATEWAY node positioned at {@code pos}, or null. Uses an
     * exact equality check; the routed GATEWAY node positions are the same
     * positions {@code buildRealizedLayout} fed to {@link GatewayPopulator},
     * so equality holds.
     */
    private static VillageRoadNode findGatewayAt(VillageRoadGraph graph, BlockPos pos) {
        if (pos == null) return null;
        for (VillageRoadNode g : graph.gateways()) {
            if (pos.equals(g.position())) return g;
        }
        return null;
    }
}
