package tterrag1112.life_in_the_village.Village.Roads.Graph.Worldgen;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadEdge;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadNode;
import tterrag1112.life_in_the_village.Village.Roads.Graph.WorldRoadGraph;
import tterrag1112.life_in_the_village.World.Atlas.AtlasCell;

import java.util.*;

/**
 * Selects which GREAT_ROAD edges should receive Old Realm names (Phase 7b).
 *
 * <h3>Selection rule</h3>
 * The world is partitioned into 8 000 × 8 000 block regions. For each region
 * that contains at least one GREAT_ROAD edge, the "most significant" edge is
 * selected. Significance is determined by:
 * <ol>
 *   <li>Longest cell path (more cells = longer road = more significant)</li>
 *   <li>Tiebreaker: midpoint closest to the region centre</li>
 * </ol>
 * Regions with no great road produce no named road.
 *
 * <h3>Naming</h3>
 * Selected edges are assigned names in deterministic order (by midpoint
 * coordinates) from {@link OldRealmNamePool#selectName}. Duplicate names
 * cannot occur because the pool tracks used names across the assignment pass.
 */
public final class NamedRoadSelector {

    private NamedRoadSelector() {}

    static final int REGION_SIZE = 8_000;

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Scans all GREAT_ROAD edges in the graph and returns the IDs of edges that
     * should be named (at most one per 8 000×8 000 region).
     *
     * <p>Does NOT assign names — call {@link #nameSelectedEdges} afterwards.
     */
    public static List<UUID> selectEdgesToName(WorldRoadGraph graph) {
        // Bucket edges by region
        Map<Long, List<RoadEdge>> byRegion = new LinkedHashMap<>();

        for (RoadEdge edge : graph.allEdges()) {
            if (edge.getTier() != RoadEdge.EdgeTier.GREAT_ROAD) continue;
            List<Long> path = edge.getCellPath();
            if (path.isEmpty()) continue;

            int[] mid = midpointBlocks(path);
            long regionKey = regionKey(mid[0], mid[1]);
            byRegion.computeIfAbsent(regionKey, k -> new ArrayList<>()).add(edge);
        }

        // For each region, pick the most significant edge
        List<UUID> selected = new ArrayList<>();
        for (Map.Entry<Long, List<RoadEdge>> entry : byRegion.entrySet()) {
            long regionKey = entry.getKey();
            List<RoadEdge> candidates = entry.getValue();

            int[] regionCenter = regionCenter(regionKey);

            RoadEdge best = candidates.stream().max(
                    Comparator.comparingInt((RoadEdge e) -> e.getCellPath().size())
                            .thenComparingLong(e -> -distSqToCenter(e, regionCenter))
            ).orElse(null);

            if (best != null) selected.add(best.getEdgeId());
        }

        // Sort deterministically by edge midpoint so naming order is stable
        selected.sort(Comparator.comparingLong(id -> {
            RoadEdge e = graph.getEdge(id);
            if (e == null || e.getCellPath().isEmpty()) return 0L;
            int[] m = midpointBlocks(e.getCellPath());
            return ((long) m[0] << 32) | (m[1] & 0xFFFFFFFFL);
        }));

        return selected;
    }

    /**
     * Assigns Old Realm names to the given edges. Deterministic for a given
     * world seed and edge set.
     *
     * <p>Edges are named in the order provided; duplicate names are prevented
     * by the {@link OldRealmNamePool} via the {@code usedNames} set.
     */
    public static void nameSelectedEdges(WorldRoadGraph graph,
                                          List<UUID> edgeIds,
                                          long worldSeed) {
        Set<String> usedNames = new HashSet<>();

        for (UUID id : edgeIds) {
            RoadEdge edge = graph.getEdge(id);
            if (edge == null) continue;

            // Get endpoint positions for stable seeding
            RoadNode nodeA = graph.getNode(edge.getNodeAId());
            RoadNode nodeB = graph.getNode(edge.getNodeBId());
            BlockPos posA = nodeA != null ? nodeA.position() : BlockPos.ZERO;
            BlockPos posB = nodeB != null ? nodeB.position() : BlockPos.ZERO;

            String name = OldRealmNamePool.selectName(posA, posB, worldSeed, usedNames);
            edge.setRoadName(name);
        }
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    /** Returns [blockX, blockZ] of the midpoint cell of the given cell path. */
    static int[] midpointBlocks(List<Long> cellPath) {
        long midCell = cellPath.get(cellPath.size() / 2);
        int bx = (AtlasCell.unpackX(midCell) << AtlasCell.CELL_SHIFT) + AtlasCell.CELL_HALF;
        int bz = (AtlasCell.unpackZ(midCell) << AtlasCell.CELL_SHIFT) + AtlasCell.CELL_HALF;
        return new int[]{bx, bz};
    }

    /** Returns the region bucket key for a block position. */
    static long regionKey(int blockX, int blockZ) {
        int rx = Math.floorDiv(blockX, REGION_SIZE);
        int rz = Math.floorDiv(blockZ, REGION_SIZE);
        return ((long) rx << 32) | (rz & 0xFFFFFFFFL);
    }

    /** Returns [centerBlockX, centerBlockZ] for the region identified by {@code regionKey}. */
    static int[] regionCenter(long regionKey) {
        int rx = (int)(regionKey >> 32);
        int rz = (int)(regionKey & 0xFFFFFFFFL);
        return new int[]{rx * REGION_SIZE + REGION_SIZE / 2, rz * REGION_SIZE + REGION_SIZE / 2};
    }

    /** Squared distance from an edge's midpoint to the given region center. */
    private static long distSqToCenter(RoadEdge edge, int[] center) {
        int[] mid = midpointBlocks(edge.getCellPath());
        long dx = mid[0] - center[0];
        long dz = mid[1] - center[1];
        return dx * dx + dz * dz;
    }
}
