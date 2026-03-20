package tterrag1112.life_in_the_village.Village.Planning;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.List;

/**
 * Analyses the terrain around a candidate village origin to produce
 * a {@link TerrainProfile} that VillagePlanner uses for slot selection.
 */
public class TerrainAnalyzer {

    /** How far out (in blocks) to sample around the origin. */
    private static final int SAMPLE_RADIUS = 48;
    private static final int SAMPLE_STEP   = 4;

    // -------------------------------------------------------------------------
    // Entry point
    // -------------------------------------------------------------------------

    public static TerrainProfile analyze(ServerLevel level,
                                         BlockPos origin) {
        int samples   = 0;
        int flatCount = 0;
        int waterCount= 0;
        int steepCount= 0;

        int baseY = surfaceY(level, origin);
        int minY  = baseY;
        int maxY  = baseY;

        // Track large flat patches — good for farm plots
        List<BlockPos> flatCandidates = new ArrayList<>();

        for (int dx = -SAMPLE_RADIUS; dx <= SAMPLE_RADIUS;
             dx += SAMPLE_STEP) {
            for (int dz = -SAMPLE_RADIUS; dz <= SAMPLE_RADIUS;
                 dz += SAMPLE_STEP) {
                BlockPos sample = origin.offset(dx, 0, dz);
                int y = surfaceY(level, sample);
                int dy = Math.abs(y - baseY);

                samples++;
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);

                if (isWaterAt(level, sample)) {
                    waterCount++;
                } else if (dy <= 2) {
                    flatCount++;
                    flatCandidates.add(new BlockPos(
                            sample.getX(), y, sample.getZ()));
                } else if (dy > 6) {
                    steepCount++;
                }
            }
        }

        float flatRatio  = (float) flatCount  / samples;
        float waterRatio = (float) waterCount / samples;
        float steepRatio = (float) steepCount / samples;

        // Overall suitability — higher is better
        float suitability = flatRatio
                - waterRatio * 2.0f
                - steepRatio * 0.5f;

        // Cardinal direction that has the most flat terrain
        // — used to orient farm plots and expansion
        FlatDirection bestFlat = detectBestFlatDirection(
                level, origin, baseY);

        return new TerrainProfile(
                origin, baseY, minY, maxY,
                flatRatio, waterRatio, steepRatio,
                suitability, flatCandidates, bestFlat);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static FlatDirection detectBestFlatDirection(
            ServerLevel level, BlockPos origin, int baseY) {
        int[] scores = new int[4]; // N, S, E, W
        int check = SAMPLE_RADIUS / 2;

        int[][] dirs = {
                {0, -1}, {0, 1}, {1, 0}, {-1, 0}
        };
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
                    int y  = surfaceY(level,
                            new BlockPos(sx, 64, sz));
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

    static int surfaceY(ServerLevel level, BlockPos pos) {
        return level.getHeight(
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                pos.getX(), pos.getZ());
    }

    private static boolean isWaterAt(ServerLevel level,
                                     BlockPos pos) {
        int y = surfaceY(level, pos);
        return level.getBlockState(
                        new BlockPos(pos.getX(), y - 1, pos.getZ()))
                .is(Blocks.WATER);
    }

    // -------------------------------------------------------------------------
    // Supporting types
    // -------------------------------------------------------------------------

    public enum FlatDirection { NORTH, SOUTH, EAST, WEST }
}