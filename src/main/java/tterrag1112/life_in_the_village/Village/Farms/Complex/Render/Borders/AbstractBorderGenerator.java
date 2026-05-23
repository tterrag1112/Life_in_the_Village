package tterrag1112.life_in_the_village.Village.Farms.Complex.Render.Borders;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluids;

import java.util.Random;

/**
 * Common framework for the four border styles. Subclasses only
 * implement {@link #renderColumn} — this base handles the line
 * walk, ground-Y lookup, water/lava skipping, and the
 * "is-it-safe-to-replace-this-block" check.
 *
 * <p>Line walking uses a simple Bresenham over XZ. Each step lands
 * on a discrete world-XZ; for that column we resolve ground via
 * the level's WORLD_SURFACE heightmap (drops back to MOTION_-
 * BLOCKING_NO_LEAVES if the surface block is a leaf — wouldn't
 * want a fence floating on tree canopy).
 *
 * <p>Replaceability: the column is considered paintable iff every
 * destination Y position is currently air, a plant, or otherwise
 * a "soft" block. Solid blocks (a wall of an adjacent building,
 * existing stone, etc.) are not over-written.
 */
public abstract class AbstractBorderGenerator implements BorderGenerator {

    /** Maximum world-Y at which we'll place a border block.
     *  Borders 60 blocks in the air are nonsensical; this guards
     *  against pathological terrain. */
    protected static final int MAX_BUILD_Y = 250;

    /** Per-column hook. Called once per XZ step along the edge.
     *  {@code groundY} is the surface block Y (the block under the
     *  border's foot). The implementation places blocks at
     *  {@code groundY + 1} and above per its style.
     *
     *  @param level         target level
     *  @param x             world X
     *  @param z             world Z
     *  @param groundY       surface block Y resolved by the base
     *  @param outwardNormal points away from plot interior
     *  @param stepIndex     0-indexed step along this edge — used
     *                       by styles that vary by stride
     *                       (post-and-rail's "every 6th column is
     *                       a post"). */
    protected abstract void renderColumn(ServerLevel level,
                                         int x, int z, int groundY,
                                         Direction outwardNormal,
                                         int stepIndex,
                                         Random rng);

    @Override
    public void renderEdge(BlockPos start, BlockPos end,
                            Direction outwardNormal,
                            ServerLevel level,
                            Random rng) {
        int x0 = start.getX(), z0 = start.getZ();
        int x1 = end.getX(),   z1 = end.getZ();
        int dx = Math.abs(x1 - x0), dz = Math.abs(z1 - z0);
        int sx = x0 < x1 ? 1 : -1, sz = z0 < z1 ? 1 : -1;
        int err = dx - dz;
        int step = 0;
        while (true) {
            int gy = resolveGroundY(level, x0, z0);
            if (gy > 0 && gy < MAX_BUILD_Y && !isOnPath(level, x0, gy, z0)) {
                clearHeadSpace(level, x0, gy, z0);
                renderColumn(level, x0, z0, gy, outwardNormal, step, rng);
            }
            if (x0 == x1 && z0 == z1) break;
            int e2 = 2 * err;
            if (e2 > -dz) { err -= dz; x0 += sx; }
            if (e2 <  dx) { err += dx; z0 += sz; }
            step++;
            if (step > 4096) break; // sanity belt
        }
    }

    /** Clear non-air blocks from {@code groundY+1} up to
     *  {@code groundY+HEAD_CLEARANCE} so the border isn't capped
     *  by overhanging tree leaves / branches / saplings. Matches
     *  RoadPainter's ROAD_HEAD_CLEARANCE pattern.
     *
     *  <p>Stops at the first solid non-replaceable block above
     *  the clearance ceiling — won't smash through stone or
     *  through an adjacent building's roof. */
    private static final int HEAD_CLEARANCE = 3;

