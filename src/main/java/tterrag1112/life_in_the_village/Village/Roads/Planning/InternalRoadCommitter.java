package tterrag1112.life_in_the_village.Village.Roads.Planning;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Networking.VillageRoadsSavedData;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer4.CrossStreet;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer4.RoadNetwork;
import tterrag1112.life_in_the_village.Village.Roads.Graph.VillageRoadEdge;
import tterrag1112.life_in_the_village.Village.Roads.Graph.VillageRoadGraph;
import tterrag1112.life_in_the_village.Village.Roads.Graph.VillageRoadNode;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
     * Builds the village-internal graph directly from the {@link RoadNetwork}
     * produced by V2 planning.
     *
     * <h3>Topology produced</h3>
     * <ul>
     *   <li>Spine GATEWAY at each spine endpoint (already created by
     *       {@link GatewayPopulator}).</li>
     *   <li>Cross-street GATEWAY at each cross-street outer endpoint
     *       (also created by {@link GatewayPopulator}).</li>
     *   <li>One {@link VillageRoadNode#interior(BlockPos)} per
     *       cross-street spine junction.</li>
     *   <li>Spine edges: the spineStart→spineEnd run is split at every
     *       spine junction so each edge spans gateway↔junction or
     *       junction↔junction. EdgeCharacter = {@code THROUGH_VILLAGE}.</li>
     *   <li>Cross-street edges: junction → outer-endpoint gateway. Two
     *       per cross street (one per arm). EdgeCharacter =
     *       {@code SIDE_PATH}.</li>
     * </ul>
     *
     * <h3>cellPath content</h3>
     * For the spine, each split sub-edge's cellPath is a straight-line
     * sample between its two endpoints (block-resolution, every 4 blocks)
     * — the world-graph never reads from these positions; only village
     * traversal does, and the BFS-derived path is only used to nudge
     * caravan navigation between gateway entry and exit. Exact block
     * fidelity is not required: caravans walk the road blocks placed by
     * {@code RoadPainter} regardless.
     *
     * <p>No-op if the village graph already has edges (reload protection).
     */
    public static void commitFromV2(ServerLevel level, Village village, RoadNetwork roads) {
        UUID villageId = village.getId();
        VillageRoadsSavedData roadsSaved = VillageRoadsSavedData.get(level);
        VillageRoadGraph graph = roadsSaved.getOrCreate(villageId);

        // Reload protection — if graph has any edges already, do nothing.
        if (!graph.allEdges().isEmpty()) return;

        BlockPos spineStart = roads.skeleton().spineStart();
        BlockPos spineEnd   = roads.skeleton().spineEnd();
        if (spineStart == null || spineEnd == null) return;

        // ── Resolve the gateway nodes for the spine endpoints ───────────
        VillageRoadNode startGw = findGatewayAt(graph, spineStart);
        VillageRoadNode endGw   = findGatewayAt(graph, spineEnd);
        if (startGw == null || endGw == null) {
            // GatewayPopulator didn't run, or gate positions disagree.
            // Bail rather than guess at a topology.
            return;
        }

        // ── Project each cross-street junction onto the spine and order
        //    them by parameter t along start→end so the spine edges chain
        //    in a single direction. ────────────────────────────────────
        record JunctionProj(CrossStreet cs, BlockPos junction, double t) {}
        List<JunctionProj> junctions = new ArrayList<>();
        double dx = spineEnd.getX() - spineStart.getX();
        double dz = spineEnd.getZ() - spineStart.getZ();
        double lenSq = dx * dx + dz * dz;
        if (lenSq > 0.0) {
            for (CrossStreet cs : roads.skeleton().crossStreets()) {
                BlockPos j = cs.spineJunction();
                if (j == null) continue;
                double jx = j.getX() - spineStart.getX();
                double jz = j.getZ() - spineStart.getZ();
                double t = (jx * dx + jz * dz) / lenSq;
                junctions.add(new JunctionProj(cs, j, t));
            }
            junctions.sort(Comparator.comparingDouble(JunctionProj::t));
        }

        // ── Spine: emit (startGw → junction1 → junction2 → ... → endGw).
        UUID prevNodeId = startGw.nodeId();
        BlockPos prevPos = spineStart;
        int spineEdgeCount = 0;
        for (JunctionProj jp : junctions) {
            UUID juncNodeId = findOrCreateNode(graph, jp.junction());
            graph.addEdge(VillageRoadEdge.create(
                    prevNodeId, juncNodeId,
                    sampleStraight(prevPos, jp.junction()),
                    VillageRoadEdge.EdgeCharacter.THROUGH_VILLAGE));
            spineEdgeCount++;
            prevNodeId = juncNodeId;
            prevPos = jp.junction();
        }
        graph.addEdge(VillageRoadEdge.create(
                prevNodeId, endGw.nodeId(),
                sampleStraight(prevPos, spineEnd),
                VillageRoadEdge.EdgeCharacter.THROUGH_VILLAGE));
        spineEdgeCount++;

        // ── Cross streets: each junction → its two outer-endpoint gateways.
        int crossEdgeCount = 0;
        for (JunctionProj jp : junctions) {
            UUID juncNodeId = findOrCreateNode(graph, jp.junction());
            VillageRoadNode armA = findGatewayAt(graph, jp.cs().start());
            VillageRoadNode armB = findGatewayAt(graph, jp.cs().end());
            if (armA != null) {
                graph.addEdge(VillageRoadEdge.create(
                        juncNodeId, armA.nodeId(),
                        sampleStraight(jp.junction(), jp.cs().start()),
                        VillageRoadEdge.EdgeCharacter.SIDE_PATH));
                crossEdgeCount++;
            }
            if (armB != null) {
                graph.addEdge(VillageRoadEdge.create(
                        juncNodeId, armB.nodeId(),
                        sampleStraight(jp.junction(), jp.cs().end()),
                        VillageRoadEdge.EdgeCharacter.SIDE_PATH));
                crossEdgeCount++;
            }
        }

        roadsSaved.setDirty();

        System.out.println("[InternalRoadCommitter] V2 village '" + village.getName()
                + "' got " + spineEdgeCount + " spine edge(s) + "
                + crossEdgeCount + " cross-street edge(s)");
    }

    /**
     * Returns the GATEWAY node positioned at {@code pos}, or null. Uses an
     * exact equality check; gateway positions come from the same gate
     * positions that produced these nodes, so equality should hold.
     */
    private static VillageRoadNode findGatewayAt(VillageRoadGraph graph, BlockPos pos) {
        if (pos == null) return null;
        for (VillageRoadNode g : graph.gateways()) {
            if (pos.equals(g.position())) return g;
        }
        return null;
    }

    /**
     * Block-resolution straight-line sample from {@code from} to {@code to},
     * inclusive of both endpoints, sampled every 4 blocks. Used as a
     * placeholder cellPath for a village-internal edge whose true geometry
     * is owned by V2's RoadPainter.
     */
    private static List<BlockPos> sampleStraight(BlockPos from, BlockPos to) {
        if (from == null || to == null) return List.of();
        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        int steps = Math.max(1, (int) Math.ceil(len / 4.0));
        List<BlockPos> out = new ArrayList<>(steps + 1);
        for (int i = 0; i <= steps; i++) {
            double t = i / (double) steps;
            out.add(new BlockPos(
                    (int) Math.round(from.getX() + dx * t),
                    (int) Math.round(from.getY() + dy * t),
                    (int) Math.round(from.getZ() + dz * t)));
        }
        return out;
    }
}
