package tterrag1112.life_in_the_village.Village.Roads.Lifecycle;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadEdge;
import tterrag1112.life_in_the_village.Village.Roads.Graph.WorldRoadGraph;

import java.util.List;

/**
 * Phase 9 — scores how "well aligned" a candidate position is with the
 * existing road network. Used as one term in the larger village-placement
 * scoring pipeline.
 *
 * <h3>Score table</h3>
 * <ul>
 *   <li>Within {@value #GREAT_ROAD_NEAR}     blocks of any GREAT_ROAD: +30</li>
 *   <li>Within {@value #GREAT_ROAD_FAR}      blocks of any GREAT_ROAD: +15</li>
 *   <li>Within {@value #TRUNK_NEAR}          blocks of any TRUNK:      +20</li>
 *   <li>Within {@value #TRUNK_FAR}           blocks of any TRUNK:      +10</li>
 * </ul>
 * The contributions are additive but capped at {@value #MAX_SCORE}.
 *
 * <p>Searches the spatial index with a generous radius
 * ({@value #SEARCH_RADIUS}) so a single query covers all the bands. Only
 * non-dead edges count — dead roads don't pull settlement.
 */
public final class NetworkAlignmentScorer {

    private NetworkAlignmentScorer() {}

    public static final int GREAT_ROAD_NEAR = 500;
    public static final int GREAT_ROAD_FAR  = 1500;
    public static final int TRUNK_NEAR      = 300;
    public static final int TRUNK_FAR       = 800;
    public static final int SEARCH_RADIUS   = 1500;

    public static final float MAX_SCORE = 50f;

    /**
     * Computes the alignment score for {@code candidate} against {@code graph}.
     * Returns 0 if the graph is null or empty.
     */
    public static float networkAlignmentScore(BlockPos candidate, WorldRoadGraph graph) {
        if (graph == null) return 0f;

        List<RoadEdge> nearby = graph.edgesNear(candidate.getX(), candidate.getZ(), SEARCH_RADIUS);
        if (nearby.isEmpty()) return 0f;

        long bestGreatDistSq = Long.MAX_VALUE;
        long bestTrunkDistSq = Long.MAX_VALUE;

        for (RoadEdge edge : nearby) {
            if (edge.isDead()) continue;
            if (edge.getTier() != RoadEdge.EdgeTier.GREAT_ROAD
                    && edge.getTier() != RoadEdge.EdgeTier.TRUNK) continue;

            long distSq = closestBlockDistSq(edge, candidate);
            if (edge.getTier() == RoadEdge.EdgeTier.GREAT_ROAD) {
                if (distSq < bestGreatDistSq) bestGreatDistSq = distSq;
            } else {
                if (distSq < bestTrunkDistSq) bestTrunkDistSq = distSq;
            }
        }

        float score = 0f;
        if (bestGreatDistSq != Long.MAX_VALUE) {
            double dist = Math.sqrt(bestGreatDistSq);
            if (dist <= GREAT_ROAD_NEAR)      score += 30f;
            else if (dist <= GREAT_ROAD_FAR)  score += 15f;
        }
        if (bestTrunkDistSq != Long.MAX_VALUE) {
            double dist = Math.sqrt(bestTrunkDistSq);
            if (dist <= TRUNK_NEAR)           score += 20f;
            else if (dist <= TRUNK_FAR)       score += 10f;
        }
        return Math.min(MAX_SCORE, score);
    }

    /**
     * Returns the squared XZ distance from {@code candidate} to the closest
     * cell-path or block-path point of {@code edge}. Uses block path when
     * realised, falls back to cell path otherwise (so worldgen-time scoring
     * works against unrealised great roads).
     */
    private static long closestBlockDistSq(RoadEdge edge, BlockPos candidate) {
        long best = Long.MAX_VALUE;

        // Prefer the realised block path (more accurate).
        if (!edge.getBlockPath().isEmpty()) {
            for (BlockPos p : edge.getBlockPath()) {
                long dx = (long) p.getX() - candidate.getX();
                long dz = (long) p.getZ() - candidate.getZ();
                long d2 = dx * dx + dz * dz;
                if (d2 < best) best = d2;
            }
            return best;
        }

        // Fall back to cell-path centres.
        for (long cellKey : edge.getCellPath()) {
            BlockPos centre = tterrag1112.life_in_the_village.Village.Economy.Trade
                    .AtlasRouteRouter.cellKeyToBlockCenter(cellKey);
            long dx = (long) centre.getX() - candidate.getX();
            long dz = (long) centre.getZ() - candidate.getZ();
            long d2 = dx * dx + dz * dz;
            if (d2 < best) best = d2;
        }
        return best;
    }
}
