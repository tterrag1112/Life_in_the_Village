package tterrag1112.life_in_the_village.Village.Farms.Complex.Render.Borders;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Random;

/**
 * Oak fence with periodic oak-log posts.
 *
 * <p>The fence itself is the entire visual (1 block tall but a
 * fence collides at 1.5 blocks — unjumpable). Roughly every 8
 * cells (~12.5%) gets an oak-log post (1 block, solid) instead
 * of a fence. Posts give the line its visual rhythm; no slab cap
 * needed.
 *
 * <p>Post selection uses a deterministic position hash rather
 * than the old "every Nth step along the edge" — the orchestrator
 * now drives per-cell dispatch (no edge walk to step along) but
 * post placement still needs to be sparse and deterministic.
 */
public final class PostAndRailBorder extends AbstractBorderGenerator {

    private static final BlockState OAK_FENCE =
            Blocks.OAK_FENCE.defaultBlockState();
    private static final BlockState OAK_LOG =
            Blocks.OAK_LOG.defaultBlockState();

    @Override
    protected void renderColumn(ServerLevel level,
                                 int x, int z, int groundY,
                                 Direction outwardNormal,
                                 int stepIndex,
                                 Random rng) {
        // Position-hash post selection: roughly 1-in-8 cells get a
        // log post. Deterministic per world coords, seed-stable
        // (no RNG draw), spacing is approximate but visually
        // similar to the old "every 6 along the edge" rhythm.
        int h = (x * 73856093) ^ (z * 19349663);
        boolean post = (h & 7) == 0;
        BlockState block = post ? OAK_LOG : OAK_FENCE;
        placeIfSoft(level, new BlockPos(x, groundY + 1, z), block);
    }
}
