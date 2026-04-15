// src/main/java/tterrag1112/life_in_the_village/Village/Planning/TerrainAnalyzer.java
package tterrag1112.life_in_the_village.Village.Planning.Terrain;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Analyses terrain around a candidate village origin and produces a
 * {@link TerrainProfile} that the planner uses to orient the layout.
 *
 * <h3>What is detected</h3>
 * <ul>
 *   <li><b>Flat ratio / steep ratio</b> — proportion of the sample
 *       area that is flat (≤2 Y variance from base) or steep (&gt;6)</li>
 *   <li><b>Water bodies</b> — contiguous water masses ≥16 blocks. The
 *       nearest water body centre and its approximate shoreline direction
 *       are stored so the planner can orient civic buildings toward it.</li>
 *   <li><b>Ridgelines</b> — narrow bands of elevated terrain that divide
 *       the site. Stored as axis-aligned rectangles so the planner can
 *       route roads around them rather than through them.</li>
 *   <li><b>Natural clearings</b> — existing open areas with few trees,
 *       preferred for building placement so fewer trees need clearing.</li>
 *   <li><b>Best flat direction</b> — the cardinal quadrant with the
 *       most flat terrain, used to orient farm plots.</li>
 * </ul>
 */
public class TerrainAnalyzer {

    private static final int SAMPLE_RADIUS = 48;
    private static final int SAMPLE_STEP   = 4;

    // Minimum connected water blocks to count as a water body
    private static final int MIN_WATER_BODY_SIZE = 16;
    // Y difference that defines a ridgeline column
    private static final int RIDGE_THRESHOLD     = 8;


    // =========================================================================
    // Entry point
    // =========================================================================

    public static TerrainProfile analyze(ServerLevel level, BlockPos origin) {
        int samples    = 0;
        int flatCount  = 0;
        int waterCount = 0;
        int steepCount = 0;
        int treeCount  = 0;

        int baseY = surfaceY(level, origin);
        int minY  = baseY;
        int maxY  = baseY;

        List<BlockPos> flatCandidates    = new ArrayList<>();
        List<BlockPos> waterSurface      = new ArrayList<>();
        List<BlockPos> ridgeColumns      = new ArrayList<>();
        List<BlockPos> clearingCandidates= new ArrayList<>();

        // ── Main sample grid ──────────────────────────────────────────────────
        for (int dx = -SAMPLE_RADIUS; dx <= SAMPLE_RADIUS; dx += SAMPLE_STEP) {
            for (int dz = -SAMPLE_RADIUS; dz <= SAMPLE_RADIUS; dz += SAMPLE_STEP) {
                BlockPos sample = origin.offset(dx, 0, dz);
                int      y      = surfaceY(level, sample);
                int      dy     = Math.abs(y - baseY);

                samples++;
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);

                boolean isWater = isWaterAt(level, sample);
                boolean isTree  = isTreeAt(level, sample, y);

                if (isWater) {
                    waterCount++;
                    waterSurface.add(new BlockPos(
                            sample.getX(), y, sample.getZ()));
                } else if (dy >= RIDGE_THRESHOLD) {
                    steepCount++;
                    ridgeColumns.add(new BlockPos(
                            sample.getX(), y, sample.getZ()));
                } else if (dy <= 2) {
                    flatCount++;
                    flatCandidates.add(new BlockPos(
                            sample.getX(), y, sample.getZ()));
                    if (!isTree) {
                        clearingCandidates.add(new BlockPos(
                                sample.getX(), y, sample.getZ()));
                    }
                } else {
                    if (!isTree) steepCount++;
                }

                if (isTree) treeCount++;
            }
        }

        float flatRatio   = (float) flatCount  / samples;
        float waterRatio  = (float) waterCount / samples;
        float steepRatio  = (float) steepCount / samples;
        float treeRatio   = (float) treeCount  / samples;

        // Suitability — flat terrain good, water and steep bad
        // Clearings are a bonus (less prep work)
        float suitability = flatRatio
                - waterRatio  * 2.0f
                - steepRatio  * 0.3f;

