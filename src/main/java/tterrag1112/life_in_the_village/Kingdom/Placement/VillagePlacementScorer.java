// src/main/java/tterrag1112/life_in_the_village/Kingdom/Placement/VillagePlacementScorer.java
package tterrag1112.life_in_the_village.Kingdom.Placement;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Village.Roads.Graph.WorldRoadGraph;
import tterrag1112.life_in_the_village.Village.Roads.Lifecycle.NetworkAlignmentScorer;
import tterrag1112.life_in_the_village.Village.VillageTag;
import tterrag1112.life_in_the_village.Village.VillageTypeData;
import tterrag1112.life_in_the_village.World.Atlas.AtlasCell;
import tterrag1112.life_in_the_village.World.Atlas.BiomeCategory;
import tterrag1112.life_in_the_village.World.Atlas.WorldAtlas;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Scores atlas cells as candidate village sites. Used after
 * {@link VillageTypeMatcher} filters out ineligible cells.
 *
 * <h3>Score components</h3>
 * <ul>
 *   <li>Base buildability — flat, not steep, not water-adjacent if
 *       not needed. Reuses the same slope-and-category logic as
 *       {@code AtlasSiteSelector.scoreGeneral}.</li>
 *   <li>Tag fit bonuses — TRADE prefers road-dense areas, DEFENSIVE
 *       prefers elevated cells, AGRICULTURAL prefers large flat
 *       contiguous areas.</li>
 *   <li>Proximity penalty — soft, decays with distance from already
 *       placed villages. Two villages very close together are allowed
 *       but heavily penalised; at {@link #MIN_COMFORTABLE_SPACING}
 *       blocks the penalty is zero.</li>
 *   <li>Buildable-area estimate — atlas-only estimate of how much
 *       flat ground lies within the village's expected footprint.
 *       Reject candidates below a floor.</li>
 *   <li>Kingdom-origin connectivity — slight bonus for cells that
 *       are closer to the capital along traversable terrain.</li>
 * </ul>
 */
public final class VillagePlacementScorer {

    private VillagePlacementScorer() {}

    /** Distance (blocks) at which the proximity penalty drops to zero. */
    public static final int MIN_COMFORTABLE_SPACING = 500;

    /** Distance (blocks) below which the penalty is very harsh. */
    public static final int CROWDED_SPACING = 200;

    /**
     * Village footprint radius (blocks) used to estimate buildable area.
     * Roughly one ring of buildings around a town square.
     */
    private static final int FOOTPRINT_RADIUS_CELLS = 4;

    /** Minimum fraction of cells in the footprint that must be buildable. */
    private static final float MIN_BUILDABLE_FRACTION = 0.55f;

    /**
     * Phase 9 — weight applied to {@link NetworkAlignmentScorer#networkAlignmentScore}.
     * Cap = 50 × 0.4 = +20 to the final score. Tunable: drop to 0.2 if
     * non-capital villages cluster too tightly along great roads in playtest.
     */
    public static final double NETWORK_SCORE_WEIGHT = 0.4 / 50.0; // normalise 0-50 → 0-0.4

    // =========================================================================
    // Public API
    // =========================================================================

    public static double score(WorldAtlas atlas, AtlasCell cell,
                               VillageTypeData type,
                               BlockPos capitalOrigin,
                               List<BlockPos> placedPositions) {
        return score(atlas, cell, type, capitalOrigin, placedPositions, null, false);
    }

    /**
     * Phase 9 — overload that adds a network-alignment bonus when {@code graph}
     * is non-null and {@code isCapital} is {@code false}. Capitals are placed
     * by the kingdom seeder before any cultural connectors exist, and per the
     * Phase 9 spec the network bonus only applies to subsequent villages.
     */
    public static double score(WorldAtlas atlas, AtlasCell cell,
                               VillageTypeData type,
                               BlockPos capitalOrigin,
                               List<BlockPos> placedPositions,
                               @Nullable WorldRoadGraph graph,
                               boolean isCapital) {

        // Buildable-area hard gate — reject before scoring
        float waterFrac = waterFraction(atlas, cell);
        float waterCeiling = waterCeilingForType(type);
        if (waterFrac > waterCeiling) return Double.NEGATIVE_INFINITY;

        float steepFrac = steepFraction(atlas, cell);
        if (steepFrac > steepCeilingForType(type)) return Double.NEGATIVE_INFINITY;

        // Buildable-area hard gate — reject before scoring
        float buildFrac = buildableFraction(atlas, cell);
        if (buildFrac < MIN_BUILDABLE_FRACTION) return Double.NEGATIVE_INFINITY;

        double score = 1.0;
        score += buildFrac * 0.5;              // more flat area = better
        score -= cell.slope() * 0.03;

        // Base category preference — plains best, mountains worst unless
        // the type specifically wants mountains
        switch (cell.category()) {
            case PLAINS, SAVANNA -> score += 0.35;
            case FOREST          -> score += 0.20;
            case BEACH           -> score += 0.15;
            case DESERT, SNOWY, JUNGLE -> score += 0.05;
            case SWAMP           -> score -= 0.15;
            case MOUNTAIN        -> score -= 0.40;
            default              -> {}
        }
        if (cell.isCoast())    score += 0.15;
        if (cell.isRiverAdj()) score += 0.20;

        // Tag-driven bonuses
        var tags = type.getTags();
        if (tags.contains(VillageTag.TRADE)) {
            int roads = atlas.countRoadsNear(cell.blockCenterX(), cell.blockCenterZ(), 256);
            score += Math.min(1.5, roads * 0.25);
        }
        if (tags.contains(VillageTag.DEFENSIVE)) {
            score += Math.min(0.6, cell.slope() * 0.03); // high ground wanted
        }
        if (tags.contains(VillageTag.AGRICULTURAL)) {
            score += buildFrac * 0.4;            // extra weight on open land
        }
        if (tags.contains(VillageTag.MOUNTAIN) && cell.category() == BiomeCategory.MOUNTAIN) {
            score += 0.4;
        }
        if (tags.contains(VillageTag.FOREST) && cell.category() == BiomeCategory.FOREST) {
            score += 0.3;
        }
        if (tags.contains(VillageTag.REMOTE)) {
            // Prefer cells far from the capital
            double distCapSq = cell.blockCenterX() * 1.0 - capitalOrigin.getX();
            double dz = cell.blockCenterZ() - capitalOrigin.getZ();
            double capDist = Math.sqrt(distCapSq * distCapSq + dz * dz);
            score += Math.min(0.8, capDist / 1500.0);
        }

        // Proximity penalty — soft, quadratic falloff
        score -= proximityPenalty(cell, placedPositions);

        // Mild connectivity bonus — closer to capital is slightly better
        // for non-remote types, helping cluster the kingdom
        if (!tags.contains(VillageTag.REMOTE)) {
            double dx = cell.blockCenterX() - capitalOrigin.getX();
            double dz = cell.blockCenterZ() - capitalOrigin.getZ();
            double dist = Math.sqrt(dx * dx + dz * dz);
            // Peak bonus at ~400 blocks, decays on either side
            score += 0.3 * Math.exp(-Math.pow((dist - 400) / 400, 2));
        }

        // Phase 9 — network alignment bonus (subsequent villages only).
        if (graph != null && !isCapital) {
            BlockPos candidate = new BlockPos(
                    cell.blockCenterX(), cell.centerY(), cell.blockCenterZ());
            float networkScore = NetworkAlignmentScorer.networkAlignmentScore(candidate, graph);
            score += networkScore * NETWORK_SCORE_WEIGHT;
        }

        return score;
    }

    // =========================================================================
    // Pass-1 scoring (loose thresholds — candidates forwarded to deep inspection)
    // =========================================================================

    /**
     * Lightweight scorer with relaxed water/steep/buildable thresholds.
     * Used by {@link ClaimVillagePlacer} to collect the top-N candidates
     * before running {@link DeepTerrainInspector} on them. The full
     * {@link #score} method is then applied only to deep-inspection survivors.
     *
     * <p>Returns {@link Double#NEGATIVE_INFINITY} only for clearly hopeless
     * cells (> 40% water, > 40% steep, < 25% buildable, or proximity reject).
     */
    static double quickScore(WorldAtlas atlas, AtlasCell cell,
                             VillageTypeData type,
                             BlockPos capitalOrigin,
                             List<BlockPos> placedPositions) {
        float waterFrac = waterFraction(atlas, cell);
        if (waterFrac > 0.40f) return Double.NEGATIVE_INFINITY;

        float steepFrac = steepFraction(atlas, cell);
        if (steepFrac > 0.40f) return Double.NEGATIVE_INFINITY;

        float buildFrac = buildableFraction(atlas, cell);
        if (buildFrac < 0.25f) return Double.NEGATIVE_INFINITY;

        // Compact score matching TerrainAnalyzer's suitability formula at cell resolution.
        // Tag-specific bonuses are omitted here — they run in Pass 3 via the full scorer.
        double s = buildFrac - waterFrac * 2.0 - steepFrac * 0.3;
        s -= proximityPenalty(cell, placedPositions);
        return s;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Fraction of cells within the footprint that are buildable AND not
     * predominantly water. Separate from water fraction — a cell can be
     * non-water but still unbuildable (e.g. steep mountain), and we want
     * to count those separately so tag-based tolerances work correctly.
     */
    private static float buildableFraction(WorldAtlas atlas, AtlasCell centre) {
        int ok = 0, total = 0;
        int r = FOOTPRINT_RADIUS_CELLS;
        int cx = centre.cellX(), cz = centre.cellZ();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                AtlasCell c = atlas.getCellByCoord(cx + dx, cz + dz);
                if (c == null) continue;
                total++;
                if (c.isBuildable() && !c.isSteep() && !isWaterLike(c)) ok++;
            }
        }
        if (total == 0) return 0f;
        return (float) ok / total;
    }

    /**
     * Fraction of cells within the footprint that are water-dominated.
     * Counts OCEAN, RIVER, and river-adjacent cells with no land category.
     * A cell flagged HAS_RIVER or HAS_OCEAN is predominantly water even
     * if the centre height is above sea level (shoreline cells usually
     * resolve to BEACH, which is NOT water).
     */
    private static float waterFraction(WorldAtlas atlas, AtlasCell centre) {
        int water = 0, total = 0;
        int r = FOOTPRINT_RADIUS_CELLS;
        int cx = centre.cellX(), cz = centre.cellZ();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                AtlasCell c = atlas.getCellByCoord(cx + dx, cz + dz);
                if (c == null) continue;
                total++;
                if (isWaterLike(c)) water++;
            }
        }
        if (total == 0) return 0f;
        return (float) water / total;
    }

    /** True for cells whose block-level surface is predominantly water. */
    private static boolean isWaterLike(AtlasCell c) {
        return c.category() == BiomeCategory.OCEAN
                || c.category() == BiomeCategory.RIVER
                || c.has(AtlasCell.FLAG_HAS_OCEAN)
                || c.has(AtlasCell.FLAG_HAS_RIVER);
    }

    private static double proximityPenalty(AtlasCell cell, List<BlockPos> placed) {
        if (placed.isEmpty()) return 0.0;
        double worst = 0.0;
        for (BlockPos p : placed) {
            double dx = cell.blockCenterX() - p.getX();
            double dz = cell.blockCenterZ() - p.getZ();
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist >= MIN_COMFORTABLE_SPACING) continue;
            if (dist <= CROWDED_SPACING) {
                worst = Math.max(worst, 5.0); // effectively a reject
            } else {
                // Linear falloff between CROWDED and MIN_COMFORTABLE
                double t = (MIN_COMFORTABLE_SPACING - dist)
                        / (double)(MIN_COMFORTABLE_SPACING - CROWDED_SPACING);
                worst = Math.max(worst, 1.5 * t);
            }
        }
        return worst;
    }
    /**
     * Maximum footprint water fraction tolerated for a given type.
     * Coastal and riverside types accept more water (the village is
     * meant to hug the shoreline), mountain and agricultural types
     * want dry ground.
     */
    private static float waterCeilingForType(VillageTypeData type) {
        var tags = type.getTags();
        if (tags.contains(VillageTag.COASTAL) || tags.contains(VillageTag.RIVERSIDE)) {
            return 0.35f; // shoreline villages still need >65% land
        }
        if (tags.contains(VillageTag.AGRICULTURAL)
                || tags.contains(VillageTag.MOUNTAIN)) {
            return 0.10f; // strict: need almost entirely dry land
        }
        return 0.20f; // generalist default — at most 20% water
    }
    private static float steepFraction(WorldAtlas atlas, AtlasCell centre) {
        int steep = 0, total = 0;
        int r = FOOTPRINT_RADIUS_CELLS;
        int cx = centre.cellX(), cz = centre.cellZ();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                AtlasCell c = atlas.getCellByCoord(cx + dx, cz + dz);
                if (c == null) continue;
                total++;
                if (c.isSteep() || c.slope() > 8) steep++;
            }
        }
        if (total == 0) return 0f;
        return (float) steep / total;
    }

    private static float steepCeilingForType(VillageTypeData type) {
        var tags = type.getTags();
        if (tags.contains(VillageTag.MOUNTAIN) || tags.contains(VillageTag.DEFENSIVE)) {
            return 0.50f; // mountain villages embrace rough terrain
        }
        if (tags.contains(VillageTag.AGRICULTURAL)) {
            return 0.05f; // farms need flat ground, period
        }
        return 0.20f; // generalist default
    }
}