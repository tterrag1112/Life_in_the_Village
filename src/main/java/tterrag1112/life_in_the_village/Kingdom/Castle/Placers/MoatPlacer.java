package tterrag1112.life_in_the_village.Kingdom.Castle.Placers;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import tterrag1112.life_in_the_village.Kingdom.Castle.CastleLayout;
import tterrag1112.life_in_the_village.Kingdom.Castle.CastleStyle;
import tterrag1112.life_in_the_village.Kingdom.Castle.TerrainSampler;

/**
 * Layer 2: Excavates the moat ring and fills it according to FeatureConfig.moatFill().
 *
 * The moat is carved as a rectangular ring between the inner and outer extents
 * of MoatBounds. Fill type is driven entirely by the CastleStyle — no parameter needed.
 *
 * A bridge gap is preserved at the gatehouse side for the drawbridge to land on.
 * The moat floor is lined with gravel for a natural look.
 */
public class MoatPlacer {

    private static final int BRIDGE_WIDTH = 6;

    private final LevelAccessor level;
    private final CastleStyle style;
    private final TerrainSampler terrain;

    public MoatPlacer(LevelAccessor level, CastleStyle style, TerrainSampler terrain) {
        this.level   = level;
        this.style   = style;
        this.terrain = terrain;
    }

    // =========================================================================
    // Entry point — fill type now comes from style, not a parameter
    // =========================================================================

    public void place(CastleLayout.MoatBounds bounds,
                      BlockPos gatehousePos,
                      RandomSource rng) {
        BlockPos min  = bounds.min();
        BlockPos max  = bounds.max();
        int moatW     = bounds.width();
        int moatD     = bounds.depth();

        int innerMinX = min.getX() + moatW;
        int innerMaxX = max.getX() - moatW;
        int innerMinZ = min.getZ() + moatW;
        int innerMaxZ = max.getZ() - moatW;

        BlockState fillBlock = style.features().moatFill().getBlock();
        boolean isLava       = style.features().moatFill() == MoatFillType.LAVA;

        for (int x = min.getX(); x <= max.getX(); x++) {
            for (int z = min.getZ(); z <= max.getZ(); z++) {
                boolean inRing = !(x >= innerMinX && x <= innerMaxX
                        && z >= innerMinZ && z <= innerMaxZ);
                if (!inRing) continue;
                if (isBridgePosition(x, z, gatehousePos)) continue;

                int surfaceY = terrain.groundY(x, z);
                int baseY    = surfaceY - moatD;

                for (int y = baseY; y <= surfaceY; y++) {
                    BlockPos pos    = new BlockPos(x, y, z);
                    boolean isFloor = (y == baseY || y == baseY + 1);

                    if (isFloor) {
                        // Lava moat gets netherrack floor for thematic consistency
                        placeBlock(pos, isLava
                                ? Blocks.NETHERRACK.defaultBlockState()
                                : (rng.nextFloat() < 0.7f
                                ? Blocks.GRAVEL.defaultBlockState()
                                : Blocks.COBBLESTONE.defaultBlockState()));
                    } else {
                        placeBlock(pos, fillBlock);
                    }
                }

                // Clear overhanging blocks above moat surface
                for (int y = surfaceY + 1; y <= surfaceY + 3; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!level.getBlockState(pos).isAir()) {
                        placeBlock(pos, Blocks.AIR.defaultBlockState());
                    }
                }
            }
        }
    }

    // =========================================================================
    // Bridge gap
    // =========================================================================

    private boolean isBridgePosition(int x, int z, BlockPos gatehouse) {
        int bw = BRIDGE_WIDTH / 2;
        boolean inWidth = Math.abs(x - gatehouse.getX()) <= bw;
        boolean inDepth = z >= gatehouse.getZ()
                && z <= gatehouse.getZ() + style.features().moatWidth() + 2;
        return inWidth && inDepth;
    }

    private void placeBlock(BlockPos pos, BlockState state) {
        level.setBlock(pos, state, Block.UPDATE_CLIENTS);
    }
}