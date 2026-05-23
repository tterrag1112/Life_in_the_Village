package tterrag1112.life_in_the_village.Village.Farms;

import tterrag1112.life_in_the_village.Village.Planning.V2.Layer1.BlockCategory;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer1.Cell;

/**
 * Detour A — composite arable score, extracted verbatim from
 * {@code FarmSectorPlanner.arableScore} so the new
 * {@code FloodFillRegionClaim} can consume it without depending on
 * the soon-to-be-deleted sector planner.
 *
 * <p>{@link #score} returns 0..1 for OPEN/SHORE cells under
 * {@link #MAX_INTERIOR_SLOPE}, weighted toward water/forest
 * proximity but biased so a flat plains cell with no water nearby
 * still clears {@link #DEFAULT_THRESHOLD} (per the B2.9 rebalance
 * — pre-B2.9 plains rejected every candidate).
 *
 * <p>Pure function: same {@code (Cell, cellSize)} inputs produce
 * the same score. Threshold is a caller decision.
 */
public final class ArableScoring {

    /** Threshold at which a cell is considered "arable enough" for
     *  the flood-fill claim. Matches the original sector planner's
     *  cut so existing balance survives extraction. */
    public static final double DEFAULT_THRESHOLD = 0.55;

    /** Slope cap (degrees-ish, expressed as the FeatureMap's
     *  {@code localSlope} unit which is max-delta over 8 neighbours). */
    public static final int MAX_INTERIOR_SLOPE = 3;

    private ArableScoring() {}

    public static double score(Cell cell, int cellSize) {
        if (cell == null) return 0.0;
        BlockCategory cat = cell.category();
        if (cat != BlockCategory.OPEN && cat != BlockCategory.SHORE) return 0.0;
        if (cell.localSlope() > MAX_INTERIOR_SLOPE) return 0.0;
        double slopeScore  = Math.max(0.0, 1.0 - cell.localSlope() / 4.0);
        double waterScore  = bfsScore(cell.distToWater(),  cellSize, 24);
        double forestScore = bfsScore(cell.distToForest(), cellSize, 32);
        double openBase    = 0.55;
        return Math.min(1.0,
                openBase + 0.25 * slopeScore + 0.15 * waterScore + 0.05 * forestScore);
    }

    private static double bfsScore(int distCells, int cellSize, int falloffBlocks) {
        if (distCells == Integer.MAX_VALUE) return 0.0;
        double db = distCells * (double) cellSize;
        return Math.max(0.0, 1.0 - db / Math.max(1, falloffBlocks));
    }
}
