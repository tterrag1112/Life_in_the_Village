package tterrag1112.life_in_the_village.Village.Farms.Complex.Render.Borders;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Random;

/**
 * Two-block-tall hedge. Bottom layer mostly oak leaves with rare
 * jungle leaves for colour pop. Top layer oak leaves, with an
 * occasional oak log "stake" every ~6 columns. Per-column height
 * jitter: 90% two-tall, 5% one-tall (sparse), 5% three-tall (lush).
 *
 * <p>Reads as a soft, living boundary — appropriate for the
 * default-culture HEDGE style.
 */
public final class HedgeBorder extends AbstractBorderGenerator {

    private static final BlockState OAK_LEAVES =
            Blocks.OAK_LEAVES.defaultBlockState();
    private static final BlockState JUNGLE_LEAVES =
            Blocks.JUNGLE_LEAVES.defaultBlockState();
    private static final BlockState OAK_LOG =
            Blocks.OAK_LOG.defaultBlockState();

    @Override
    protected void renderColumn(ServerLevel level,
                                 int x, int z, int groundY,
                                 Direction outwardNormal,
                                 int stepIndex,
                                 Random rng) {
        int roll = rng.nextInt(100);
        int height = roll < 5 ? 1 : roll < 95 ? 2 : 3;
        boolean stake = stepIndex > 0 && stepIndex % 6 == 0;

        for (int dy = 1; dy <= height; dy++) {
            BlockPos p = new BlockPos(x, groundY + dy, z);
            BlockState s;
            if (stake && dy == 1) {
                s = OAK_LOG;
            } else if (rng.nextInt(20) == 0) {
                s = JUNGLE_LEAVES;
            } else {
                s = OAK_LEAVES;
            }
            placeIfSoft(level, p, s);
        }
    }
}