        // ── Water body detection ──────────────────────────────────────────────
        WaterBodyInfo waterBody = detectWaterBody(
                level, origin, waterSurface);

        // ── Ridge detection ───────────────────────────────────────────────────
        List<RidgeInfo> ridges = detectRidges(
                ridgeColumns, origin, baseY);

        // ── Best flat direction ───────────────────────────────────────────────
        FlatDirection bestFlat = detectBestFlatDirection(
                level, origin, baseY);

        // ── Water-facing direction ────────────────────────────────────────────
        // If a significant water body exists, note the direction to it so
        // the planner can orient civic buildings to face it.
        FlatDirection waterFacing = waterBody != null
                ? directionTo(origin, waterBody.centre)
                : null;


        // ── Slope direction detection ─────────────────────────────────────
        // Compute the dominant downhill direction by sampling Y values
        // at the four cardinal extents and picking whichever is lowest
        // relative to baseY. Used by TERRACED to align terraces along
        // contour lines (perpendicular to the slope direction).
        FlatDirection slopeDir = detectSlopeDirection(level, origin, baseY);
        int slopeMagnitude = computeSlopeMagnitude(level, origin, baseY);

        return new TerrainProfile(
                origin, baseY, minY, maxY,
                flatRatio, waterRatio, steepRatio, treeRatio,
                suitability,
                flatCandidates, clearingCandidates,
                bestFlat, waterFacing,
                waterBody, ridges,
                slopeDir, slopeMagnitude);
    }


    // =========================================================================
    // Water body detection
    // =========================================================================

    /**
     * Finds the largest connected water body in the sample set and
     * returns its centre position and approximate shoreline direction.
     * Returns null if no significant water body is found.
     */
    private static WaterBodyInfo detectWaterBody(
            ServerLevel level, BlockPos origin,
            List<BlockPos> waterSurface) {

        if (waterSurface.size() < MIN_WATER_BODY_SIZE) return null;

        // Simple centroid of all water surface blocks
        long sumX = 0, sumZ = 0;
        for (BlockPos p : waterSurface) {
            sumX += p.getX();
            sumZ += p.getZ();
        }
        int centreX = (int)(sumX / waterSurface.size());
        int centreZ = (int)(sumZ / waterSurface.size());
        int centreY = surfaceY(level, new BlockPos(centreX, 64, centreZ));
        BlockPos centre = new BlockPos(centreX, centreY, centreZ);

        // Approximate shoreline: the edge of the water body nearest to origin
        BlockPos nearestWater = waterSurface.stream()
                .min(Comparator.comparingDouble(p -> p.distSqr(origin)))
                .orElse(centre);

        // Estimate water body radius
        double maxDistSq = waterSurface.stream()
                .mapToDouble(p -> p.distSqr(centre))
                .max()
                .orElse(0);
        int radius = (int) Math.sqrt(maxDistSq);

        return new WaterBodyInfo(centre, nearestWater,
                radius, waterSurface.size());
    }

    // =========================================================================
    // Ridge detection
    // =========================================================================

    /**
     * Groups elevated columns into ridge segments. A ridge is defined as
     * a connected group of steep columns that forms a band across the site.
     * Ridge segments are returned as axis-aligned bounding boxes that the
     * planner treats as hard obstacles — roads route around them, buildings
     * are not placed on them.
     */
    private static List<RidgeInfo> detectRidges(
            List<BlockPos> ridgeColumns, BlockPos origin, int baseY) {

        if (ridgeColumns.size() < 4) return Collections.emptyList();

        List<RidgeInfo> ridges = new ArrayList<>();
        Set<BlockPos>   used   = new HashSet<>();

        for (BlockPos seed : ridgeColumns) {
            if (used.contains(seed)) continue;

            // Flood-fill connected ridge columns (8-neighbour, SAMPLE_STEP tolerance)
            List<BlockPos> group = new ArrayList<>();
            Queue<BlockPos> queue = new ArrayDeque<>();
            queue.add(seed);
            used.add(seed);

            while (!queue.isEmpty()) {
                BlockPos cur = queue.poll();
                group.add(cur);
                for (BlockPos candidate : ridgeColumns) {
                    if (used.contains(candidate)) continue;
                    int cdx = Math.abs(candidate.getX() - cur.getX());
                    int cdz = Math.abs(candidate.getZ() - cur.getZ());
                    if (cdx <= SAMPLE_STEP * 2 && cdz <= SAMPLE_STEP * 2) {
                        used.add(candidate);
                        queue.add(candidate);
                    }
                }
            }

            if (group.size() < 3) continue; // too small to be a meaningful ridge

            // Compute bounding box of this ridge group
            int minX = group.stream().mapToInt(BlockPos::getX).min().orElse(0);
            int maxX = group.stream().mapToInt(BlockPos::getX).max().orElse(0);
            int minZ = group.stream().mapToInt(BlockPos::getZ).min().orElse(0);
            int maxZ = group.stream().mapToInt(BlockPos::getZ).max().orElse(0);
            int peakY= group.stream().mapToInt(BlockPos::getY).max().orElse(baseY);

            // Only record as a ridge if it spans more than 12 blocks (not a bump)
            if (Math.max(maxX - minX, maxZ - minZ) >= 12) {
                ridges.add(new RidgeInfo(
                        new BlockPos(minX, peakY, minZ),
                        new BlockPos(maxX, peakY, maxZ),
                        peakY - baseY,
                        // Classify as N/S or E/W oriented
                        (maxZ - minZ) > (maxX - minX)
                                ? RidgeInfo.Orientation.NORTH_SOUTH
                                : RidgeInfo.Orientation.EAST_WEST));
            }
        }

        return ridges;
    }

    // =========================================================================
    // Flat direction detection
    // =========================================================================

    private static FlatDirection detectBestFlatDirection(
            ServerLevel level, BlockPos origin, int baseY) {
        int[] scores = new int[4]; // N, S, E, W
        int check = SAMPLE_RADIUS / 2;
        int[][] dirs = {{0, -1}, {0, 1}, {1, 0}, {-1, 0}};

        for (int d = 0; d < 4; d++) {
            int hits = 0;
            for (int dist = 8; dist <= check; dist += 4) {
                for (int perp = -8; perp <= 8; perp += 4) {
                    int sx = origin.getX()
                            + dirs[d][0] * dist
                            + dirs[d][1] * perp;
                    int sz = origin.getZ()
                            + dirs[d][1] * dist
                            + dirs[d][0] * perp;
                    int y = surfaceY(level, new BlockPos(sx, 64, sz));
                    if (Math.abs(y - baseY) <= 2) hits++;
                }
            }
            scores[d] = hits;
        }

        int best = 0;
        for (int d = 1; d < 4; d++) {
            if (scores[d] > scores[best]) best = d;
        }
        return FlatDirection.values()[best];
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    static int surfaceY(ServerLevel level, BlockPos pos) {
        return level.getHeight(
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                pos.getX(), pos.getZ());
    }

    private static boolean isWaterAt(ServerLevel level, BlockPos pos) {
        int y = surfaceY(level, pos);
        BlockState below = level.getBlockState(
                new BlockPos(pos.getX(), y - 1, pos.getZ()));
        return below.is(Blocks.WATER) || below.liquid();
    }

    private static boolean isTreeAt(ServerLevel level, BlockPos pos, int surfY) {
        // Check for logs or leaves within 4 blocks above the surface
        for (int dy = 0; dy <= 4; dy++) {
            BlockState s = level.getBlockState(
                    new BlockPos(pos.getX(), surfY + dy, pos.getZ()));
            if (s.is(BlockTags.LOGS) || s.is(BlockTags.LEAVES)) return true;
        }
        return false;
    }

    private static FlatDirection directionTo(BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx > 0 ? FlatDirection.EAST : FlatDirection.WEST;
        } else {
            return dz > 0 ? FlatDirection.SOUTH : FlatDirection.NORTH;
        }
    }

    // =========================================================================
    // Supporting types
    // =========================================================================

    public enum FlatDirection { NORTH, SOUTH, EAST, WEST }

    /**
     * Describes a significant water body near the village site.
     */
    public record WaterBodyInfo(
            /** Centroid of all water surface blocks. */
            BlockPos centre,
            /** Nearest water block to the village origin — the shoreline point. */
            BlockPos nearestShore,
            /** Approximate radius in blocks. */
            int radius,
            /** Number of water surface sample blocks. */
            int sampleCount
    ) {}

    /**
     * Describes a ridge of elevated terrain that acts as a layout obstacle.
     */
    public record RidgeInfo(
            BlockPos min,
            BlockPos max,
            /** How many blocks above base Y the ridge peaks. */
            int heightAboveBase,
            Orientation orientation
    ) {
        public enum Orientation { NORTH_SOUTH, EAST_WEST }

        /** Returns true if the given XZ position is within this ridge's footprint. */
        public boolean contains(int x, int z) {
            return x >= min.getX() && x <= max.getX()
                    && z >= min.getZ() && z <= max.getZ();
        }

        /**
         * Returns true if a circle of {@code radius} at {@code pos} would
         * overlap this ridge's footprint with a given clearance.
         */
        public boolean overlapsCircle(BlockPos pos, int radius) {
            int nearX = Math.max(min.getX(), Math.min(max.getX(), pos.getX()));
            int nearZ = Math.max(min.getZ(), Math.min(max.getZ(), pos.getZ()));
            int dx    = pos.getX() - nearX;
            int dz    = pos.getZ() - nearZ;
            return dx * dx + dz * dz < radius * radius;
        }
    }
