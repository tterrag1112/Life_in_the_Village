package tterrag1112.life_in_the_village.Village.Farms.Complex.Render.Borders;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Random;

/**
 * Uniform 2-block stone wall: cobblestone base + stone slab cap.
 *
 * <p>~10% mossy-cobblestone mixed into the base for material
 * variation. No height jitter, no per-column cap probability —
 * every column is exactly 1.5 blocks tall.
 */
public final class StoneWallBorder extends AbstractBorderGenerator {

    private static final BlockState COBBLESTONE =
            Blocks.COBBLESTONE.defaultBlockState();
    private static final BlockState MOSSY_COBBLESTONE =
            Blocks.MOSSY_COBBLESTONE.defaultBlockState();
    private static final BlockState SLAB_CAP =
            Blocks.STONE_SLAB.defaultBlockState();

    @Override
    protected void renderColumn(ServerLevel level,
                                 int x, int z, int groundY,
                                 Direction outwardNormal,
                                 int stepIndex,
                                 Random rng) {
        BlockState base = rng.nextInt(10) == 0 ? MOSSY_COBBLESTONE : COBBLESTONE;
        placeIfSoft(level, new BlockPos(x, groundY + 1, z), base);
        placeIfSoft(level, new BlockPos(x, groundY + 2, z), SLAB_CAP);
    }
}
