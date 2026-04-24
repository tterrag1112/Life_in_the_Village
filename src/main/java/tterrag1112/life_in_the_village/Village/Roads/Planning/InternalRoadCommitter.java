package tterrag1112.life_in_the_village.Village.Roads.Planning;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Networking.VillageRoadsSavedData;
import tterrag1112.life_in_the_village.Village.Planning.VillageLayout;
import tterrag1112.life_in_the_village.Village.Roads.Graph.VillageRoadEdge;
import tterrag1112.life_in_the_village.Village.Roads.Graph.VillageRoadGraph;
import tterrag1112.life_in_the_village.Village.Roads.Graph.VillageRoadNode;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Populates a village's {@link VillageRoadGraph} with internal road edges derived from the
 * layout's road centerlines, connecting the GATEWAY nodes already placed by
 * {@link GatewayPopulator}.
 *
 * <h3>When to call</h3>
 * Call {@link #commit} immediately after {@link GatewayPopulator#populate} in the spawn
 * pipeline. If the graph already has edges, commit is a no-op (reload protection).
 *
 * <h3>Edge derivation</h3>
 * For layouts with two gateways (LINEAR, ROADSIDE, CHAIN), the committer finds the road
 * centerline that best connects the two gateway positions and records it as a single
 * {@link VillageRoadEdge.EdgeCharacter#THROUGH_VILLAGE} edge. Layouts with fewer than
 * two gateways or no suitable centerline produce no edges.
 */
public final class InternalRoadCommitter {

    private InternalRoadCommitter() {}

    // =========================================================================
    // Main entry point
    // =========================================================================

    /**
     * Creates internal road edges in the village road graph for the given village.
     * No-op if edges already exist (reload protection).
     *
     * @param level   server level for saved-data access
     * @param village the newly-spawned village
     * @param layout  the layout produced by the village planner
     */
    public static void commit(ServerLevel level, Village village, VillageLayout layout) {
        UUID villageId = village.getId();
        VillageRoadsSavedData roadsSaved = VillageRoadsSavedData.get(level);
        VillageRoadGraph graph = roadsSaved.getOrCreate(villageId);

        // Reload protection: skip if internal edges already present
        if (!graph.allEdges().isEmpty()) return;

        List<VillageEdgeDescriptor> descriptors = deriveEdgeDescriptors(graph, layout);
        if (descriptors.isEmpty()) return;

        for (VillageEdgeDescriptor desc : descriptors) {
            if (desc.path().isEmpty()) continue;
            UUID fromId = findOrCreateNode(graph, desc.fromPos());
            UUID toId   = findOrCreateNode(graph, desc.toPos());
            graph.addEdge(VillageRoadEdge.create(fromId, toId, desc.path(), desc.character()));
        }

        roadsSaved.setDirty();

        System.out.println("[InternalRoadCommitter] village '" + village.getName()
                + "' got " + descriptors.size() + " internal road edge(s)");
    }

    // =========================================================================
    // Descriptor derivation
    // =========================================================================

    /**
     * Derives edge descriptors from gate positions and road centerlines in the layout.
     * Returns a single THROUGH_VILLAGE edge for layouts with exactly two gateways
     * where a connecting centerline can be found.
     */
    static List<VillageEdgeDescriptor> deriveEdgeDescriptors(VillageRoadGraph graph,
                                                              VillageLayout layout) {
        List<VillageRoadNode> gateways = graph.gateways();
        if (gateways.size() < 2) return List.of();

        Collection<List<BlockPos>> allCenterlines = layout.getAllCenterlines();
        if (allCenterlines.isEmpty()) return List.of();

        // For two-gateway layouts use the primary and first side gateway
        VillageRoadNode primary = gateways.stream()
                .filter(g -> g.gatewayInfo()
                        .map(i -> i.role() == VillageRoadNode.GatewayRole.PRIMARY)
                        .orElse(false))
                .findFirst()
                .orElse(gateways.get(0));
        VillageRoadNode secondary = gateways.stream()
                .filter(g -> !g.nodeId().equals(primary.nodeId()))
                .findFirst()
                .orElse(null);

        if (secondary == null) return List.of();

        List<BlockPos> centerline = findConnectingCenterline(
                allCenterlines, primary.position(), secondary.position());
        if (centerline == null || centerline.isEmpty()) return List.of();

        // Orient the path so it starts at primary and ends at secondary
        BlockPos pathStart = centerline.get(0);
        BlockPos pathEnd   = centerline.get(centerline.size() - 1);
        boolean reversed = primary.position().distSqr(pathEnd)
                < primary.position().distSqr(pathStart);
        List<BlockPos> oriented;
        if (reversed) {
            oriented = new ArrayList<>(centerline);
            java.util.Collections.reverse(oriented);
        } else {
            oriented = centerline;
        }

        return List.of(new VillageEdgeDescriptor(
                primary.position(), secondary.position(),
                oriented,
                VillageRoadEdge.EdgeCharacter.THROUGH_VILLAGE));
    }

    /**
     * Finds the road centerline whose endpoints are collectively closest to the
     * two given gateway positions.  Longest centerline is preferred when scores
     * are equal (it is most likely the main through-road).
     */
    private static List<BlockPos> findConnectingCenterline(
            Collection<List<BlockPos>> centerlines,
            BlockPos posA, BlockPos posB) {
        List<BlockPos> best = null;
        double bestScore = Double.MAX_VALUE;
        int bestLength = 0;

        for (List<BlockPos> cl : centerlines) {
            if (cl.size() < 2) continue;
            BlockPos start = cl.get(0);
            BlockPos end   = cl.get(cl.size() - 1);
            // Minimum sum-of-squared-distances from path endpoints to the two gateway positions
            double scoreAB = start.distSqr(posA) + end.distSqr(posB);
            double scoreBA = start.distSqr(posB) + end.distSqr(posA);
            double score = Math.min(scoreAB, scoreBA);
            if (score < bestScore || (score == bestScore && cl.size() > bestLength)) {
                bestScore  = score;
                best       = cl;
                bestLength = cl.size();
            }
        }
        return best;
    }

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
}
