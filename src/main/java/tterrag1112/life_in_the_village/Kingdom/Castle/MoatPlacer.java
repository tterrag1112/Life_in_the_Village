package tterrag1112.life_in_the_village.Kingdom.Castle;


import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Layer 2: Excavates the moat ring around the castle and optionally fills it.
 *
 * The moat is carved as a rectangular ring between the inner and outer radii
 * defined by MoatBounds. Terrain is excavated downward by moatDepth blocks.
 *
 * Fill modes:
 *   WATER — standard water-filled moat
 *   EMPTY — dry moat (ditch)
 *   LAVA  — lava moat for hellish/dungeon castle styles
 *
 * The moat floor is lined with gravel for a natural look.
 * A narrow stone bridge foundation is left at the gatehouse side.
 */
public class MoatPlacer {

    public enum FillMode { WATER, EMPTY, LAVA }

    private static final int BRIDGE_WIDTH = 6; // blocks — cleared as bridge approach

    private final LevelAccessor level;
    private final CastleStyle style;
    private final TerrainSampler terrain;

    public MoatPlacer(LevelAccessor level, CastleStyle style, TerrainSampler terrain) {
        this.level   = level;
        this.style   = style;
        this.terrain = terrain;
    }

    // -------------------------------------------------------------------------
    // Entry point
    // -------------------------------------------------------------------------

    /**
     * Excavate and fill the moat ring described by bounds.
     *
     * @param bounds      Describes the castle extents and moat parameters
     * @param fillMode    What to fill the moat channel with
     * @param gatehousePos The XZ center of the gatehouse — the bridge gap is preserved here
     * @param rng         Random source for floor material variation
     */
    public void place(CastleLayout.MoatBounds bounds, FillMode fillMode,
                      BlockPos gatehousePos, RandomSource rng) {

        BlockPos min = bounds.min();
        BlockPos max = bounds.max();
        int moatW    = bounds.width();
        int moatD    = bounds.depth();

        // The inner edge of the moat (castle wall outer face)
        int innerMinX = min.getX() + moatW;
        int innerMaxX = max.getX() - moatW;
        int innerMinZ = min.getZ() + moatW;
        int innerMaxZ = max.getZ() - moatW;

        BlockState fillBlock = switch (fillMode) {
            case WATER -> Blocks.WATER.defaultBlockState();
            case LAVA  -> Blocks.LAVA.defaultBlockState();
            case EMPTY -> Blocks.AIR.defaultBlockState();
        };

        for (int x = min.getX(); x <= max.getX(); x++) {
            for (int z = min.getZ(); z <= max.getZ(); z++) {
                // Only process the ring (between inner and outer edges)
                boolean inMoatRing = !(x >= innerMinX && x <= innerMaxX
                        && z >= innerMinZ && z <= innerMaxZ);
                if (!inMoatRing) continue;

                // Leave a bridge gap at the gatehouse side
                if (isBridgePosition(x, z, gatehousePos)) continue;

                int surfaceY = terrain.groundY(x, z);
                int baseY    = surfaceY - moatD;

                // Excavate from surface downward
                for (int y = baseY; y <= surfaceY; y++) {
                    BlockPos pos    = new BlockPos(x, y, z);
                    boolean isFloor = (y == baseY || y == baseY + 1);

                    if (isFloor) {
                        // Line the floor with gravel
                        placeBlock(pos, rng.nextFloat() < 0.7f
                                ? Blocks.GRAVEL.defaultBlockState()
                                : Blocks.COBBLESTONE.defaultBlockState());
                    } else {
                        // Fill the channel
                        placeBlock(pos, fillBlock);
                    }
                }

                // Clear any overhanging blocks above (trees, dirt overhangs)
                for (int y = surfaceY + 1; y <= surfaceY + 3; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!level.getBlockState(pos).isAir()) {
                        placeBlock(pos, Blocks.AIR.defaultBlockState());
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Bridge gap detection
    // -------------------------------------------------------------------------

    /**
     * Returns true if this XZ position is within the bridge approach zone
     * in front of the gatehouse. This area is not excavated so the drawbridge
     * has a surface to lower onto.
     */
    private boolean isBridgePosition(int x, int z, BlockPos gatehouse) {
        int bw = BRIDGE_WIDTH / 2;
        // Bridge extends in the +Z direction (south, conventional gatehouse facing)
        boolean inWidth = Math.abs(x - gatehouse.getX()) <= bw;
        boolean inDepth = z >= gatehouse.getZ() && z <= gatehouse.getZ() + style.features().moatWidth() + 2;
        return inWidth && inDepth;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void placeBlock(BlockPos pos, BlockState state) {
        level.setBlock(pos, state, Block.UPDATE_CLIENTS);
    }
}
