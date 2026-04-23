package tterrag1112.life_in_the_village.Village.Roads.Graph.Worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.World.Atlas.AtlasCell;
import tterrag1112.life_in_the_village.World.Atlas.BiomeCategory;
import tterrag1112.life_in_the_village.World.Atlas.WorldAtlas;

import java.util.*;

/**
 * Generates the anchor positions for the Old Realm road skeleton using
 * deterministic Poisson-disk sampling seeded from the world seed.
 *
 * <h3>Algorithm</h3>
 * The region is divided into 2000-block "grid cells." For each grid cell,
 * one candidate position is generated using an RNG seeded from the world seed
 * and the cell's coordinates. Candidates that are too close (< 4000 blocks)
 * to an already-accepted anchor are rejected, producing natural spacing.
 *
 * <h3>Quality scoring</h3>
 * Accepted candidates are scored by sampling the atlas: flat buildable land
 * starts at 60, steep terrain loses 30, coast adds 10, river-adjacency adds
 * 15, and mountain-pass detection adds 20. Ocean and void cells are rejected.
 *
 * <h3>Dominant axis</h3>
 * Each world has a "primary old-road direction" derived from the world seed.
 * A world-seeded origin point defines where the axis line passes through the
 * world. Candidates within 1000 blocks of that line are marked as on the
 * dominant axis and receive a quality boost of 25.
 */
public final class GreatRoadAnchorSeeder {

    private GreatRoadAnchorSeeder() {}

    // ── Poisson sampling parameters ──────────────────────────────────────────

    /** Size of each Poisson grid cell in blocks. */
    private static final int  GRID_CELL_SIZE = 2_000;
    /** Minimum block distance between accepted anchors. */
    private static final int  MIN_DIST       = 4_000;
    private static final long MIN_DIST_SQ    = (long) MIN_DIST * MIN_DIST;

    /** Primes for deterministic per-grid-cell RNG seeding. */
    private static final long PRIME_A = 6364136223846793005L;
    private static final long PRIME_B = 1442695040888963407L;

    // ── Quality scoring ──────────────────────────────────────────────────────

    private static final int QUALITY_BASE_BUILDABLE      = 60;
    private static final int QUALITY_STEEP_PENALTY       = -30;
    private static final int QUALITY_COAST_BONUS         = 10;
    private static final int QUALITY_RIVER_ADJ_BONUS     = 15;
    private static final int QUALITY_MOUNTAIN_PASS_BONUS = 20;
    private static final int QUALITY_DOMINANT_AXIS_BONUS = 25;

    // ── Dominant axis parameters ──────────────────────────────────────────────

    /** Primary perpendicular distance threshold for "on dominant axis." */
    private static final int AXIS_THRESHOLD_BLOCKS  = 1_000;
    /** Relaxed threshold if fewer than 2 candidates fall on axis. */
    private static final int AXIS_THRESHOLD_RELAXED = 3_000;
    /** Target count of anchors marked on the dominant axis. */
    private static final int AXIS_TARGET_COUNT      = 3;

    // ── Axis direction table (normalised vectors, index = axisIndex) ──────────
    // 0=N  1=NE  2=E  3=SE  4=S  5=SW  6=W  7=NW
    private static final double[] AXIS_DX = {  0,  1,  1,  1,  0, -1, -1, -1 };
    private static final double[] AXIS_DZ = {  1,  1,  0, -1, -1, -1,  0,  1 };

    // =========================================================================
    // Public record
    // =========================================================================

    public record AnchorCandidate(
            BlockPos position,
            int quality,
            boolean onDominantAxis
    ) {}

    // =========================================================================
    // Main entry point
    // =========================================================================

