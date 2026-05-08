package tterrag1112.life_in_the_village.Village.Decoration.Parks;

import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tterrag1112.life_in_the_village.Cultures.Culture;
import tterrag1112.life_in_the_village.Cultures.CultureBundles;
import tterrag1112.life_in_the_village.Village.Decoration.VillageSizeTier;
import tterrag1112.life_in_the_village.Village.Planning.V2.Inclination;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer1.BlockCategory;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer1.Cell;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer1.V2FeatureMap;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.Footprint;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.PlacedBuilding;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * B2.4 — Layer 4 post-pass that scans the {@link V2FeatureMap} for
 * leftover cells outside placed building footprints, scores them
 * for park-suitability, clusters high scorers into rectangular
 * candidate areas, and picks the top {@link GardenStyle} fits.
 *
 * <p>Per the user's "natural-feature-first" direction, the
 * scoring rewards cells with adjacent forests, water, and gentle
 * slopes. View-vista is intentionally skipped for B2.4 (the
 * {@code FeatureMap} doesn't expose a per-cell vista signal —
 * future work).</p>
 *
 * <p>Park count is the per-tier hard cap scaled by the culture's
 * {@code parkPriority}; doc 09's tier model + the prompt's hybrid
 * direction give:</p>
 * <ul>
 *   <li>HAMLET — always 0.</li>
 *   <li>VILLAGE — 0 or 1, weighted by priority.</li>
 *   <li>TOWN — 1.</li>
 *   <li>CITY — 1..3, scaled by priority.</li>
 * </ul>
 *
 * <p>Determinism: scoring and clustering are pure functions of the
 * planner inputs; the only RNG is a single sample for the
 * VILLAGE-tier "0 or 1" decision, taken from a salted derivative
 * of the planner's seed so cross-session determinism holds.</p>
 */
public final class ParkCandidateFinder {

    private static final Logger LOGGER = LoggerFactory.getLogger(ParkCandidateFinder.class);

    /** Cells with this score or higher count as park-candidate seeds. */
    private static final double SEED_SCORE_THRESHOLD = 0.55;
    /** Cells with this score or higher count for cluster expansion
     *  around an existing seed. Lower than the seed threshold so the
     *  cluster grows past the immediate hot core. */
    private static final double EXPANSION_SCORE_THRESHOLD = 0.35;
    /** Cap on cluster diameter (blocks) — keeps a single park area
     *  from swallowing the whole map even with very-cohesive
     *  vegetation. */
    private static final int MAX_CLUSTER_DIAMETER = 32;
    /** Buffer block-count between a candidate area and the nearest
     *  building footprint. */
    public static final int BUILDING_BUFFER = 2;

    private static final long PARK_RNG_SALT = 0x504152_4B5F4B33L;

    private ParkCandidateFinder() {}

    /**
     * Runs the candidate scan + style-selection. Returns the
     * reserved {@link GardenPlot} list for the village; the V2
     * spawn adapter persists each plot to {@code VillageSavedData}
     * once the village id is known and renders them post-decoration.
     */
    public static List<GardenPlot> find(V2FeatureMap fmap,
                                        List<PlacedBuilding> placed,
                                        Culture culture,
                                        Inclination inclination,
                                        VillageSizeTier tier,
                                        UUID villageId,
                                        long seed,
                                        long createdTick) {
        if (fmap == null || tier == null) return List.of();
        int cap = tierCap(tier);
        if (cap == 0) return List.of();
        double priority = priorityOf(culture);
        Random rng = new Random(seed ^ PARK_RNG_SALT);
        int target = scaledCount(tier, priority, rng);
        if (target == 0) return List.of();

        // ── Score every cell, masking those inside or adjacent to
        // a placed footprint or its frontage strip. ─────────────────
        int gridSize = fmap.gridSize();
        double[][] scores = new double[gridSize][gridSize];
        boolean[][] occupied = buildOccupancyMask(fmap, placed);
        for (int i = 0; i < gridSize; i++) {
            for (int j = 0; j < gridSize; j++) {
                if (occupied[i][j]) {
                    scores[i][j] = 0.0;
                    continue;
                }
                Cell cell = fmap.cell(i, j);
                scores[i][j] = scoreCell(cell, fmap.cellSize());
            }
        }

        // ── Greedy cluster: for each unvisited seed (score >=
        // SEED_SCORE_THRESHOLD), grow a bbox over neighbouring high-
        // scoring cells until growth would exceed MAX_CLUSTER_DIAMETER
        // or step into occupied / low-score cells. ──────────────────
        boolean[][] visited = new boolean[gridSize][gridSize];
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < gridSize; i++) {
            for (int j = 0; j < gridSize; j++) {
                if (visited[i][j] || occupied[i][j]) continue;
                if (scores[i][j] < SEED_SCORE_THRESHOLD) continue;
                Candidate c = growCluster(fmap, scores, occupied, visited, i, j);
                if (c != null) candidates.add(c);
            }
        }
        if (candidates.isEmpty()) {
            LOGGER.debug("ParkCandidateFinder: no seed cells passed threshold "
                    + "(target={} cap={} priority={}); 0 plots reserved",
                    target, cap, priority);
            return List.of();
        }

