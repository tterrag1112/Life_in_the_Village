package tterrag1112.life_in_the_village.Kingdom.Castle;


import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Utility for resolving solid ground Y at arbitrary XZ positions.
 *
 * Used by all Layer 2 placers so walls, towers, and other structures
 * anchor to terrain rather than floating above or burying into it.
 *
 * Cached per-column to avoid redundant world scans.
 */
public class TerrainSampler {

    private final LevelAccessor level;

    public TerrainSampler(LevelAccessor level) {
        this.level = level;
    }

    // -------------------------------------------------------------------------
    // Ground resolution
    // -------------------------------------------------------------------------

    /**
     * Find the highest solid, non-liquid block Y at (x, z).
     * Scans downward from the world build height.
     * Returns the Y of the topmost solid surface (block top face = Y+1).
     */
    public int groundY(int x, int z) {
        int maxY = level.getMaxY() - 1;
        int minY = level.getMinY();

        for (int y = maxY; y >= minY; y--) {
            BlockState state = level.getBlockState(new BlockPos(x, y, z));
            if (isSolidGround(state)) {
                return y;
            }
        }
        return minY;
    }

    /**
     * Convenience overload accepting a BlockPos (uses only X and Z).
     */
    public int groundY(BlockPos pos) {
        return groundY(pos.getX(), pos.getZ());
    }

    /**
     * Find the lowest ground Y within a rectangular XZ area.
     * Used to find the base depth for moat excavation or foundation digging.
     */
    public int minGroundY(int x1, int z1, int x2, int z2) {
        int min = Integer.MAX_VALUE;
        for (int x = x1; x <= x2; x++) {
            for (int z = z1; z <= z2; z++) {
                min = Math.min(min, groundY(x, z));
            }
        }
        return min == Integer.MAX_VALUE ? 64 : min;
    }

    /**
     * Find the average ground Y along a line from (x1,z1) to (x2,z2).
     * Useful for deciding the base elevation of a wall segment.
     */
    public int averageGroundY(BlockPos from, BlockPos to) {
        int steps = Math.max(1, (int) Math.sqrt(
                (double)(to.getX() - from.getX()) * (to.getX() - from.getX()) +
                        (double)(to.getZ() - from.getZ()) * (to.getZ() - from.getZ())
        ));

        long sum = 0;
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            int x = (int) Math.round(from.getX() + t * (to.getX() - from.getX()));
            int z = (int) Math.round(from.getZ() + t * (to.getZ() - from.getZ()));
            sum += groundY(x, z);
        }
        return (int) (sum / (steps + 1));
    }

    /**
     * Find the highest ground Y under a tower footprint (circle of given radius).
     * Used to determine foundation depth for towers.
     */
    public int maxGroundYInRadius(BlockPos center, int radius) {
        int max = Integer.MIN_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz <= radius * radius) {
                    max = Math.max(max, groundY(center.getX() + dx, center.getZ() + dz));
                }
            }
        }
        return max == Integer.MIN_VALUE ? 64 : max;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Determines whether a block counts as solid ground.
     * Excludes water, lava, plants, and air.
     */
    private boolean isSolidGround(BlockState state) {
        if (state.isAir()) return false;
        if (state.is(Blocks.WATER)) return false;
        if (state.is(Blocks.LAVA)) return false;
        // Exclude common non-solid/decorative blocks
        if (!state.blocksMotion()) return false;
        return true;
    }

    /**
     * Place solid fill from minY (inclusive) up to groundY at each column,
     * ensuring the structure has a foundation rather than floating.
     *
     * This is called by each placer before placing its blocks.
     */
    public void fillFoundation(int x, int z, int fromY, int toY, MaterialPalette palette,
                               net.minecraft.util.RandomSource rng) {
        for (int y = fromY; y <= toY; y++) {
            BlockPos pos = new BlockPos(x, y, z);
            BlockState current = level.getBlockState(pos);
            if (current.isAir() || current.is(Blocks.WATER)) {
                level.setBlock(pos, palette.pickMain(rng),
                        net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
            }
        }
    }
}