/**
 * Picks the cardinal direction in which terrain drops most. Returns
 * null if the terrain is roughly flat in all directions (variance
 * across cardinals < 4 blocks).
 */
private static FlatDirection detectSlopeDirection(
        ServerLevel level, BlockPos origin, int baseY) {
    int probeDist = SAMPLE_RADIUS / 2;
    int[][] dirs = {{0, -1}, {0, 1}, {1, 0}, {-1, 0}};
    FlatDirection[] dirEnums = {
            FlatDirection.NORTH, FlatDirection.SOUTH,
            FlatDirection.EAST, FlatDirection.WEST
    };

    // Sample three points along each cardinal and average them
    int[] avgDeltas = new int[4];
    for (int d = 0; d < 4; d++) {
        int sum = 0;
        int samples = 0;
        for (int dist : new int[]{probeDist / 2, probeDist * 3 / 4, probeDist}) {
            int sx = origin.getX() + dirs[d][0] * dist;
            int sz = origin.getZ() + dirs[d][1] * dist;
            int y = surfaceY(level, new BlockPos(sx, 64, sz));
            sum += (y - baseY);
            samples++;
        }
        avgDeltas[d] = sum / Math.max(1, samples);
    }

    // The "downhill" direction has the most negative delta
    int lowest = 0;
    for (int d = 1; d < 4; d++) {
        if (avgDeltas[d] < avgDeltas[lowest]) lowest = d;
    }
    // If the variance across all four directions is small, terrain
    // is essentially flat — return null so TERRACED falls back.
    int max = avgDeltas[0], min = avgDeltas[0];
    for (int d = 1; d < 4; d++) {
        if (avgDeltas[d] > max) max = avgDeltas[d];
        if (avgDeltas[d] < min) min = avgDeltas[d];
    }
    if (max - min < 4) return null;
    return dirEnums[lowest];
}

/** Magnitude of the slope as a Y-drop in blocks across SAMPLE_RADIUS/2. */
private static int computeSlopeMagnitude(
        ServerLevel level, BlockPos origin, int baseY) {
    int probeDist = SAMPLE_RADIUS / 2;
    int[][] dirs = {{0, -1}, {0, 1}, {1, 0}, {-1, 0}};
    int maxDrop = 0;
    for (int[] d : dirs) {
        int sx = origin.getX() + d[0] * probeDist;
        int sz = origin.getZ() + d[1] * probeDist;
        int y = surfaceY(level, new BlockPos(sx, 64, sz));
        int drop = baseY - y;
        if (drop > maxDrop) maxDrop = drop;
    }
    return maxDrop;
}
}