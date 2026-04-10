package tterrag1112.life_in_the_village.World.Atlas;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

/**
 * Finds and scores buildable atlas cells for village placement.
 *
 * <h3>Why this exists</h3>
 * Replaces the chunk-loading probe loops in {@code KingdomSpawner} and
 * {@code WorldgenKingdomSeeder}. Instead of force-loading 9×9 chunk
 * regions to call {@code getHeight} as a probe, callers query the
 * already-sampled atlas. Site selection becomes a pure in-memory
 * search over a few hundred cells — orders of magnitude faster than
 * the chunk-loading approach.
 *
 * <h3>Scoring</h3>
 * {@link #scoreGeneral} returns a suitability score for general village
 * placement. Higher is better. {@link Double#NEGATIVE_INFINITY} means
 * the cell is unusable (water, void, too steep). Type-specific scoring
 * is deferred to Phase 4 (shape rule system) — for now all village
 * types use the general score.
 */
public final class AtlasSiteSelector {

    private AtlasSiteSelector() {}

    // =========================================================================
    // Scoring
    // =========================================================================

    /**
     * General buildability score for any village type.
     * Returns NEGATIVE_INFINITY for unusable cells.
     */
    public static double scoreGeneral(AtlasCell cell) {
        if (!cell.isBuildable()) return Double.NEGATIVE_INFINITY;
        if (cell.isSteep())      return Double.NEGATIVE_INFINITY;

        double score = 1.0;


        // Slope penalty: every block of slope costs 0.03
        score -= cell.slope() * 0.03;

        // Category preference
        switch (cell.category()) {
            case PLAINS                 -> score += 0.40;
            case FOREST                 -> score += 0.20;
            case SAVANNA                -> score += 0.15;
            case BEACH                  -> score += 0.15;
            case DESERT, SNOWY, JUNGLE  -> score += 0.05;
            case SWAMP                  -> score -= 0.20;
            case MOUNTAIN               -> score -= 0.50;
            default                     -> {}
        }

        // Adjacency bonuses — water access is a soft preference
        if (cell.isCoast())    score += 0.15;
        if (cell.isRiverAdj()) score += 0.20;

        return score;
    }

    // =========================================================================
    // Search
    // =========================================================================