        // ── Style selection per candidate + final ranking. ───────────
        List<RankedPlot> ranked = new ArrayList<>();
        for (Candidate c : candidates) {
            StyleFit fit = pickBestStyle(c, culture, inclination);
            if (fit == null) continue;
            // Combined rank = candidate area's natural score × style
            // affinity score. Higher = better.
            double combined = c.areaScore() * fit.score();
            ranked.add(new RankedPlot(c, fit.style(), combined));
        }
        if (ranked.isEmpty()) return List.of();
        ranked.sort(Comparator.<RankedPlot>comparingDouble(r -> -r.score));

        // ── Take top N (= target), reserving non-overlapping plots. ──
        List<GardenPlot> reserved = new ArrayList<>();
        for (RankedPlot r : ranked) {
            if (reserved.size() >= target) break;
            if (overlapsExisting(r.candidate.bounds, reserved)) continue;
            UUID plotId = UUID.nameUUIDFromBytes(
                    (villageId + "/park/" + reserved.size())
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            reserved.add(new GardenPlot(
                    plotId, villageId, r.candidate.bounds,
                    r.style, r.style.preserveBias(),
                    List.of(), List.of(), createdTick));
        }
        LOGGER.info("ParkCandidateFinder: reserved {} parks (target={} tier={} "
                + "priority={}) from {} candidates",
                reserved.size(), target, tier,
                String.format(java.util.Locale.ROOT, "%.2f", priority),
                candidates.size());
        return reserved;
    }

    // ── Per-tier cap & priority sampling ──────────────────────────────

    /** Hard cap per viability tier (no scaling beyond this). */
    public static int tierCap(VillageSizeTier tier) {
        return switch (tier) {
            case HAMLET  -> 0;
            case VILLAGE -> 1;
            case TOWN    -> 1;
            case CITY    -> 3;
        };
    }

    /** Effective park count for a tier given the culture's
     *  {@code parkPriority}. Honours the per-tier minimum. */
    public static int scaledCount(VillageSizeTier tier, double priority,
                                  Random rng) {
        int cap = tierCap(tier);
        if (cap <= 0) return 0;
        return switch (tier) {
            case HAMLET  -> 0;
            case VILLAGE -> rng.nextDouble() < priority ? 1 : 0;
            case TOWN    -> 1;  // floor=1, cap=1
            case CITY    -> Math.max(1,
                    (int) Math.ceil(cap * Math.max(0.0, Math.min(1.0, priority))));
        };
    }

    private static double priorityOf(Culture culture) {
        if (culture == null) return 0.5;
        CultureBundles.CulturePlanningBias bias = culture.planningBias();
        return bias != null ? bias.parkPriority() : 0.5;
    }

    // ── Cell scoring ──────────────────────────────────────────────────

