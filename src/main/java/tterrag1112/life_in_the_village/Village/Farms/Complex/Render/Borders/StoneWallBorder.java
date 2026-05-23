package tterrag1112.life_in_the_village.Village.Farms.Complex.Render.Borders;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Random;

/**
 * Mortared stone wall — one cobblestone block plus an optional
 * stone slab "cap" giving a 1.5-block effective height. Reads as
 * a hard, prosperous boundary (the spec's "stone wall" style).
 *
 * <p>Occasional cobblestone wall blocks (the connecting kind)
 * replace plain cobblestone for visual variety. Slab cap fires
 * on ~70% of columns so the line isn't dead-uniform.
 */
public final class StoneWallBorder extends AbstractBorderGenerator {

    private static final BlockState COBBLESTONE =
            Blocks.COBBLESTONE.defaultBlockState();
    private static final BlockState MOSSY_COBBLESTONE =
            Blocks.MOSSY_COBBLESTONE.defaultBlockState();
    private static final BlockState COBBLESTONE_WALL =
            Blocks.COBBLESTONE_WALL.defaultBlockState();
    private static final BlockState COBBLESTONE_SLAB =
            Blocks.COBBLESTONE_SLAB.defaultBlockState();

    @Override
    protected void renderColumn(ServerLevel level,
                                 int x, int z, int groundY,
                                 Direction outwardNormal,
                                 int stepIndex,
                                 Random rng) {
        // Base block — cobblestone with mossy variation, occasional wall.
        BlockState base;
        int r = rng.nextInt(100);
        if (r < 10) base = COBBLESTONE_WALL;
        else if (r < 25) base = MOSSY_COBBLESTONE;
        else base = COBBLESTONE;
        placeIfSoft(level, new BlockPos(x, groundY + 1, z), base);

        // Cap slab on most columns.
        if (rng.nextInt(10) < 7) {
            placeIfSoft(level, new BlockPos(x, groundY + 2, z), COBBLESTONE_SLAB);
        }
    }
}
