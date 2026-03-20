// src/main/java/tterrag1112/life_in_the_village/Village/Planning/VillageSitePreparer.java
package tterrag1112.life_in_the_village.Village.Planning;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;

/**
 * Runs a single pre-pass over the village site before any buildings,
 * paths, or decorations are placed. Goals:
 * <ol>
 *   <li>Remove all trees (logs + leaves) in a radius around the centre.</li>
 *   <li>Fill single-block surface holes / caves so buildings don't float.</li>
 *   <li>Apply a gentle height-averaging pass to remove extreme spikes/pits
 *       that would confuse the layout planner and terrain smoother.</li>
 * </ol>
 *
 * This runs entirely before {@link
 * tterrag1112.life_in_the_village.Village.Decoration.VillageDecorator}
 * so every subsequent pass works on clean, tree-free terrain.
 */
public class VillageSitePreparer {

    // Radius of the full preparation zone around the village centre
    private static final int PREP_RADIUS     = 80;
    // How many smoothing iterations to run over the site
    private static final int SMOOTH_PASSES   = 2;
    // Maximum height change per smoothing step per column
    private static final int MAX_STEP        = 2;

    // -------------------------------------------------------------------------
    // Entry point
    // -------------------------------------------------------------------------

    public static void prepare(ServerLevel level, VillageLayout layout) {
        BlockPos centre = layout.getCenter();
        System.out.println("VillageSitePreparer: preparing site at " + centre);

        // Pass 1 — clear all trees
        clearTrees(level, centre, PREP_RADIUS);

        // Pass 2 — fill surface caves / one-block holes
        fillSurfaceHoles(level, centre, PREP_RADIUS);

        // Pass 3 — gentle height smoothing (repeated)
        for (int i = 0; i < SMOOTH_PASSES; i++) {
            smoothHeights(level, centre, PREP_RADIUS);
        }

        System.out.println("VillageSitePreparer: site preparation complete");
    }

    // -------------------------------------------------------------------------
    // Pass 1: tree clearing
    // -------------------------------------------------------------------------

    // In VillageSitePreparer.java — replace clearTrees() and clearTreeColumn() entirely:

    // -------------------------------------------------------------------------
    // Pass 1: tree clearing — flood fill from every log block
    // -------------------------------------------------------------------------

    private static void clearTrees(ServerLevel level,
                                   BlockPos centre, int radius) {
        // Collect every log/leaves block in the radius first,
        // then flood-fill outward from each log to catch the full canopy.
        Set<BlockPos> toRemove = new HashSet<>();
        Set<BlockPos> visited  = new HashSet<>();

        int radiusSq = radius * radius;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radiusSq) continue;
                int x      = centre.getX() + dx;
                int z      = centre.getZ() + dz;
                int surfY  = level.getHeight(
                        Heightmap.Types.WORLD_SURFACE, x, z);

                // Scan from well above surface down through the trunk
                for (int y = surfY + 24; y >= surfY - 8; y--) {
                    BlockPos pos   = new BlockPos(x, y, z);
                    BlockState s   = level.getBlockState(pos);
                    if (isTreeBlock(s) && !visited.contains(pos)) {
                        // Flood-fill the whole tree from this seed
                        floodFillTree(level, pos, toRemove, visited);
                    }
                }

