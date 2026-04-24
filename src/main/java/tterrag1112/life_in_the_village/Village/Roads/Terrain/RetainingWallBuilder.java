package tterrag1112.life_in_the_village.Village.Roads.Terrain;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import tterrag1112.life_in_the_village.Village.Roads.Terrain.GreatRoadProfile.PositionClassification;

import java.util.List;

/**
 * Places Old Realm retaining walls alongside a GREAT_ROAD wherever the terrain
 * slopes laterally across the road (SLOPED_LEFT / SLOPED_RIGHT positions).
 *
 * <h3>Wall placement logic</h3>
 * For each SLOPED position the wall is placed one block outside the road half-width
 * on the uphill side.  Height = terrainY_uphill − profileY, clamped to [2, 12].
 * A capstone block is placed at the top of every 8th wall column.
 *
 * <h3>Old Realm palette</h3>
 * <ul>
 *   <li>50 % — stone_bricks</li>
 *   <li>25 % — mossy_stone_bricks</li>
 *   <li>15 % — cobblestone</li>
 *   <li>  8 % — mossy_cobblestone</li>
 *   <li>  2 % — cracked_stone_bricks</li>
 * </ul>
 * Selection is deterministic per-block via a lightweight hash of the block
 * position so the pattern is stable across re-realizations.
 */
public final class RetainingWallBuilder {

    private RetainingWallBuilder() {}

    private static final int MIN_WALL_HEIGHT = 2;
    private static final int MAX_WALL_HEIGHT = 12;
    private static final int CAPSTONE_INTERVAL = 8;

    // =========================================================================
    // Entry point
    // =========================================================================

    /**
     * Places retaining walls for all SLOPED_LEFT / SLOPED_RIGHT positions.
     *
     * @param level        server level
     * @param dense        block-dense centerline
     * @param profileY     smoothed profile from {@link GreatRoadProfile#computeProfile}
     * @param classes      per-position classification from {@link GreatRoadProfile#classify}
     * @param roadHalfWidth half-width of the road surface (blocks from centre)
     */
    public static void build(ServerLevel level,
                              List<BlockPos> dense,
                              int[] profileY,
                              List<PositionClassification> classes,
                              int roadHalfWidth) {
        for (int i = 0; i < dense.size(); i++) {
            PositionClassification cls = classes.get(i);
            if (cls != PositionClassification.SLOPED_LEFT
                    && cls != PositionClassification.SLOPED_RIGHT) continue;

            BlockPos center = dense.get(i);
            int[] perp = GreatRoadProfile.computePerp(dense, i);

            // Uphill side: left for SLOPED_LEFT, right for SLOPED_RIGHT
            int sign = (cls == PositionClassification.SLOPED_LEFT) ? 1 : -1;

            int wallX = center.getX() + perp[0] * sign * (roadHalfWidth + 1);
            int wallZ = center.getZ() + perp[1] * sign * (roadHalfWidth + 1);

            if (!level.isLoaded(new BlockPos(wallX, center.getY(), wallZ))) continue;

            int uphillY = level.getHeight(
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, wallX, wallZ);
            int roadY   = profileY[i];
            int wallH   = uphillY - roadY;

            if (wallH < MIN_WALL_HEIGHT) continue;
            wallH = Math.min(wallH, MAX_WALL_HEIGHT);

            boolean capstone = (i % CAPSTONE_INTERVAL == 0);

            for (int h = 0; h < wallH; h++) {
                BlockPos wp = new BlockPos(wallX, roadY + h, wallZ);
                if (!level.isLoaded(wp)) break;
                boolean isTop = (h == wallH - 1);
                BlockState block = isTop && capstone
                        ? Blocks.STONE_BRICK_SLAB.defaultBlockState()
                        : oldRealmBlock(wallX, roadY + h, wallZ);
                level.setBlock(wp, block, 3);
            }
        }
    }

    // =========================================================================
    // Old Realm palette
    // =========================================================================

    static BlockState oldRealmBlock(int x, int y, int z) {
        int hash = Math.abs(posHash(x, y, z) % 100);
        if (hash < 50) return Blocks.STONE_BRICKS.defaultBlockState();
        if (hash < 75) return Blocks.MOSSY_STONE_BRICKS.defaultBlockState();
        if (hash < 90) return Blocks.COBBLESTONE.defaultBlockState();
        if (hash < 98) return Blocks.MOSSY_COBBLESTONE.defaultBlockState();
        return Blocks.CRACKED_STONE_BRICKS.defaultBlockState();
    }

    static int posHash(int x, int y, int z) {
        // Low-cost deterministic hash — not cryptographic, but stable and distribution-friendly
        int h = x * 374761393 + y * 1234567891 + z * 668265263;
        h ^= h >>> 13;
        h *= 1540483477;
        h ^= h >>> 15;
        return h;
    }
}
