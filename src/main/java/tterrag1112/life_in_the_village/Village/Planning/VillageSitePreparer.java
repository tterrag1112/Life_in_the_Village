// src/main/java/tterrag1112/life_in_the_village/Village/Planning/VillageSitePreparer.java
package tterrag1112.life_in_the_village.Village.Planning;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.*;

/**
 * Pre-processes the village site before the layout planner runs.
 * Three passes:
 * <ol>
 *   <li>Tree clearing — flood-fill from every log/leaf block</li>
 *   <li>Surface hole filling — closes 1–3 block drops beneath the
 *       surface cap so buildings don't generate over open air</li>
 *   <li>Gentle height averaging — 2 passes capped at 2 blocks each,
 *       removing spikes and shallow pits</li>
 * </ol>
 *
 * <h3>Y convention</h3>
 * Uses {@code MOTION_BLOCKING_NO_LEAVES} throughout (same as
 * {@code RoadRouter} and {@code VillagePlanner}) so every Y value
 * in the system refers to the same solid surface block.
 */
public class VillageSitePreparer {

    private static final int SMOOTH_PASSES = 2;
    private static final int MAX_STEP      = 2; // max height change per pass
    private static final int RIDGE_THRESHOLD = 8; // matches TerrainAnalyzer.RIDGE_THRESHOLD


    // =========================================================================
    // Entry point
    // =========================================================================

    /**
     * @param centre       solid surface position at the village centre
     * @param villageLevel 1–10, scales the preparation radius
     */
    public static void prepare(ServerLevel level,
                               BlockPos centre,
                               int villageLevel) {
        prepare(level, centre, villageLevel, false);
    }


    private static void prepare(ServerLevel level,
                                BlockPos centre,
                                int villageLevel,
                                boolean isCapital) {
        // Capitals get a larger radius and more aggressive smoothing
        int radius      = isCapital
                ? Math.max(180, 120 + villageLevel * 6)
                : Math.max(72, 56 + villageLevel * 8);
        int smoothPasses = isCapital ? 5 : SMOOTH_PASSES;
        int maxStep      = isCapital ? 4 : MAX_STEP;

        System.out.println("VillageSitePreparer: preparing radius="
                + radius + (isCapital ? " [CAPITAL]" : "") + " at " + centre);

        clearTrees(level, centre, radius);
        fillSurfaceHoles(level, centre, radius);
        for (int i = 0; i < smoothPasses; i++) {
            smoothHeights(level, centre, radius, maxStep);
        }

        System.out.println("VillageSitePreparer: site preparation complete");
    }

    // =========================================================================
    // Pass 1: tree clearing
    // =========================================================================

