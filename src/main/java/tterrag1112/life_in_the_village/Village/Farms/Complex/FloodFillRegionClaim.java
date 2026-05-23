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
            java.util.List<Polygon> excludedPolygons) {

        /** Backward-compat for callers that don't yet pass an
         *  exclusion list (test command, future call sites that
         *  don't track reservations). */
        public Input(BlockPos seed, int maxRadiusBlocks, int blockBudget,
                     int slopeLimit, double scoreThreshold,
                     V2FeatureMap fmap, BiomeBlockedPredicate biomeBlocked) {
            this(seed, maxRadiusBlocks, blockBudget, slopeLimit,
                    scoreThreshold, fmap, biomeBlocked, java.util.List.of());
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
            return fail(in, FailReason.SEED_NOT_ADMISSIBLE,
                    "Seed cell category/slope/score does not pass admissibility.");
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

        // BFS with deterministic neighbour order N, E, S, W.
        Set<Long> admitted = new HashSet<>();
        Deque<int[]> queue = new ArrayDeque<>();
        long seedPacked = CellPolygonizer.packCell(seedI, seedJ);
        admitted.add(seedPacked);
        queue.addLast(new int[]{seedI, seedJ});
        final int[] dx = {0, +1, 0, -1};
        final int[] dz = {-1, 0, +1, 0};
        double r2 = (double) in.maxRadiusBlocks() * in.maxRadiusBlocks();
        int budget = in.blockBudget();
        while (!queue.isEmpty() && admitted.size() < budget) {
            int[] cur = queue.pollFirst();
            for (int d = 0; d < 4; d++) {
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
                Cell c = fmap.cellAt(worldPos.getX(), worldPos.getZ());
                if (!admissible(c, in)) continue;
                admitted.add(packed);
                queue.addLast(new int[]{ni, nj});
                if (admitted.size() >= budget) break;
            }
        }

        if (admitted.size() < MIN_VIABLE_CELLS) {
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
        boolean tight = admitted.size() < TIGHT_FRACTION * budget;
        return new Result(polygon, admitted, admitted.size(), budget,
                tight, null, null);
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
