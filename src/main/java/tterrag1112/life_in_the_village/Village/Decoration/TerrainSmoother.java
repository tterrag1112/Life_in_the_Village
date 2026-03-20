// src/main/java/tterrag1112/life_in_the_village/Village/Decoration/TerrainSmoother.java
package tterrag1112.life_in_the_village.Village.Decoration;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Set;

/**
 * Handles all terrain smoothing for the village — building footprints,
 * path corridors, and the town-square pad.
 *
 * <h3>Design rules</h3>
 * <ul>
 *   <li>Never touches blocks in {@code protectedBlocks} — this is how road
 *       and path blocks are shielded from being overwritten by edge
 *       smoothing.</li>
 *   <li>Fills use biome-matched dirt/grass. Carves replace only natural
 *       terrain (grass, dirt, sand, gravel, snow) — never stone, logs,
 *       or player-placed blocks.</li>
 *   <li>Slopes are ramped, not stepped, so the transition looks natural.</li>
 *   <li>After carving, the exposed top is capped with the correct surface
 *       block (grass above dirt, sand stays sand, etc.).</li>
 * </ul>
 */
public class TerrainSmoother {

    // -------------------------------------------------------------------------
    // Public entry points
    // -------------------------------------------------------------------------

    /**
     * Smooths terrain around a building footprint.
     * The footprint itself is levelled to {@code targetY}; outside the
     * footprint a margin is blended back to the natural terrain.
     *
     * @param protectedPositions positions of road/path blocks that must
     *                           not be overwritten (may be empty)
     */
    public static void smoothAroundBuilding(ServerLevel level,
                                            BlockPos min, BlockPos max,
                                            int targetY,
                                            Set<BlockPos> protectedPositions) {
        int buildingW = max.getX() - min.getX();
        int buildingL = max.getZ() - min.getZ();
        int margin    = Math.max(6, Math.max(buildingW, buildingL) / 3);

        for (int x = min.getX() - margin; x <= max.getX() + margin; x++) {
            for (int z = min.getZ() - margin; z <= max.getZ() + margin; z++) {

                // Inside footprint — level completely
                boolean insideFootprint = x >= min.getX() && x <= max.getX()
                        && z >= min.getZ() && z <= max.getZ();

                int surfaceY = surfaceY(level, x, z);

                int desiredY;
                if (insideFootprint) {
                    desiredY = targetY;
                } else {
                    // Outside footprint — ramp from targetY back to natural
                    int distX  = Math.max(0, Math.max(min.getX() - x, x - max.getX()));
                    int distZ  = Math.max(0, Math.max(min.getZ() - z, z - max.getZ()));
                    int dist   = Math.max(distX, distZ);
                    float t    = Math.min(1f, (float) dist / margin);
                    desiredY   = (int)(targetY + t * (surfaceY - targetY));
                }

                adjustColumn(level, x, z, surfaceY, desiredY,
                        protectedPositions);
            }
        }
    }