    /**
     * Generates anchor candidates for the given world region.
     *
     * <p>The same seed always produces the same positions — this method is
     * purely deterministic (seeded RNGs only, no clock or iteration-order
     * dependencies).
     *
     * @param worldSeed    the level seed
     * @param regionMinX   western boundary in block coordinates (inclusive)
     * @param regionMinZ   northern boundary in block coordinates (inclusive)
     * @param regionMaxX   eastern boundary in block coordinates (inclusive)
     * @param regionMaxZ   southern boundary in block coordinates (inclusive)
     * @param level        server level (used only for force-filling atlas cells)
     * @param atlas        the world atlas (cells are force-filled if absent)
     * @return list of accepted candidates, sorted by position for stability
     */
    public static List<AnchorCandidate> seedAnchors(
            long worldSeed,
            int regionMinX, int regionMinZ,
            int regionMaxX, int regionMaxZ,
            ServerLevel level,
            WorldAtlas atlas) {

        // ── Phase 1: Poisson-disk sampling ───────────────────────────────────

        int gridMinX = Math.floorDiv(regionMinX, GRID_CELL_SIZE);
        int gridMinZ = Math.floorDiv(regionMinZ, GRID_CELL_SIZE);
        int gridMaxX = Math.floorDiv(regionMaxX, GRID_CELL_SIZE);
        int gridMaxZ = Math.floorDiv(regionMaxZ, GRID_CELL_SIZE);

        // Build the sorted list of grid cells for deterministic iteration
        List<int[]> gridCells = new ArrayList<>();
        for (int gx = gridMinX; gx <= gridMaxX; gx++) {
            for (int gz = gridMinZ; gz <= gridMaxZ; gz++) {
                gridCells.add(new int[]{gx, gz});
            }
        }
        // Sort by gx then gz so the order is stable regardless of iteration
        gridCells.sort(Comparator.comparingInt((int[] c) -> c[0])
                .thenComparingInt(c -> c[1]));

        List<BlockPos> accepted = new ArrayList<>();

        for (int[] cell : gridCells) {
            int gx = cell[0];
            int gz = cell[1];

            // Deterministic seed for this grid cell
            long cellSeed = worldSeed ^ (gx * PRIME_A) ^ (gz * PRIME_B);
            Random rng = new Random(cellSeed);

            // One candidate position within this grid cell
            int cx = gx * GRID_CELL_SIZE + rng.nextInt(GRID_CELL_SIZE);
            int cz = gz * GRID_CELL_SIZE + rng.nextInt(GRID_CELL_SIZE);

            // Clamp to the requested region
            cx = Math.max(regionMinX, Math.min(regionMaxX, cx));
            cz = Math.max(regionMinZ, Math.min(regionMaxZ, cz));

            // Reject if too close to any already-accepted anchor
            boolean tooClose = false;
            for (BlockPos ex : accepted) {
                long dx = (long)(ex.getX() - cx);
                long dz = (long)(ex.getZ() - cz);
                if (dx * dx + dz * dz < MIN_DIST_SQ) {
                    tooClose = true;
                    break;
                }
            }
            if (!tooClose) {
                accepted.add(new BlockPos(cx, 0, cz));
            }
        }

        // ── Phase 2: Quality scoring and ocean/void rejection ─────────────────

        List<AnchorCandidate> candidates = new ArrayList<>();
        for (BlockPos pos : accepted) {
            int quality = scoreQuality(pos, level, atlas);
            if (quality > 0) {
                candidates.add(new AnchorCandidate(pos, quality, false));
            }
        }

        // ── Phase 3: Dominant axis marking ───────────────────────────────────

        if (!candidates.isEmpty()) {
            candidates = markDominantAxis(candidates, worldSeed);
        }

        return candidates;
    }

    // =========================================================================
    // Quality scoring
    // =========================================================================

    private static int scoreQuality(BlockPos pos, ServerLevel level, WorldAtlas atlas) {
        AtlasCell cell = atlas.getCellAtBlock(pos.getX(), pos.getZ());
        if (cell == null) {
            // Force-fill this one cell — acceptable worldgen-time cost
            cell = atlas.ensureCell(level, pos.getX(), pos.getZ());
        }

        // Hard rejections
        if (cell.category() == BiomeCategory.OCEAN) return 0;
        if (cell.category() == BiomeCategory.VOID)  return 0;
        // Pure river cells with no land adjacency cannot serve as anchors
        if (cell.category() == BiomeCategory.RIVER && !cell.isRiverAdj()) return 0;

        int quality = QUALITY_BASE_BUILDABLE;

        if (cell.isSteep())     quality += QUALITY_STEEP_PENALTY;
        if (cell.isCoast())     quality += QUALITY_COAST_BONUS;
        if (cell.isRiverAdj()) quality += QUALITY_RIVER_ADJ_BONUS;

        // Mountain pass detection: non-mountain cell with mountain neighbours
        if (cell.category() != BiomeCategory.MOUNTAIN
                && countMountainNeighbors(pos, atlas) >= 2) {
            quality += QUALITY_MOUNTAIN_PASS_BONUS;
        }

        return Math.max(0, quality);
    }