                // Also clear surface-level plants in the radius
                BlockPos surfPos = new BlockPos(x, surfY, z);
                BlockState surf  = level.getBlockState(surfPos);
                if (isSurfacePlant(surf)) toRemove.add(surfPos);
            }
        }

        // Remove all collected blocks in one pass
        for (BlockPos pos : toRemove) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        }

        System.out.println("VillageSitePreparer: cleared "
                + toRemove.size() + " tree/plant blocks");
    }

    /**
     * Flood-fills outward from a seed log or leaf block, collecting
     * all connected tree blocks (logs, leaves, vines attached to logs)
     * into {@code toRemove}. Uses BFS with a 64-block reach cap to
     * avoid runaway fills in jungle biomes.
     */
    private static void floodFillTree(ServerLevel level,
                                      BlockPos seed,
                                      Set<BlockPos> toRemove,
                                      Set<BlockPos> visited) {
        Queue<BlockPos> queue = new ArrayDeque<>();
        queue.add(seed);
        visited.add(seed);

        int limit = 2048; // max blocks per tree — enough for a jungle tree
        int count = 0;

        while (!queue.isEmpty() && count < limit) {
            BlockPos current = queue.poll();
            toRemove.add(current);
            count++;

            // Check all 26 neighbours (6 faces + edges + corners)
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        BlockPos neighbour = current.offset(dx, dy, dz);
                        if (visited.contains(neighbour)) continue;
                        BlockState ns = level.getBlockState(neighbour);
                        if (isTreeBlock(ns)) {
                            visited.add(neighbour);
                            queue.add(neighbour);
                        }
                    }
                }
            }
        }
    }

    /** Returns true for blocks that are part of a tree structure. */
    private static boolean isTreeBlock(BlockState s) {
        return s.is(BlockTags.LOGS)
                || s.is(BlockTags.LEAVES)
                || s.is(BlockTags.WART_BLOCKS)       // nether trees
                || s.is(Blocks.SHROOMLIGHT)           // nether trees
                || s.is(Blocks.BEE_NEST)              // often attached to oak
                || s.is(Blocks.VINE)
                || s.is(Blocks.COCOA)
                || s.is(Blocks.BAMBOO)
                || s.is(Blocks.BAMBOO_SAPLING);
    }

    /** Returns true for surface-level plants that should be cleared. */
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

    // -------------------------------------------------------------------------
    // Pass 2: fill surface caves / single-block holes
    // -------------------------------------------------------------------------

    /**
     * For each column, if there is a 1–2 block gap between the surface
     * and the block below it, fills that gap with dirt so buildings
     * don't generate with open air beneath their foundations.
     */
    private static void fillSurfaceHoles(ServerLevel level,
                                         BlockPos centre, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius) continue;
                int x = centre.getX() + dx;
                int z = centre.getZ() + dz;
                int surfY = level.getHeight(
                        Heightmap.Types.WORLD_SURFACE, x, z);

                // Check the two blocks below the surface
                for (int depth = 1; depth <= 3; depth++) {
                    BlockPos below = new BlockPos(x, surfY - depth, z);
                    BlockState s   = level.getBlockState(below);
                    if (s.isAir() || s.liquid()) {
                        // Fill upward to just below the surface cap
                        level.setBlock(below,
                                Blocks.DIRT.defaultBlockState(), 3);
                    } else {
                        break; // solid — stop descending
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Pass 3: gentle height smoothing
    // -------------------------------------------------------------------------

    /**
     * Averages the surface height of each column with its cardinal
     * neighbours. Only modifies natural terrain blocks (grass, dirt,
     * sand, gravel). Never touches stone or ores.
     * Runs in two sub-passes (sample then apply) to avoid order bias.
     */
    private static void smoothHeights(ServerLevel level,
                                      BlockPos centre, int radius) {
        // Sub-pass A: sample current heights into an array
        int size   = radius * 2 + 1;
        int[] heights = new int[size * size];

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int idx = (dx + radius) * size + (dz + radius);
                heights[idx] = level.getHeight(Heightmap.Types.WORLD_SURFACE,
                        centre.getX() + dx, centre.getZ() + dz);
            }
        }

        // Sub-pass B: apply averaged target heights
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius) continue;

                int cx = centre.getX() + dx;
                int cz = centre.getZ() + dz;
                int idx = (dx + radius) * size + (dz + radius);
                int currentY = heights[idx];

                // Average with 4 cardinal neighbours (clamp to array bounds)
                int sum   = currentY;
                int count = 1;
                int[][] dirs = {{1,0},{-1,0},{0,1},{0,-1}};
                for (int[] d : dirs) {
                    int ni = (dx + d[0] + radius) * size + (dz + d[1] + radius);
                    if (ni >= 0 && ni < heights.length) {
                        sum += heights[ni];
                        count++;
                    }
                }
                int targetY = sum / count;

                // Clamp the change to MAX_STEP per pass
                targetY = Math.max(currentY - MAX_STEP,
                        Math.min(currentY + MAX_STEP, targetY));

                if (targetY == currentY) continue;

                adjustSurfaceColumn(level, cx, cz, currentY, targetY);
            }
        }
    }

    private static void adjustSurfaceColumn(ServerLevel level,
                                            int x, int z,
                                            int currentY, int targetY) {
        if (targetY > currentY) {
            // Fill — place dirt/grass up to targetY
            for (int y = currentY; y < targetY; y++) {
                BlockPos pos   = new BlockPos(x, y, z);
                BlockState s   = level.getBlockState(pos);
                if (s.isAir() || s.is(BlockTags.REPLACEABLE)) {
                    boolean isTop = (y == targetY - 1);
                    level.setBlock(pos, isTop
                            ? detectCap(level, x, z, currentY)
                            .defaultBlockState()
                            : Blocks.DIRT.defaultBlockState(), 3);
                }
            }
        } else {
            // Carve — remove natural blocks down to targetY
            for (int y = currentY - 1; y >= targetY; y--) {
                BlockPos pos   = new BlockPos(x, y, z);
                BlockState s   = level.getBlockState(pos);
                if (isNaturalSurface(s)) {
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                } else {
                    break;
                }
            }
            // Re-cap the newly exposed top
            BlockPos newTop = new BlockPos(x, targetY - 1, z);
            BlockState topState = level.getBlockState(newTop);
            if (topState.is(Blocks.DIRT) || topState.is(Blocks.COARSE_DIRT)) {
                level.setBlock(newTop,
                        detectCap(level, x, z, currentY).defaultBlockState(), 3);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static boolean isNaturalSurface(BlockState s) {
        return s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.DIRT)
                || s.is(Blocks.COARSE_DIRT) || s.is(Blocks.ROOTED_DIRT)
                || s.is(Blocks.SAND) || s.is(Blocks.RED_SAND)
                || s.is(Blocks.GRAVEL) || s.is(Blocks.SNOW_BLOCK)
                || s.is(Blocks.POWDER_SNOW)
                || s.is(BlockTags.REPLACEABLE) || s.isAir();
    }

    private static net.minecraft.world.level.block.Block detectCap(
            ServerLevel level, int x, int z, int nearY) {
        // Sample nearby surface to match biome cap
        for (int dy = 0; dy <= 3; dy++) {
            BlockState s = level.getBlockState(new BlockPos(x, nearY - dy, z));
            if (s.is(Blocks.SAND) || s.is(Blocks.RED_SAND))
                return s.getBlock();
            if (s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.PODZOL))
                return s.getBlock();
        }
        return Blocks.GRASS_BLOCK;
    }
}