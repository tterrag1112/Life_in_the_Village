package tterrag1112.life_in_the_village.Village.Farms.Complex.Render.Borders;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Random;

/**
 * Uniform 2-block drystone: mixed-stone base + stone slab cap.
 *
 * <p>Same height profile as {@link StoneWallBorder} (1 base + 1
 * slab) but a wider material palette gives the rough, dry-laid
 * look: cobblestone (60%) + mossy cobblestone (20%) + stone
 * (10%) + andesite (10%). No height jitter — the visual
 * "irregularity" comes from material variation, not geometry.
 */
public final class DrystoneBorder extends AbstractBorderGenerator {

    private static final BlockState COBBLESTONE =
            Blocks.COBBLESTONE.defaultBlockState();
    private static final BlockState MOSSY_COBBLESTONE =
            Blocks.MOSSY_COBBLESTONE.defaultBlockState();
    private static final BlockState STONE =
            Blocks.STONE.defaultBlockState();
    private static final BlockState ANDESITE =
            Blocks.ANDESITE.defaultBlockState();
    private static final BlockState SLAB_CAP =
            Blocks.STONE_SLAB.defaultBlockState();

    @Override
    protected void renderColumn(ServerLevel level,
                                 int x, int z, int groundY,
                                 Direction outwardNormal,
                                 int stepIndex,
                                 Random rng) {
        placeIfSoft(level, new BlockPos(x, groundY + 1, z), pickStone(rng));
        placeIfSoft(level, new BlockPos(x, groundY + 2, z), SLAB_CAP);
    }

    private static BlockState pickStone(Random rng) {
        int r = rng.nextInt(100);
        if (r < 60) return COBBLESTONE;
        if (r < 80) return MOSSY_COBBLESTONE;
        if (r < 90) return STONE;
        return ANDESITE;
    }
}