    /** Composite score in [0..1] mixing natural-interest signals.
     *  Cells that are out of bounds or non-buildable score 0. */
    static double scoreCell(Cell cell, int cellSize) {
        if (cell == null) return 0.0;
        BlockCategory cat = cell.category();
        // Parks need walkable ground; water + stone cores not eligible
        // unless they're shore-adjacent (handled via distToWater).
        if (cat != BlockCategory.OPEN && cat != BlockCategory.SHORE
                && cat != BlockCategory.FOREST) {
            return 0.0;
        }
        // Slope gentleness: 0 slope = full score, 4+ blocks = 0.
        double slopeScore = Math.max(0.0,
                1.0 - cell.localSlope() / 4.0);
        // Forest proximity: BFS distance, in cells. Closer = higher.
        // distToForest of 0 (forest cell) → 1.0; 8+ cells → ~0.
        double forestScore = bfsDistToScore(cell.distToForest(), cellSize, 12);
        // Water proximity: same shape.
        double waterScore = bfsDistToScore(cell.distToWater(), cellSize, 16);
        // FOREST cells get a category boost — preserved-tree parks
        // (SACRED_GROVE) live and die on these.
        double categoryBonus = cat == BlockCategory.FOREST ? 0.15 : 0.0;
        return Math.min(1.0,
                0.45 * slopeScore + 0.25 * forestScore + 0.15 * waterScore + categoryBonus);
    }

    private static double bfsDistToScore(int distCells, int cellSize, int falloffBlocks) {
        if (distCells == Integer.MAX_VALUE) return 0.0;
        double db = distCells * (double) cellSize;
        return Math.max(0.0, 1.0 - db / Math.max(1, falloffBlocks));
    }

    // ── Occupancy mask ────────────────────────────────────────────────

