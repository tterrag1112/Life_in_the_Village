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
 * fence collides at 1.5 blocks — unjumpable). Every 5-7 columns
 * gets an oak-log post (1 block, solid) instead of a fence.
 * Posts give the line its visual rhythm; no slab cap needed.
 */
public final class PostAndRailBorder extends AbstractBorderGenerator {

    private static final BlockState OAK_FENCE =
            Blocks.OAK_FENCE.defaultBlockState();
    private static final BlockState OAK_LOG =
            Blocks.OAK_LOG.defaultBlockState();

    private static final int POST_INTERVAL = 6;

    @Override
    protected void renderColumn(ServerLevel level,
                                 int x, int z, int groundY,
                                 Direction outwardNormal,
                                 int stepIndex,
                                 Random rng) {
        boolean post = stepIndex > 0 && stepIndex % POST_INTERVAL == 0;
        BlockState block = post ? OAK_LOG : OAK_FENCE;
        placeIfSoft(level, new BlockPos(x, groundY + 1, z), block);
    }
}
