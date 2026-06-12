package tterrag1112.life_in_the_village.Village.Farms.Complex;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Utilities.Geometry.Polygon;
import tterrag1112.life_in_the_village.Village.Farms.ArableScoring;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer1.Cell;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer1.V2FeatureMap;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * Detour A — flood-fill arable region claim around a farmhouse seed.
 *
 * <p>BFS over the {@link V2FeatureMap}'s cell grid, 4-connected. A
 * cell is admitted if all of:
 * <ul>
 *   <li>distance from seed (in blocks) {@code <= maxRadiusBlocks}</li>
 *   <li>{@link ArableScoring#score} {@code >= scoreThreshold}</li>
 *   <li>{@link Cell#localSlope()} {@code <= slopeLimit}</li>
 *   <li>{@code !biomeBlocked.test} at the cell's world position</li>
 *   <li>budget not yet exhausted</li>
 * </ul>
 *
 * <p>The cell set is converted to a {@link Polygon} via
 * {@link CellPolygonizer}, then simplified with 1-block tolerance to
 * collapse the single-cell notches the prompt calls out.
 *
 * <p>Pure function. Deterministic FIFO queue and fixed neighbour
 * order make output reproducible for identical inputs.
 */
public final class FloodFillRegionClaim {

    /** Per-prompt failure threshold — claim below this is "no
     *  arable land here, fail the spawn." */
    public static final int MIN_VIABLE_CELLS = 100;

    /** "Tight" complex flag — when claim fills less than this
     *  fraction of budget, the BSP target plot count is reduced
     *  proportionally upstream. */
    public static final double TIGHT_FRACTION = 0.50;

    private FloodFillRegionClaim() {}

    /** Inputs to the claim. All required; nullable {@link #biomeBlocked}
     *  treated as "allow everything" (used by the headless harness
     *  where biome lookup isn't possible). */
    public record Input(
            BlockPos seed,
            int maxRadiusBlocks,
            int blockBudget,
            int slopeLimit,
            double scoreThreshold,
            V2FeatureMap fmap,
            BiomeBlockedPredicate biomeBlocked,
            /** Detour A — Prompt B Stage A. Polygons whose interior
             *  is forbidden territory (parks, other reserved areas).
             *  Null or empty ⇒ no exclusion. Cells whose centre falls
             *  inside any of these are rejected during BFS, same as
             *  arable / biome failures. */
            java.util.List<Polygon> excludedPolygons,
            /** Prompt B follow-up — when true, the seed-admissibility
             *  failure path emits a single diagnostic line naming
             *  the cell category, slope, water/forest distances,
             *  computed arable score, and which gate rejected.
             *  Off in production; on for the test_spawn harness. */
            boolean verbose,
            /** Layout Rework Stage 2b — hard containment boundary. When
             *  non-null, cells whose centre falls OUTSIDE this polygon
             *  are rejected during BFS, bounding the claim to the
             *  reserved {@code Parcel} budget box. (The FeatureMap is
             *  scanned pre-spawn, so without this the flood-fill could
             *  spill onto cells now occupied by other buildings.) Null ⇒
             *  unbounded (only maxRadius + budget cap apply). */
            Polygon boundary) {

        /** Backward-compat for callers that don't yet pass an
         *  exclusion list (test command, future call sites that
         *  don't track reservations). */
        public Input(BlockPos seed, int maxRadiusBlocks, int blockBudget,
                     int slopeLimit, double scoreThreshold,
                     V2FeatureMap fmap, BiomeBlockedPredicate biomeBlocked) {
            this(seed, maxRadiusBlocks, blockBudget, slopeLimit,
                    scoreThreshold, fmap, biomeBlocked,
                    java.util.List.of(), false, null);
        }

        /** Backward-compat for callers that pass exclusion polygons
         *  but no verbosity flag (V2 spawn adapter — quiet). */
        public Input(BlockPos seed, int maxRadiusBlocks, int blockBudget,
                     int slopeLimit, double scoreThreshold,
                     V2FeatureMap fmap, BiomeBlockedPredicate biomeBlocked,
                     java.util.List<Polygon> excludedPolygons) {
            this(seed, maxRadiusBlocks, blockBudget, slopeLimit,
                    scoreThreshold, fmap, biomeBlocked,
                    excludedPolygons, false, null);
        }

        /** Backward-compat for callers that pass exclusion + verbosity
         *  but no containment boundary. */
        public Input(BlockPos seed, int maxRadiusBlocks, int blockBudget,
                     int slopeLimit, double scoreThreshold,
                     V2FeatureMap fmap, BiomeBlockedPredicate biomeBlocked,
                     java.util.List<Polygon> excludedPolygons, boolean verbose) {
            this(seed, maxRadiusBlocks, blockBudget, slopeLimit,
                    scoreThreshold, fmap, biomeBlocked,
                    excludedPolygons, verbose, null);
        }
    }

    /** Result of the claim. {@link #failure} non-null iff the claim
     *  failed; {@link #region} non-null iff success. {@link #tight}
     *  flags a marginal claim ({@code cellsClaimed < TIGHT_FRACTION *
     *  blockBudget}) so callers can reduce plot count. */
    public record Result(
            Polygon region,
            Set<Long> admittedCells,
            int cellsClaimed,
            int blockBudget,
            boolean tight,
            FailReason failure,
            String detail) {}

    public enum FailReason {
        SEED_OUT_OF_BOUNDS,
        SEED_NOT_ADMISSIBLE,
        INSUFFICIENT_AREA,
        BIOME_BLOCKED_AT_SEED
    }

    /** Per-cell biome veto. {@code true} ⇒ the cell at world
     *  ({@code x}, {@code z}) is in a blocked biome and must NOT
     *  be admitted. Implementation lives outside this class so
     *  the algorithm stays free of {@code net.minecraft.world.level}
     *  dependencies. */
    @FunctionalInterface
    public interface BiomeBlockedPredicate {
        boolean test(int worldX, int worldZ);
    }

    public static Result run(Input in) {
        V2FeatureMap fmap = in.fmap();
        BlockPos seed = in.seed();
        if (!fmap.inBounds(seed.getX(), seed.getZ())) {
            return fail(in, FailReason.SEED_OUT_OF_BOUNDS,
                    "Seed (" + seed.getX() + "," + seed.getZ() + ") outside scan grid.");
        }
        if (in.biomeBlocked() != null
                && in.biomeBlocked().test(seed.getX(), seed.getZ())) {
            return fail(in, FailReason.BIOME_BLOCKED_AT_SEED,
                    "Seed biome is in spec's blocked set.");
        }
        if (insideAnyExcluded(seed.getX(), seed.getZ(), in)) {
            return fail(in, FailReason.SEED_NOT_ADMISSIBLE,
                    "Seed lies inside a reserved exclusion polygon "
                            + "(park, garden, or other prior reservation).");
        }

        // Seed cell admissibility — if the seed itself isn't arable,
        // there's no point flooding from it.
        Cell seedCell = fmap.cellAt(seed.getX(), seed.getZ());
        if (!admissible(seedCell, in)) {
            String diag = diagnoseSeed(seedCell, in);
            if (in.verbose()) {
                org.slf4j.LoggerFactory.getLogger(FloodFillRegionClaim.class)
                        .info("FloodFillRegionClaim seed reject @ ({}, {}, {}): {}",
                                seed.getX(), seed.getY(), seed.getZ(), diag);
            }
            return fail(in, FailReason.SEED_NOT_ADMISSIBLE,
                    "Seed cell category/slope/score does not pass admissibility. "
                            + diag);
        }

        // Translate seed world coords → grid (i, j). V2FeatureMap
        // doesn't expose this directly, but cellWorldPos maps the
        // other way and cellAt clamps; recover by integer division.
        int[] gridOrigin = gridOriginOf(fmap);
        int seedI = (seed.getX() - gridOrigin[0]) / fmap.cellSize();
        int seedJ = (seed.getZ() - gridOrigin[1]) / fmap.cellSize();
        if (seedI < 0 || seedJ < 0
                || seedI >= fmap.gridSize() || seedJ >= fmap.gridSize()) {
            return fail(in, FailReason.SEED_OUT_OF_BOUNDS,
                    "Seed grid index out of bounds after translation.");
        }

        // BFS with deterministic neighbour order — 8-connected
        // (N, NE, E, SE, S, SW, W, NW). Pre-tuning the BFS was
        // 4-connected, which combined with the square scan grid
        // produced visibly diamond-shaped complexes. Diagonals
        // are admitted under the same arable / biome / exclusion
        // checks as orthogonals; budget cost is the same.
        Set<Long> admitted = new HashSet<>();
        Deque<int[]> queue = new ArrayDeque<>();
        long seedPacked = CellPolygonizer.packCell(seedI, seedJ);
        admitted.add(seedPacked);
        queue.addLast(new int[]{seedI, seedJ});
        final int[] dx = { 0, +1, +1, +1,  0, -1, -1, -1};
        final int[] dz = {-1, -1,  0, +1, +1, +1,  0, -1};
        double r2 = (double) in.maxRadiusBlocks() * in.maxRadiusBlocks();
        int budget = in.blockBudget();
        while (!queue.isEmpty() && admitted.size() < budget) {
            int[] cur = queue.pollFirst();
            for (int d = 0; d < 8; d++) {
                int ni = cur[0] + dx[d];
                int nj = cur[1] + dz[d];
                if (ni < 0 || nj < 0
                        || ni >= fmap.gridSize() || nj >= fmap.gridSize()) continue;
                long packed = CellPolygonizer.packCell(ni, nj);
                if (admitted.contains(packed)) continue;
                BlockPos worldPos = fmap.cellWorldPos(ni, nj);
                double ddx = worldPos.getX() - seed.getX();
                double ddz = worldPos.getZ() - seed.getZ();
                if (ddx * ddx + ddz * ddz > r2) continue;
                if (in.biomeBlocked() != null
                        && in.biomeBlocked().test(worldPos.getX(), worldPos.getZ())) {
                    continue;
                }
                if (insideAnyExcluded(worldPos.getX(), worldPos.getZ(), in)) {
                    continue;
                }
                // Stage 2b — hard parcel containment: stay inside the
                // reserved budget box (the FeatureMap is pre-spawn, so
                // unbounded claims could spill onto now-built cells).
                if (in.boundary() != null
                        && !Polygon.contains(in.boundary(),
                                worldPos.getX(), worldPos.getZ())) {
                    continue;
                }
                Cell c = fmap.cellAt(worldPos.getX(), worldPos.getZ());
                if (!admissible(c, in)) continue;
                admitted.add(packed);
                queue.addLast(new int[]{ni, nj});
                if (admitted.size() >= budget) break;
            }
        }

        // Boundary roughening — drop ~18% of cells that have at
        // least one non-admitted neighbour. Single pass: iterating
        // fragments the region (drops expose new boundary cells
        // for the next pass, cascading inward). Uses a seed
        // derived from the original seed so the same farmhouse +
        // game-time produces the same rough edge each run.
        roughenBoundary(admitted, seedI, seedJ, in);

        // Round 2 item 3 — the minimum-area gate applies only to UNPROVEN
        // claims. When a containment boundary is present it is a proven
        // Parcel budget (the planner's dry-run already cleared its own
        // quantum ≥ this floor), so the refill must not re-litigate area:
        // a bad re-seed or exclusion drift would otherwise skip a complex
        // the planner committed. The ring's PROBE path also passes a
        // boundary (the wedge polygon) but enforces its own stricter
        // quantum on cellsClaimed, so it is unaffected by the skip.
        if (admitted.size() < MIN_VIABLE_CELLS && in.boundary() == null) {
            return fail(in, FailReason.INSUFFICIENT_AREA,
                    "Insufficient arable land at this location ("
                            + admitted.size() + " < "
                            + MIN_VIABLE_CELLS + " cells).");
        }

        BlockPos worldOrigin = new BlockPos(gridOrigin[0], seed.getY(), gridOrigin[1]);
        Polygon polygon = CellPolygonizer.polygonize(admitted, fmap.cellSize(),
                worldOrigin, seed.getY());
        if (polygon == null) {
            return fail(in, FailReason.INSUFFICIENT_AREA,
                    "Polygon construction failed (degenerate boundary).");
        }
        // E.bug.4 — soften the outer region silhouette. Chaikin
        // corner-cutting once rounds the BSP-grid-aligned vertices
        // produced by polygonize, removing the rectilinear "near-
        // square" look without going back to the diamond extreme.
        // Plot polygons (from BspSubdivider per leaf) stay tight —
        // smoothing only applied here on the region.
        polygon = CellPolygonizer.chaikinSmooth(polygon, 1);
        if (polygon == null || polygon.vertices().size() < 3) {
            return fail(in, FailReason.INSUFFICIENT_AREA,
                    "Region polygon collapsed during smoothing.");
        }
        boolean tight = admitted.size() < TIGHT_FRACTION * budget;
        return new Result(polygon, admitted, admitted.size(), budget,
                tight, null, null);
    }

    /** Fraction of boundary cells dropped during roughening.
     *  Tuned so the perimeter reads as organic / ragged without
     *  fragmenting the region or punching obvious notches.
     *  E.bug.4: lowered 0.18 → 0.10 to drop fewer small notches
     *  (the "squiggles"); CellPolygonizer adds a Chaikin smoothing
     *  pass on the region polygon which finishes the softening
     *  without requiring more aggressive roughening here. */
    private static final double BOUNDARY_DROP_RATE = 0.10;

    /** Identify boundary cells (cells with at least one missing
     *  neighbour out of the 8 directions), then remove a
     *  {@link #BOUNDARY_DROP_RATE} fraction of them. Single pass —
     *  iterating would cascade inward as the drops expose new
     *  boundary cells.
     *
     *  <p>The seed cell itself is NEVER dropped, even if it lands
     *  on the boundary after BFS (small claims can leave the seed
     *  with all-non-admitted diagonals). */
    private static void roughenBoundary(Set<Long> admitted,
                                         int seedI, int seedJ,
                                         Input in) {
        java.util.Random rng = new java.util.Random(
                ((long) seedI << 32) ^ seedJ ^ in.maxRadiusBlocks());
        long seedCell = CellPolygonizer.packCell(seedI, seedJ);
        final int[] dxRough = { 0, +1, +1, +1,  0, -1, -1, -1};
        final int[] dzRough = {-1, -1,  0, +1, +1, +1,  0, -1};
        java.util.List<Long> boundary = new java.util.ArrayList<>();
        for (Long cell : admitted) {
            if (cell == seedCell) continue;
            int ci = (int) (cell >> 32);
            int cj = (int) (long) cell;
            for (int d = 0; d < 8; d++) {
                long neighbour = CellPolygonizer.packCell(
                        ci + dxRough[d], cj + dzRough[d]);
                if (!admitted.contains(neighbour)) {
                    boundary.add(cell);
                    break;
                }
            }
        }
        // Deterministic order for the drop selection.
        java.util.Collections.sort(boundary);
        for (Long b : boundary) {
            if (rng.nextDouble() < BOUNDARY_DROP_RATE) admitted.remove(b);
        }
    }

    // ── helpers ────────────────────────────────────────────────────────

    /** True iff {@code (x, z)} lies within any of the input's
     *  excluded polygons. Null / empty list ⇒ always false (cheap
     *  short-circuit). Called once per BFS-visited cell + once per
     *  seed check; for the typical case of zero-or-few exclusions
     *  the loop is fine. */
    private static boolean insideAnyExcluded(int x, int z, Input in) {
        var ex = in.excludedPolygons();
        if (ex == null || ex.isEmpty()) return false;
        for (Polygon p : ex) {
            if (p == null) continue;
            if (Polygon.contains(p, x, z)) return true;
        }
        return false;
    }

    /** Compose a one-line diagnostic explaining why the seed cell
     *  failed admissibility. Names the rejecting gate so the caller
     *  doesn't have to cross-reference the rule order. Pure
     *  read-only; cheap to compute even when verbose is off (used
     *  in the fail() detail string regardless, so test-command
     *  users see it in the chat error). */
    private static String diagnoseSeed(Cell c, Input in) {
        if (c == null) {
            return "category=null score=0.00 threshold=" + fmt(in.scoreThreshold())
                    + " gate=NULL_CELL";
        }
        String cat   = String.valueOf(c.category());
        int slope    = c.localSlope();
        int distW    = c.distToWater()  == Integer.MAX_VALUE ? -1 : c.distToWater();
        int distF    = c.distToForest() == Integer.MAX_VALUE ? -1 : c.distToForest();
        double score = ArableScoring.score(c, in.fmap().cellSize());
        String gate;
        if (c.category() != tterrag1112.life_in_the_village.Village.Planning.V2
                .Layer1.BlockCategory.OPEN
                && c.category() != tterrag1112.life_in_the_village.Village.Planning.V2
                        .Layer1.BlockCategory.SHORE) {
            gate = "CATEGORY (need OPEN|SHORE)";
        } else if (slope > ArableScoring.MAX_INTERIOR_SLOPE) {
            gate = "SLOPE_INNER (need ≤" + ArableScoring.MAX_INTERIOR_SLOPE + ")";
        } else if (slope > in.slopeLimit()) {
            gate = "SLOPE_OUTER (need ≤" + in.slopeLimit() + ")";
        } else if (score < in.scoreThreshold()) {
            gate = "SCORE";
        } else {
            gate = "UNKNOWN";
        }
        return "category=" + cat
                + " slope=" + slope
                + " distWater=" + distW
                + " distForest=" + distF
                + " score=" + fmt(score)
                + " threshold=" + fmt(in.scoreThreshold())
                + " gate=" + gate;
    }

    private static String fmt(double d) {
        return String.format(java.util.Locale.ROOT, "%.2f", d);
    }

    private static boolean admissible(Cell c, Input in) {
        if (c == null) return false;
        if (c.localSlope() > in.slopeLimit()) return false;
        double score = ArableScoring.score(c, in.fmap().cellSize());
        return score >= in.scoreThreshold();
    }

    /** Recover the grid's world origin (minX, minZ) from the map.
     *  {@code cellWorldPos(0, 0)} returns the cell CENTRE; back out
     *  by half a cell size on each axis. */
    private static int[] gridOriginOf(V2FeatureMap fmap) {
        BlockPos centreOf00 = fmap.cellWorldPos(0, 0);
        int half = fmap.cellSize() / 2;
        return new int[]{centreOf00.getX() - half, centreOf00.getZ() - half};
    }

    private static Result fail(Input in, FailReason reason, String detail) {
        return new Result(null, Set.of(), 0, in.blockBudget(), false,
                reason, detail);
    }
}
