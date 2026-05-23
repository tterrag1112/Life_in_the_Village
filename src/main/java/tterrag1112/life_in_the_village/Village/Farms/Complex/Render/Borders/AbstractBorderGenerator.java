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

    /** True iff the ground block at (x, y, z) is the path surface
     *  the path renderer just stamped. Borders skip these columns
     *  so paths cross fence lines cleanly. The PathRenderer runs
     *  before borders in the orchestrator's render order. */
    protected static boolean isOnPath(ServerLevel level, int x, int y, int z) {
        return level.getBlockState(new BlockPos(x, y, z)).is(Blocks.DIRT_PATH);
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
