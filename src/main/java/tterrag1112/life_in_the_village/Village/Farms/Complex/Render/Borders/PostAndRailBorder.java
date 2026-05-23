package tterrag1112.life_in_the_village.Village.Farms.Complex.Render.Borders;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Random;

/**
 * Oak post-and-rail fence. Default-state fence at every column;
 * a "post" (oak log instead of fence, with a second log atop)
 * every ~5 columns. Posts every ~15 columns carry a lantern.
 *
 * <p>Reads as a light agricultural / temperate boundary. Cheap
 * relative to stone but visually more articulated than a flat
 * fence line.
 */
public final class PostAndRailBorder extends AbstractBorderGenerator {

    private static final BlockState OAK_FENCE =
            Blocks.OAK_FENCE.defaultBlockState();
    private static final BlockState OAK_LOG =
            Blocks.OAK_LOG.defaultBlockState();
    private static final BlockState LANTERN =
            Blocks.LANTERN.defaultBlockState();
    private static final BlockState HAY_BALE =
            Blocks.HAY_BLOCK.defaultBlockState();

    @Override
    protected void renderColumn(ServerLevel level,
                                 int x, int z, int groundY,
                                 Direction outwardNormal,
                                 int stepIndex,
                                 Random rng) {
        boolean post = stepIndex > 0 && stepIndex % 5 == 0;
        boolean lit  = stepIndex > 0 && stepIndex % 15 == 0;

        BlockPos foot = new BlockPos(x, groundY + 1, z);
        BlockPos head = new BlockPos(x, groundY + 2, z);

        if (post) {
            placeIfSoft(level, foot, OAK_LOG);
            placeIfSoft(level, head, OAK_LOG);
            if (lit) {
                placeIfSoft(level, new BlockPos(x, groundY + 3, z), LANTERN);
            } else if (rng.nextInt(8) == 0) {
                placeIfSoft(level, new BlockPos(x, groundY + 3, z), HAY_BALE);
            }
        } else {
            placeIfSoft(level, foot, OAK_FENCE);
        }
    }
}
