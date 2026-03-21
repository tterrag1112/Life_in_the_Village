// src/main/java/tterrag1112/life_in_the_village/Village/Planning/BuildingFoundation.java
package tterrag1112.life_in_the_village.Village.Planning;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import tterrag1112.life_in_the_village.Village.Building;

/**
 * Places natural-looking foundations under each building so structures
 * appear to have been built on the terrain rather than dropped onto it.
 *
 * <h3>Foundation rules</h3>
 * <ul>
 *   <li><b>Uphill side</b> (terrain higher than building floor):
 *       carve the terrain down to floor level. The exposed face gets
 *       a stone/cobblestone retaining wall 1 block thick.</li>
 *   <li><b>Downhill side</b> (terrain lower than building floor):
 *       place oak fence or stone pillar columns downward from the
 *       building floor to the terrain, up to {@link #MAX_STILT_HEIGHT}
 *       blocks. Beyond that, fill with tapered cobblestone.</li>
 *   <li><b>Flat ground</b>: no action — the building sits cleanly.</li>
 * </ul>
 *
 * <h3>Taper rule</h3>
 * The fill/stilt area extends outward from the building footprint by
 * one block per block of height difference, creating a natural slope
 * rather than a cliff face.
 */
public class BuildingFoundation {

    /** Maximum drop before switching from stilts to fill. */
    private static final int MAX_STILT_HEIGHT = 3;
    /** Border of columns/fill outside the footprint. */
    private static final int FOUNDATION_BORDER = 1;

    // =========================================================================
    // Entry point
    // =========================================================================

    /**
     * Places the foundation for a single building. Call this after
     * the structure NBT has been placed in the world, so the building
     * shape is known.
     */
    public static void place(ServerLevel level, Building building) {
        BlockPos min    = building.getShape().getMin();
        BlockPos max    = building.getShape().getMax();
        int      floorY = building.getShape().getOrigin().getY();

        // Scan the footprint plus a 1-block border
        for (int x = min.getX() - FOUNDATION_BORDER;
             x <= max.getX() + FOUNDATION_BORDER; x++) {
            for (int z = min.getZ() - FOUNDATION_BORDER;
                 z <= max.getZ() + FOUNDATION_BORDER; z++) {

                int surfY = level.getHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);

                boolean onBorder = x < min.getX() || x > max.getX()
                        || z < min.getZ() || z > max.getZ();

                if (surfY < floorY) {
                    // Terrain is lower — place a foundation column
                    placeFoundationColumn(level, x, z,
                            surfY, floorY, onBorder);
                } else if (surfY > floorY) {
                    // Terrain is higher — carve and add retaining face
                    carveAndRetain(level, x, z,
                            surfY, floorY, onBorder,
                            min, max);
                }
                // surfY == floorY — flush, nothing to do
            }
        }
    }

    // =========================================================================
    // Column placement
    // =========================================================================

    /**
     * Places a foundation column rising from {@code surfY} up to
     * {@code floorY - 1} (so the building floor rests on solid ground).
     *
     * <ul>
     *   <li>Drop ≤ {@code MAX_STILT_HEIGHT}: oak fence stilts — visually
     *       light, reads as a raised floor</li>
     *   <li>Drop > {@code MAX_STILT_HEIGHT}: cobblestone fill — solid
     *       platform, reads as built-up ground</li>
     * </ul>
     */
    private static void placeFoundationColumn(ServerLevel level,
                                              int x, int z,
                                              int surfY, int floorY,
                                              boolean onBorder) {
        int drop = floorY - surfY;

        if (drop <= MAX_STILT_HEIGHT) {
            // Stilts: fence posts down from floorY - 1
            for (int y = surfY; y < floorY; y++) {
                BlockPos pos = new BlockPos(x, y, z);
                BlockState s = level.getBlockState(pos);
                if (s.isAir() || s.is(BlockTags.REPLACEABLE)) {
                    // Use stone pillar on border, fence inside
                    level.setBlock(pos, onBorder
                            ? Blocks.COBBLESTONE.defaultBlockState()
                            : Blocks.OAK_FENCE.defaultBlockState(), 3);
                }
            }
        } else {
            // Fill: taper the fill so it steps out 1 block per Y level
            int fillWidth = Math.min(drop - MAX_STILT_HEIGHT,
                    FOUNDATION_BORDER + 1);
            for (int y = surfY; y < floorY; y++) {
                BlockPos pos = new BlockPos(x, y, z);
                BlockState s = level.getBlockState(pos);
                if (s.isAir() || s.is(BlockTags.REPLACEABLE)
                        || s.liquid()) {
                    boolean isTop = (y == floorY - 1);
                    level.setBlock(pos, isTop
                            ? Blocks.GRASS_BLOCK.defaultBlockState()
                            : Blocks.COBBLESTONE.defaultBlockState(), 3);
                }
            }
        }
    }

    // =========================================================================
    // Carve + retaining wall
    // =========================================================================

    /**
     * Carves terrain down to {@code floorY} on the uphill side and
     * places a single-block-thick cobblestone retaining wall on the
     * exposed face adjacent to the building.
     */
    private static void carveAndRetain(ServerLevel level,
                                       int x, int z,
                                       int surfY, int floorY,
                                       boolean onBorder,
                                       BlockPos min, BlockPos max) {
        // Carve natural terrain down to floor level
        for (int y = surfY; y >= floorY; y--) {
            BlockPos pos = new BlockPos(x, y, z);
            BlockState s = level.getBlockState(pos);
            if (isNatural(s)) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            } else {
                break;
            }
        }

        // Re-cap the new top
        BlockPos cap = new BlockPos(x, floorY - 1, z);
        if (level.getBlockState(cap).is(Blocks.DIRT)
                || level.getBlockState(cap).is(Blocks.COARSE_DIRT)) {
            level.setBlock(cap,
                    Blocks.GRASS_BLOCK.defaultBlockState(), 3);
        }

        // Place retaining wall on the exposed face if this column is
        // directly adjacent to the building footprint
        if (onBorder) {
            int rise = surfY - floorY;
            if (rise >= 2) {
                // Place cobblestone slab or wall on the border column
                // at floor level to retain the cut face
                BlockPos wallPos = new BlockPos(x, floorY, z);
                if (level.getBlockState(wallPos).isAir()) {
                    level.setBlock(wallPos,
                            rise >= 3
                                    ? Blocks.COBBLESTONE_WALL.defaultBlockState()
                                    : Blocks.COBBLESTONE_SLAB.defaultBlockState(),
                            3);
                }
            }
        }
    }

    private static boolean isNatural(BlockState s) {
        return s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.DIRT)
                || s.is(Blocks.COARSE_DIRT) || s.is(Blocks.ROOTED_DIRT)
                || s.is(Blocks.SAND) || s.is(Blocks.RED_SAND)
                || s.is(Blocks.GRAVEL) || s.is(Blocks.SNOW_BLOCK)
                || s.is(BlockTags.REPLACEABLE) || s.isAir();
    }
}