    /**
     * Returns the best-scoring atlas cell within {@code radius} blocks
     * of {@code (centerX, centerZ)} that passes the filter predicate.
     *
     * @param atlas    the world atlas to search
     * @param centerX  block X to search around
     * @param centerZ  block Z to search around
     * @param radius   search radius in blocks
     * @param filter   additional rejection predicate (e.g. min distance to neighbours)
     */
    public static Optional<AtlasCell> findBest(
            WorldAtlas atlas, int centerX, int centerZ, int radius,
            Predicate<AtlasCell> filter) {

        List<AtlasCell> candidates =
                atlas.cellsWithinRadius(centerX, centerZ, radius);

        AtlasCell best = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (AtlasCell cell : candidates) {
            if (!filter.test(cell)) continue;
            double s = scoreGeneral(cell);
            if (s > bestScore) {
                bestScore = s;
                best = cell;
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * Returns the best-scoring atlas cell in an angular wedge.
     * Used by kingdom placement to find a village position at a given
     * direction and distance from the kingdom centre, with some angular
     * tolerance to avoid getting stuck on bad terrain right on the line.
     *
     * @param atlas         the world atlas
     * @param origin        the kingdom centre
     * @param angleRad      preferred direction in radians
     * @param spreadRad     half-width of the angular wedge to search
     * @param minRadius     minimum distance from origin
     * @param maxRadius     maximum distance from origin
     * @param filter        additional rejection predicate
     */
    public static Optional<AtlasCell> findBestInWedge(
            WorldAtlas atlas, int originX, int originZ,
            double angleRad, double spreadRad,
            int minRadius, int maxRadius,
            Predicate<AtlasCell> filter) {

        // Search box covers the worst-case wedge bounds
        int searchRadius = maxRadius + AtlasCell.CELL_SIZE;
        List<AtlasCell> candidates =
                atlas.cellsWithinRadius(originX, originZ, searchRadius);

        int minSq = minRadius * minRadius;
        int maxSq = maxRadius * maxRadius;
        double cosSpread = Math.cos(spreadRad);
        double tx = Math.cos(angleRad);
        double tz = Math.sin(angleRad);

        AtlasCell best = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (AtlasCell cell : candidates) {
            int dx = cell.blockCenterX() - originX;
            int dz = cell.blockCenterZ() - originZ;
            int distSq = dx * dx + dz * dz;
            if (distSq < minSq || distSq > maxSq) continue;

            // Angular check via dot product of unit vectors
            double dist = Math.sqrt(distSq);
            double dot = (dx / dist) * tx + (dz / dist) * tz;
            if (dot < cosSpread) continue;

            if (!filter.test(cell)) continue;
            double s = scoreGeneral(cell);
            if (s > bestScore) {
                bestScore = s;
                best = cell;
            }
        }
        return Optional.ofNullable(best);
    }
    /**
     * Returns the highest-scoring atlas cell in an angular wedge
     * centred on a point. Unlike {@link #findBestInWedge}, which
     * returns the first cell matching a filter, this method evaluates
     * a scoring function on every cell in the wedge and returns the
     * one with the highest score.
     *
     * <h3>Use case</h3>
     * Trade-hub village seeding scores candidates by how many existing
     * roads pass nearby — see {@code KingdomSpawner.tradeHubRouteBonus}.
     * Other village types can use this to express any "prefer cells
     * with property X" rule without needing a separate site selector.
     *
     * <p>Cells failing the {@code filter} predicate are skipped before
     * scoring, so callers can combine hard constraints (must be
     * buildable) with soft preferences (prefer to be near roads).
     *
     * @param atlas      world atlas
     * @param originX    centre block X
     * @param originZ    centre block Z
     * @param angleRad   centre angle in radians
     * @param spreadRad  half-width of the wedge in radians
     * @param minRadius  minimum search distance in blocks
     * @param maxRadius  maximum search distance in blocks
     * @param filter     hard constraint — cells failing this are skipped
     * @param scorer     scoring function — higher is better
     * @return the highest-scoring cell, or empty if none qualify
     */
    public static Optional<AtlasCell> findBestScoredInWedge(
            WorldAtlas atlas,
            int originX, int originZ,
            double angleRad, double spreadRad,
            int minRadius, int maxRadius,
            Predicate<AtlasCell> filter,
            ToDoubleFunction<AtlasCell> scorer) {

        AtlasCell best = null;
        double bestScore = -Double.MAX_VALUE;

        // Walk every cell in the bounding rectangle of the wedge.
        // The atlas is sparse so we iterate only filled cells via
        // cellsWithinRadius.
        List<AtlasCell> candidates = atlas.cellsWithinRadius(
                originX, originZ, maxRadius);

        for (AtlasCell cell : candidates) {
            int dx = cell.blockCenterX() - originX;
            int dz = cell.blockCenterZ() - originZ;

            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist < minRadius || dist > maxRadius) continue;

            double cellAngle = Math.atan2(dz, dx);
            double angleDiff = Math.abs(normalizeAngle(cellAngle - angleRad));
            if (angleDiff > spreadRad) continue;

            if (!filter.test(cell)) continue;

            double score = scorer.applyAsDouble(cell);
            if (score > bestScore) {
                bestScore = score;
                best = cell;
            }
        }

        return Optional.ofNullable(best);
    }

    /** Normalises an angle to [-π, π]. */
    private static double normalizeAngle(double a) {
        while (a > Math.PI)  a -= 2 * Math.PI;
        while (a < -Math.PI) a += 2 * Math.PI;
        return a;
    }
}