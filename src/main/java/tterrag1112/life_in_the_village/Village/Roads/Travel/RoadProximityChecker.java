package tterrag1112.life_in_the_village.Village.Roads.Travel;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadEdge;
import tterrag1112.life_in_the_village.Village.Roads.Graph.WorldRoadGraph;

import java.util.List;
import java.util.Optional;

/**
 * Precise in-depth check: is a given block position within the safety
 * corridor of any maintained road?
 *
 * <h3>Corridor definition</h3>
 * The corridor is measured as 2D Chebyshev distance (i.e. both X and Z
 * within the width threshold) from any block in the edge's realized
 * {@link RoadEdge#getBlockPath()}. Y is ignored — roads slope with terrain
 * and the suppression cylinder reaches straight up and down.
 *
 * <h3>Corridor widths per tier</h3>
 * <pre>
 * GREAT_ROAD  12 blocks
 * TRUNK        8 blocks
 * CONNECTOR    5 blocks
 * LOCAL        3 blocks
 * </pre>
 *
 * <h3>Maintenance threshold</h3>
 * An edge is "maintained" when {@code maintenance >= 30} (bands 1–4 in the
 * Phase-6b overgrowth system). Below 30 the road is visually overgrown with
 * fallen logs and saplings — wild things have reclaimed it, and so has spawn
 * suppression. GREAT_ROAD edges are always treated as maintained (invariant 3).
 *
 * <h3>Performance</h3>
 * Call only after the {@link RoadProximityCache} confirms the chunk might
 * contain a road. The spatial pre-filter ({@link WorldRoadGraph#edgesNear})
 * limits candidates to edges whose cell path touches the search area; the
 * Chebyshev check per block is cheap (two subtractions + two comparisons).
 */
public final class RoadProximityChecker {

    private RoadProximityChecker() {}

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Returns the tier of the highest-priority maintained road whose safety
     * corridor contains {@code pos}, or empty if no qualifying road is found.
     *
     * @param graph        road graph for the dimension
     * @param pos          the spawn position to test
     * @param searchRadius spatial pre-filter radius (use 16 as default;
     *                     must be ≥ max corridor width = 12)
     */
    public static Optional<RoadEdge.EdgeTier> nearestMaintainedRoadTier(
            WorldRoadGraph graph, BlockPos pos, int searchRadius) {

        int x = pos.getX();
        int z = pos.getZ();
        RoadEdge.EdgeTier best = null;

        List<RoadEdge> candidates = graph.edgesNear(x, z, searchRadius);
        for (RoadEdge edge : candidates) {
            if (edge.getTier() != RoadEdge.EdgeTier.GREAT_ROAD
                    && edge.getMaintenance() < 30) continue;
            if (!edge.isRealized() || edge.getBlockPath().isEmpty()) continue;

            int cw = corridorWidth(edge.getTier());

            boolean inCorridor = false;
            for (BlockPos bp : edge.getBlockPath()) {
                if (Math.abs(bp.getX() - x) <= cw && Math.abs(bp.getZ() - z) <= cw) {
                    inCorridor = true;
                    break;
                }
            }

            if (inCorridor) {
                if (best == null || tierPriority(edge.getTier()) > tierPriority(best)) {
                    best = edge.getTier();
                    if (best == RoadEdge.EdgeTier.GREAT_ROAD) break; // can't do better
                }
            }
        }

        return Optional.ofNullable(best);
    }

    // =========================================================================
    // Tier tables
    // =========================================================================

    /** Chebyshev corridor half-width (blocks) around the road surface. */
    public static int corridorWidth(RoadEdge.EdgeTier tier) {
        return switch (tier) {
            case GREAT_ROAD -> 12;
            case TRUNK      ->  8;
            case CONNECTOR  ->  5;
            case LOCAL      ->  3;
            case SEA -> 0;
        };
    }

    /** Ordering for "best tier wins": GREAT_ROAD=3, LOCAL=0. */
    private static int tierPriority(RoadEdge.EdgeTier tier) {
        return switch (tier) {
            case GREAT_ROAD -> 3;
            case TRUNK      -> 2;
            case CONNECTOR  -> 1;
            case LOCAL      -> 0;
            case SEA -> 0;
        };
    }
}