    private static void clearHeadSpace(ServerLevel level, int x, int groundY, int z) {
        for (int dy = 1; dy <= HEAD_CLEARANCE; dy++) {
            BlockPos p = new BlockPos(x, groundY + dy, z);
            BlockState cur = level.getBlockState(p);
            if (cur.isAir()) continue;
            // Stop at first solid block we shouldn't replace; matches
            // the path renderer's selective-clear policy so we don't
            // tear up building walls.
            if (!cur.canBeReplaced() && !isVegetation(cur)) continue;
            level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
        }
    }

    private static boolean isVegetation(BlockState s) {
        return s.is(Blocks.OAK_LEAVES)
                || s.is(Blocks.BIRCH_LEAVES)
                || s.is(Blocks.SPRUCE_LEAVES)
                || s.is(Blocks.JUNGLE_LEAVES)
                || s.is(Blocks.DARK_OAK_LEAVES)
                || s.is(Blocks.ACACIA_LEAVES)
                || s.is(Blocks.OAK_LOG)
                || s.is(Blocks.BIRCH_LOG)
                || s.is(Blocks.SPRUCE_LOG)
                || s.is(Blocks.JUNGLE_LOG)
                || s.is(Blocks.DARK_OAK_LOG)
                || s.is(Blocks.ACACIA_LOG)
                || s.is(Blocks.OAK_SAPLING)
                || s.is(Blocks.SHORT_GRASS)
                || s.is(Blocks.TALL_GRASS)
                || s.is(Blocks.FERN)
                || s.is(Blocks.LARGE_FERN);
    }

    /** Resolve the ground block Y at {@code (x, z)}. WORLD_SURFACE
     *  includes leaves which would float fences on tree canopies;
     *  fall back to MOTION_BLOCKING_NO_LEAVES if the WORLD_SURFACE
     *  top is a leaf. */
    protected static int resolveGroundY(ServerLevel level, int x, int z) {
        int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
        BlockState top = level.getBlockState(new BlockPos(x, y, z));
        if (top.is(Blocks.OAK_LEAVES)
                || top.is(Blocks.BIRCH_LEAVES)
                || top.is(Blocks.SPRUCE_LEAVES)
                || top.is(Blocks.JUNGLE_LEAVES)
                || top.is(Blocks.DARK_OAK_LEAVES)
                || top.is(Blocks.ACACIA_LEAVES)) {
            y = level.getHeight(
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        }
        return y;
    }

    /** True iff the ground block at (x, y, z) is a path-surface
     *  material the {@link tterrag1112.life_in_the_village.Village.Farms.Complex.Render.PathRenderer}
     *  might have stamped. Borders skip these columns so paths
     *  cross fence lines cleanly. PathRenderer runs before borders
     *  in the orchestrator's render order.
     *
     *  <p>Accepts any of the road materials cultures commonly
     *  configure via {@code planningBias.roadMaterial} so the
     *  check stays correct under per-culture material variation.
     *  Default DIRT_PATH is the common case; the others cover
     *  cultures with stone-paved or gravel roads. */
    protected static boolean isOnPath(ServerLevel level, int x, int y, int z) {
        BlockState s = level.getBlockState(new BlockPos(x, y, z));
        return s.is(Blocks.DIRT_PATH)
                || s.is(Blocks.GRAVEL)
                || s.is(Blocks.COARSE_DIRT)
                || s.is(Blocks.SMOOTH_STONE)
                || s.is(Blocks.STONE_BRICKS);
    }

    /** Safe replacement: only set the block if the current state
     *  is air or a soft / plant-like block. Returns true iff the
     *  set happened. */
    protected static boolean placeIfSoft(ServerLevel level, BlockPos pos, BlockState state) {
        BlockState cur = level.getBlockState(pos);
        if (cur.isAir()) {
            return level.setBlock(pos, state, 3);
        }
        if (cur.getFluidState().getType() != Fluids.EMPTY) return false;
        if (cur.canBeReplaced()) {
            return level.setBlock(pos, state, 3);
        }
        return false;
    }
}
