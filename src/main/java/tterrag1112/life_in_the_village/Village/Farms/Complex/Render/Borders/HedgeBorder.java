package tterrag1112.life_in_the_village.Village.Farms.Complex.Render.Borders;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Random;

/**
 * Uniform 2-block hedge column: oak-leaves base + oak-leaves top.
 *
 * <p>Per the "frame, not wall" design: 1 block wide, exactly 2
 * tall, no height jitter. Variation is material-only: ~10% of
 * columns mix in birch or jungle leaves on the base. No oak-log
 * stakes — those broke the uniform line.
 *
 * <p>All placed leaves have {@link LeavesBlock#PERSISTENT}=true.
 * Without it the hedge decays within a few minecraft days because
 * the leaves are nowhere near a log block. This is local to the
 * hedge border path; the orchard / decoration scatter etc. set
 * their own leaf state independently.
 */
public final class HedgeBorder extends AbstractBorderGenerator {

    private static final BlockState OAK_LEAVES =
            Blocks.OAK_LEAVES.defaultBlockState()
                    .setValue(LeavesBlock.PERSISTENT, true);
    private static final BlockState BIRCH_LEAVES =
            Blocks.BIRCH_LEAVES.defaultBlockState()
                    .setValue(LeavesBlock.PERSISTENT, true);
    private static final BlockState JUNGLE_LEAVES =
            Blocks.JUNGLE_LEAVES.defaultBlockState()
                    .setValue(LeavesBlock.PERSISTENT, true);

    @Override
    protected void renderColumn(ServerLevel level,
                                 int x, int z, int groundY,
                                 Direction outwardNormal,
                                 int stepIndex,
                                 Random rng) {
        BlockState base = pickLeafBlock(rng);
        placeIfSoft(level, new BlockPos(x, groundY + 1, z), base);
        placeIfSoft(level, new BlockPos(x, groundY + 2, z), OAK_LEAVES);
    }

    private static BlockState pickLeafBlock(Random rng) {
        int r = rng.nextInt(100);
        if (r < 5)  return BIRCH_LEAVES;
        if (r < 10) return JUNGLE_LEAVES;
        return OAK_LEAVES;
    }
}