    /**
     * Levels a rectangular pad (town square, farm plot, etc.) to a
     * flat surface at {@code targetY}. Only the pad itself is touched;
     * no margin blending.
     */
    public static void levelPad(ServerLevel level,
                                int minX, int minZ,
                                int maxX, int maxZ,
                                int targetY,
                                Set<BlockPos> protectedPositions) {
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                int surfaceY = surfaceY(level, x, z);
                adjustColumn(level, x, z, surfaceY, targetY,
                        protectedPositions);
                // Always cap with a clean surface block
                Block cap = detectSurfaceCap(level, x, z, targetY);
                BlockPos capPos = new BlockPos(x, targetY, z);
                if (!protectedPositions.contains(capPos)) {
                    level.setBlock(capPos, cap.defaultBlockState(), 3);
                }
            }
        }
    }

    /**
     * Smooths a single-block-wide corridor along a path list.
     * Ensures each path block has solid ground directly underneath it —
     * fills gaps and does not overwrite the path block itself.
     *
     * @param pathBlocks the already-placed path/road blocks
     */
    public static void supportPathBlocks(ServerLevel level,
                                         java.util.List<BlockPos> pathBlocks) {
        for (BlockPos pos : pathBlocks) {
            BlockPos below = pos.below();
            BlockState belowState = level.getBlockState(below);

            // Already supported
            if (belowState.isSolidRender()) continue;

            // Fill downward until we hit something solid
            for (int y = below.getY(); y >= below.getY() - 4; y--) {
                BlockPos fill = new BlockPos(pos.getX(), y, pos.getZ());
                BlockState s  = level.getBlockState(fill);
                if (s.isSolidRender()) break;
                if (s.isAir() || s.is(BlockTags.REPLACEABLE)) {
                    level.setBlock(fill,
                            Blocks.DIRT.defaultBlockState(), 3);
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Core column adjustment
    // -------------------------------------------------------------------------

    private static void adjustColumn(ServerLevel level,
                                     int x, int z,
                                     int surfaceY, int desiredY,
                                     Set<BlockPos> protectedPositions) {
        if (surfaceY == desiredY) return;

        Block surfaceCap    = detectSurfaceCap(level, x, z, surfaceY);
        Block subsurface    = detectSubsurface(level, x, z, surfaceY);

        if (surfaceY < desiredY) {
            // Need to fill up
            for (int y = surfaceY; y < desiredY; y++) {
                BlockPos pos = new BlockPos(x, y, z);
                if (protectedPositions.contains(pos)) break;
                BlockState existing = level.getBlockState(pos);
                if (!existing.isAir() && !existing.liquid()
                        && !existing.is(BlockTags.REPLACEABLE)
                        && !existing.is(BlockTags.FLOWERS)
                        && !existing.is(BlockTags.SMALL_FLOWERS)) break;
                boolean isTop = (y == desiredY - 1);
                level.setBlock(pos,
                        isTop ? surfaceCap.defaultBlockState()
                                : subsurface.defaultBlockState(), 3);
            }
        } else {
            // Need to carve down — only remove natural terrain
            for (int y = surfaceY - 1; y >= desiredY; y--) {
                BlockPos pos = new BlockPos(x, y, z);
                if (protectedPositions.contains(pos)) break;
                BlockState state = level.getBlockState(pos);
                if (!isNaturalTerrain(state)) break;
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            }
            // Re-cap at desiredY
            BlockPos capPos = new BlockPos(x, desiredY - 1, z);
            if (!protectedPositions.contains(capPos)) {
                BlockState atCap = level.getBlockState(capPos);
                if (atCap.is(Blocks.DIRT)) {
                    level.setBlock(capPos,
                            surfaceCap.defaultBlockState(), 3);
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    public static int surfaceY(ServerLevel level, int x, int z) {
        return level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
    }

    private static boolean isNaturalTerrain(BlockState state) {
        return state.is(Blocks.GRASS_BLOCK)
                || state.is(Blocks.DIRT)
                || state.is(Blocks.COARSE_DIRT)
                || state.is(Blocks.ROOTED_DIRT)
                || state.is(Blocks.SAND)
                || state.is(Blocks.RED_SAND)
                || state.is(Blocks.GRAVEL)
                || state.is(Blocks.SNOW_BLOCK)
                || state.is(Blocks.POWDER_SNOW)
                || state.is(BlockTags.REPLACEABLE)
                || state.is(BlockTags.FLOWERS)
                || state.is(BlockTags.SMALL_FLOWERS)
                || state.is(BlockTags.LEAVES)
                || state.isAir();
    }

    private static Block detectSurfaceCap(ServerLevel level,
                                          int x, int z, int nearY) {
        // Sample a 3×3 patch to find the most common surface block nearby
        int grass = 0, sand = 0, snow = 0, podzol = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int sy = surfaceY(level, x + dx, z + dz) - 1;
                BlockState s = level.getBlockState(
                        new BlockPos(x + dx, sy, z + dz));
                if (s.is(Blocks.SAND) || s.is(Blocks.RED_SAND)) sand++;
                else if (s.is(Blocks.SNOW_BLOCK))                snow++;
                else if (s.is(Blocks.PODZOL))                    podzol++;
                else                                              grass++;
            }
        }
        if (sand   > grass && sand   > snow) return Blocks.SAND;
        if (snow   > grass)                  return Blocks.SNOW_BLOCK;
        if (podzol > grass)                  return Blocks.PODZOL;
        return Blocks.GRASS_BLOCK;
    }

    private static Block detectSubsurface(ServerLevel level,
                                          int x, int z, int nearY) {
        BlockState below = level.getBlockState(
                new BlockPos(x, nearY - 2, z));
        if (below.is(Blocks.SAND) || below.is(Blocks.RED_SAND))
            return below.getBlock();
        return Blocks.DIRT;
    }
}