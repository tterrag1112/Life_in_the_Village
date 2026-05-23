package tterrag1112.life_in_the_village.Village.Farms.Complex.Render.Borders;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Random;

/**
 * Irregular dry-laid stone — mix of cobblestone, mossy
 * cobblestone, gravel, and stone, with column heights jittering
 * 1-2 blocks. Reads as rougher / older / less prosperous than
 * the mortared {@link StoneWallBorder}.
 */
public final class DrystoneBorder extends AbstractBorderGenerator {

    private static final BlockState COBBLESTONE =
            Blocks.COBBLESTONE.defaultBlockState();
    private static final BlockState MOSSY_COBBLESTONE =
            Blocks.MOSSY_COBBLESTONE.defaultBlockState();
    private static final BlockState GRAVEL =
            Blocks.GRAVEL.defaultBlockState();
    private static final BlockState STONE =
            Blocks.STONE.defaultBlockState();
    private static final BlockState ANDESITE =
            Blocks.ANDESITE.defaultBlockState();

    @Override
    protected void renderColumn(ServerLevel level,
                                 int x, int z, int groundY,
                                 Direction outwardNormal,
                                 int stepIndex,
                                 Random rng) {
        int height = 1 + rng.nextInt(2);   // 1 or 2 blocks
        for (int dy = 1; dy <= height; dy++) {
            BlockState s = pickStone(rng);
            placeIfSoft(level, new BlockPos(x, groundY + dy, z), s);
        }
    }

    private static BlockState pickStone(Random rng) {
        int r = rng.nextInt(100);
        if (r < 45) return COBBLESTONE;
        if (r < 65) return MOSSY_COBBLESTONE;
        if (r < 80) return STONE;
        if (r < 92) return ANDESITE;
        return GRAVEL;
    }
}