    private static void clearTrees(ServerLevel level,
                                   BlockPos centre, int radius) {
        Set<BlockPos> toRemove = new HashSet<>();
        Set<BlockPos> visited  = new HashSet<>();
        int rSq = radius * radius;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > rSq) continue;
                int x     = centre.getX() + dx;
                int z     = centre.getZ() + dz;
                int surfY = level.getHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);

                // Scan well above surface to catch tall canopies
                for (int y = surfY + 24; y >= surfY - 4; y--) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState s = level.getBlockState(pos);
                    if (isTreeBlock(s) && !visited.contains(pos)) {
                        floodFillTree(level, pos, toRemove, visited);
                    }
                }

                // Clear surface plants
                BlockPos surfPos = new BlockPos(x, surfY, z);
                if (isSurfacePlant(level.getBlockState(surfPos))) {
                    toRemove.add(surfPos);
                }
                // Also clear one above in case of tall grass
                BlockPos aboveSurf = new BlockPos(x, surfY + 1, z);
                if (isSurfacePlant(level.getBlockState(aboveSurf))) {
                    toRemove.add(aboveSurf);
                }
            }
        }

        for (BlockPos pos : toRemove) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 18);
        }

        System.out.println("VillageSitePreparer: cleared "
                + toRemove.size() + " tree/plant blocks");
    }

    private static void floodFillTree(ServerLevel level,
                                      BlockPos seed,
                                      Set<BlockPos> toRemove,
                                      Set<BlockPos> visited) {
        Queue<BlockPos> queue = new ArrayDeque<>();
        queue.add(seed);
        visited.add(seed);

        int limit = 2048;
        int count = 0;

        while (!queue.isEmpty() && count < limit) {
            BlockPos cur = queue.poll();
            toRemove.add(cur);
            count++;

            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        BlockPos nb = cur.offset(dx, dy, dz);
                        if (visited.contains(nb)) continue;
                        if (isTreeBlock(level.getBlockState(nb))) {
                            visited.add(nb);
                            queue.add(nb);
                        }
                    }
                }
            }
        }
    }

    private static boolean isTreeBlock(BlockState s) {
        return s.is(BlockTags.LOGS)
                || s.is(BlockTags.LEAVES)
                || s.is(BlockTags.WART_BLOCKS)
                || s.is(Blocks.SHROOMLIGHT)
                || s.is(Blocks.BEE_NEST)
                || s.is(Blocks.VINE)
                || s.is(Blocks.COCOA)
                || s.is(Blocks.BAMBOO)
                || s.is(Blocks.BAMBOO_SAPLING);
    }

    private static boolean isSurfacePlant(BlockState s) {
        return s.is(BlockTags.FLOWERS)
                || s.is(BlockTags.SMALL_FLOWERS)
                || s.is(BlockTags.REPLACEABLE)
                || s.is(Blocks.SUGAR_CANE)
                || s.is(Blocks.TALL_GRASS)
                || s.is(Blocks.SHORT_GRASS)
                || s.is(Blocks.DEAD_BUSH)
                || s.is(Blocks.FERN)
                || s.is(Blocks.LARGE_FERN);
    }

    // =========================================================================
    // Pass 2: fill surface holes
    // =========================================================================

    /**
     * Fills 1–3 block gaps immediately below the solid surface cap.
     * MOTION_BLOCKING_NO_LEAVES returns the solid block Y, so
     * {@code surfY} IS the surface block. We check from {@code surfY - 1}
     * downward for air gaps.
     */
    private static void fillSurfaceHoles(ServerLevel level,
                                         BlockPos centre, int radius) {
        int rSq = radius * radius;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > rSq) continue;
                int x     = centre.getX() + dx;
                int z     = centre.getZ() + dz;
                int surfY = level.getHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);

                // Fill gaps beneath the surface (depth 1 = block below surface cap)
                for (int depth = 1; depth <= 3; depth++) {
                    BlockPos below = new BlockPos(x, surfY - depth, z);
                    BlockState s   = level.getBlockState(below);
                    if (s.isAir()) {
                        level.setBlock(below,
                                Blocks.DIRT.defaultBlockState(), 18);
                    } else {
                        break; // solid — no gap here
                    }
                }
            }
        }
    }

    // =========================================================================
    // Pass 3: gentle height smoothing
    // =========================================================================

    /**
     * Two-phase cardinal-neighbour averaging using
     * {@code MOTION_BLOCKING_NO_LEAVES}.
     * Phase A samples heights; Phase B applies clamped adjustments.
     * Only modifies natural terrain blocks.
     */
    private static void smoothHeights(ServerLevel level,
                                      BlockPos centre, int radius, int maxStep) {
        int size    = radius * 2 + 1;
        int[] heights = new int[size * size];

        // Phase A: sample
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int idx = (dx + radius) * size + (dz + radius);
                heights[idx] = level.getHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        centre.getX() + dx, centre.getZ() + dz);
            }
        }

        // Phase B: apply
        int rSq = radius * radius;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > rSq) continue;

                int cx  = centre.getX() + dx;
                int cz  = centre.getZ() + dz;
                int idx = (dx + radius) * size + (dz + radius);
                int currentY = heights[idx];

                // Average with 4 cardinal neighbours
                int sum = currentY, count = 1;
                for (int[] d : new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
                    int ni = (dx + d[0] + radius) * size + (dz + d[1] + radius);
                    if (ni >= 0 && ni < heights.length) {
                        sum += heights[ni];
                        count++;
                    }
                }



                int targetY = sum / count;
                // Clamp change per pass
                targetY = Math.max(currentY - maxStep,
                        Math.min(currentY + maxStep, targetY));

                int dy = Math.abs(targetY - currentY);
                int effectiveMaxStep = dy > RIDGE_THRESHOLD / 2
                        ? 1          // steep column — preserve more shape
                        : maxStep;  // normal column — full smoothing

                targetY = Math.max(currentY - effectiveMaxStep,
                        Math.min(currentY + effectiveMaxStep, targetY));

                if (targetY == currentY) continue;
                adjustColumn(level, cx, cz, currentY, targetY);
            }
        }
    }
    private static void smoothHeights(ServerLevel level,
                                      BlockPos centre, int radius) {
        smoothHeights(level, centre, radius, MAX_STEP);
    }

    /**
     * Fills or carves a single column to reach {@code targetY}.
     *
     * <p>Convention: {@code currentY} and {@code targetY} are both
     * solid block Y values (MOTION_BLOCKING_NO_LEAVES).
     * <ul>
     *   <li>Fill: place dirt from {@code currentY + 1} up to
     *       {@code targetY}, cap with the biome surface block.</li>
     *   <li>Carve: remove natural blocks from {@code currentY} down
     *       to {@code targetY + 1}, re-cap at {@code targetY}.</li>
     * </ul>
     */
    private static void adjustColumn(ServerLevel level,
                                     int x, int z,
                                     int currentY, int targetY) {
        if (targetY > currentY) {
            // Fill: currentY is solid, place new blocks above it
            for (int y = currentY + 1; y <= targetY; y++) {
                BlockPos pos = new BlockPos(x, y, z);
                BlockState s = level.getBlockState(pos);
                if (s.isAir() || s.is(BlockTags.REPLACEABLE)) {
                    boolean isTop = (y == targetY);
                    level.setBlock(pos, isTop
                            ? capBlock(level, x, z, currentY)
                            : Blocks.DIRT.defaultBlockState(), 18);
                }
            }
        } else {
            // Carve: remove blocks from currentY down to targetY + 1
            for (int y = currentY; y > targetY; y--) {
                BlockPos pos = new BlockPos(x, y, z);
                BlockState s = level.getBlockState(pos);
                if (isNaturalTerrain(s)) {
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), 18);
                } else {
                    break; // hit stone or player-placed block
                }
            }
            // Re-cap at the new top (targetY)
            BlockPos newTop = new BlockPos(x, targetY, z);
            BlockState top  = level.getBlockState(newTop);
            if (top.is(Blocks.DIRT) || top.is(Blocks.COARSE_DIRT)) {
                level.setBlock(newTop, capBlock(level, x, z, currentY), 18);
            }
        }
    }

    private static BlockState capBlock(ServerLevel level, int x, int z, int nearY) {
        // Sample a few blocks nearby to match the biome surface type
        for (int dy = 0; dy <= 4; dy++) {
            BlockState s = level.getBlockState(new BlockPos(x, nearY - dy, z));
            if (s.is(Blocks.SAND) || s.is(Blocks.RED_SAND))
                return s;
            if (s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.PODZOL)
                    || s.is(Blocks.MYCELIUM))
                return s;
        }
        return Blocks.GRASS_BLOCK.defaultBlockState();
    }

    private static boolean isNaturalTerrain(BlockState s) {
        return s.is(Blocks.GRASS_BLOCK)
                || s.is(Blocks.DIRT)
                || s.is(Blocks.COARSE_DIRT)
                || s.is(Blocks.ROOTED_DIRT)
                || s.is(Blocks.SAND)
                || s.is(Blocks.RED_SAND)
                || s.is(Blocks.GRAVEL)
                || s.is(Blocks.SNOW_BLOCK)
                || s.is(Blocks.POWDER_SNOW)
                || s.is(BlockTags.REPLACEABLE)
                || s.isAir();
    }
}