    private static boolean[][] buildOccupancyMask(V2FeatureMap fmap,
                                                  List<PlacedBuilding> placed) {
        int n = fmap.gridSize();
        boolean[][] occ = new boolean[n][n];
        if (placed == null || placed.isEmpty()) return occ;
        for (PlacedBuilding pb : placed) {
            Footprint fp = pb.footprint();
            BlockPos centre = pb.centre();
            int hw = fp.width() / 2 + BUILDING_BUFFER;
            int hl = fp.length() / 2 + BUILDING_BUFFER;
            int minX = centre.getX() - hw;
            int maxX = centre.getX() + hw;
            int minZ = centre.getZ() - hl;
            int maxZ = centre.getZ() + hl;
            int minI = fmap.worldXToCellI(minX);
            int maxI = fmap.worldXToCellI(maxX);
            int minJ = fmap.worldZToCellJ(minZ);
            int maxJ = fmap.worldZToCellJ(maxZ);
            for (int i = clamp(minI, 0, n - 1); i <= clamp(maxI, 0, n - 1); i++) {
                for (int j = clamp(minJ, 0, n - 1); j <= clamp(maxJ, 0, n - 1); j++) {
                    occ[i][j] = true;
                }
            }
        }
        return occ;
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    // ── Cluster growth ────────────────────────────────────────────────

    /** Greedy rectangular grow: starts at the seed cell and expands
     *  N/S/E/W as long as the new edge averages ≥
     *  {@link #EXPANSION_SCORE_THRESHOLD} and stays within the
     *  diameter cap. */
    private static Candidate growCluster(V2FeatureMap fmap, double[][] scores,
                                         boolean[][] occupied, boolean[][] visited,
                                         int seedI, int seedJ) {
        int n = scores.length;
        int minI = seedI, maxI = seedI;
        int minJ = seedJ, maxJ = seedJ;
        int cellSize = fmap.cellSize();
        int diameterCells = MAX_CLUSTER_DIAMETER / Math.max(1, cellSize);

        boolean grew = true;
        while (grew) {
            grew = false;
            // Try each direction; expand if the new edge averages
            // above the expansion threshold.
            if (maxI - minI < diameterCells - 1
                    && tryExpand(scores, occupied, minI, maxI + 1,
                            minJ, maxJ, /*axisI*/ true)) {
                maxI++; grew = true;
            }
            if (maxI - minI < diameterCells - 1
                    && minI > 0
                    && tryExpand(scores, occupied, minI - 1, minI - 1,
                            minJ, maxJ, /*axisI*/ true)) {
                minI--; grew = true;
            }
            if (maxJ - minJ < diameterCells - 1
                    && tryExpand(scores, occupied, minI, maxI,
                            minJ, maxJ + 1, /*axisI*/ false)) {
                maxJ++; grew = true;
            }
            if (maxJ - minJ < diameterCells - 1
                    && minJ > 0
                    && tryExpand(scores, occupied, minI, maxI,
                            minJ - 1, minJ - 1, /*axisI*/ false)) {
                minJ--; grew = true;
            }
            // Loop guard — prevent runaway iteration.
            if (maxI >= n - 1) maxI = n - 1;
            if (maxJ >= n - 1) maxJ = n - 1;
        }

        // Mark visited.
        double sum = 0.0;
        int count = 0;
        for (int i = minI; i <= maxI; i++) {
            for (int j = minJ; j <= maxJ; j++) {
                visited[i][j] = true;
                sum += scores[i][j];
                count++;
            }
        }
        if (count == 0) return null;
        double avg = sum / count;

        // Convert cell bbox to world bbox.
        BlockPos minWorld = fmap.cellWorldPos(minI, minJ);
        BlockPos maxWorld = fmap.cellWorldPos(maxI, maxJ);
        // Expand outwards by half-cell to cover the cell area.
        int half = cellSize / 2;
        GardenPlot.Bounds bounds = new GardenPlot.Bounds(
                minWorld.getX() - half, minWorld.getZ() - half,
                maxWorld.getX() + half, maxWorld.getZ() + half);
        return new Candidate(bounds, avg);
    }

    private static boolean tryExpand(double[][] scores, boolean[][] occupied,
                                     int newMinI, int newMaxI,
                                     int newMinJ, int newMaxJ,
                                     boolean axisI) {
        int n = scores.length;
        if (newMinI < 0 || newMaxI >= n || newMinJ < 0 || newMaxJ >= n) return false;
        int count = 0;
        double sum = 0.0;
        for (int i = newMinI; i <= newMaxI; i++) {
            for (int j = newMinJ; j <= newMaxJ; j++) {
                if (occupied[i][j]) return false;
                sum += scores[i][j];
                count++;
            }
        }
        if (count == 0) return false;
        return (sum / count) >= EXPANSION_SCORE_THRESHOLD;
    }

    // ── Style selection ───────────────────────────────────────────────

    /** Picks the highest-affinity style for {@code candidate} using
     *  the candidate's terrain mix, the village's inclination, and
     *  the culture's per-style preference weights. */
    private static StyleFit pickBestStyle(Candidate candidate, Culture culture,
                                          Inclination inclination) {
        StyleFit best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (GardenStyle style : GardenStyle.values()) {
            int span = Math.max(candidate.bounds.width(), candidate.bounds.length());
            if (span < style.minSize() || span > style.maxSize()) continue;
            double prefWeight = culture == null ? 1.0
                    : culture.planningBias().parkPreferenceFor(style.name());
            double inclinationBonus = inclination != null
                    && style.inclinationAffinity().contains(inclination)
                    ? 1.5 : 1.0;
            double score = prefWeight * inclinationBonus;
            if (score > bestScore) {
                bestScore = score;
                best = new StyleFit(style, score);
            }
        }
        return best;
    }

    private static boolean overlapsExisting(GardenPlot.Bounds candidate,
                                            List<GardenPlot> reserved) {
        for (GardenPlot p : reserved) {
            if (candidate.overlaps(p.bounds())) return true;
        }
        return false;
    }

    // ── Helper records ────────────────────────────────────────────────

    private record Candidate(GardenPlot.Bounds bounds, double areaScore) {}
    private record StyleFit(GardenStyle style, double score) {}
    private record RankedPlot(Candidate candidate, GardenStyle style, double score) {}
}