    private static int countMountainNeighbors(BlockPos pos, WorldAtlas atlas) {
        int count = 0;
        int cellX = WorldAtlas.blockToCell(pos.getX());
        int cellZ = WorldAtlas.blockToCell(pos.getZ());
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                AtlasCell nb = atlas.getCellByCoord(cellX + dx, cellZ + dz);
                if (nb != null && nb.category() == BiomeCategory.MOUNTAIN) count++;
            }
        }
        return count;
    }

    // =========================================================================
    // Dominant axis
    // =========================================================================

    private static List<AnchorCandidate> markDominantAxis(
            List<AnchorCandidate> candidates, long worldSeed) {

        int axisIndex = getDominantAxisIndex(worldSeed);
        double axDx = AXIS_DX[axisIndex];
        double axDz = AXIS_DZ[axisIndex];
        // Pre-normalised: diagonals have length √2, so normalise explicitly
        double len = Math.sqrt(axDx * axDx + axDz * axDz);
        axDx /= len;
        axDz /= len;

        // World-seeded axis origin (near spawn, offset by seed)
        Random rng = new Random(worldSeed * PRIME_A + PRIME_B);
        int originX = rng.nextInt(4000) - 2000;
        int originZ = rng.nextInt(4000) - 2000;

        // Perpendicular distance of each candidate from the axis line
        double[] perpDist = new double[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) {
            BlockPos pos = candidates.get(i).position();
            double relX = pos.getX() - originX;
            double relZ = pos.getZ() - originZ;
            // |cross product| = perpendicular distance
            perpDist[i] = Math.abs(relX * axDz - relZ * axDx);
        }

        // Collect candidates within the primary threshold
        List<Integer> axisIndices = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            if (perpDist[i] <= AXIS_THRESHOLD_BLOCKS) axisIndices.add(i);
        }

        // Relax to secondary threshold if fewer than 2 were found
        if (axisIndices.size() < 2) {
            axisIndices.clear();
            for (int i = 0; i < candidates.size(); i++) {
                if (perpDist[i] <= AXIS_THRESHOLD_RELAXED) axisIndices.add(i);
            }
        }

        // If still fewer than 2, take the two candidates closest to the axis
        if (axisIndices.size() < 2) {
            Integer[] sorted = new Integer[candidates.size()];
            for (int i = 0; i < sorted.length; i++) sorted[i] = i;
            Arrays.sort(sorted, Comparator.comparingDouble(i -> perpDist[i]));
            axisIndices.clear();
            int toTake = Math.min(2, candidates.size());
            for (int i = 0; i < toTake; i++) axisIndices.add(sorted[i]);
        }

        // Cap at AXIS_TARGET_COUNT, preferring the highest quality candidates
        if (axisIndices.size() > AXIS_TARGET_COUNT) {
            axisIndices.sort(Comparator.comparingInt(i -> -candidates.get(i).quality()));
            axisIndices = new ArrayList<>(axisIndices.subList(0, AXIS_TARGET_COUNT));
        }

        Set<Integer> axisSet = new HashSet<>(axisIndices);

        // Rebuild list with axis flag and quality bonus applied
        List<AnchorCandidate> result = new ArrayList<>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            AnchorCandidate old = candidates.get(i);
            boolean onAxis = axisSet.contains(i);
            int quality    = onAxis ? old.quality() + QUALITY_DOMINANT_AXIS_BONUS : old.quality();
            result.add(new AnchorCandidate(old.position(), quality, onAxis));
        }
        return result;
    }

    // =========================================================================
    // Public helpers (used by debug commands and GreatRoadGenerationQueue)
    // =========================================================================

    /**
     * Returns the dominant axis direction index for the given world seed.
     * 0=N, 1=NE, 2=E, 3=SE, 4=S, 5=SW, 6=W, 7=NW
     */
    public static int getDominantAxisIndex(long worldSeed) {
        return (int) Math.floorMod(worldSeed, 8);
    }

    /** Human-readable name for a dominant axis index (0–7). */
    public static String getDominantAxisName(int axisIndex) {
        return new String[]{"N", "NE", "E", "SE", "S", "SW", "W", "NW"}[axisIndex & 7];
    }
}
