package tterrag1112.life_in_the_village.Village.Farms.Complex.Render.Borders;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluids;

import java.util.Random;

/**
 * Common framework for the four border styles. Subclasses only
 * implement {@link #renderColumn} — this base handles the head-
 * clearance pre-pass and the per-cell BorderGenerator entry point.
 *
 * <p>The orchestrator drives per-cell dispatch (rasterize all plot
 * edges → dedupe XZ across the complex → snapshot ground Y →
 * paint each cell once). This base's {@link #paintColumnAt} is
 * the per-cell call.
 *
 * <p>Replaceability: the column is considered paintable iff every
 * destination Y position is currently air, a plant, or otherwise
 * a "soft" block. Solid blocks (a wall of an adjacent building,
 * existing stone, etc.) are not over-written.
 */
public abstract class AbstractBorderGenerator implements BorderGenerator {

    /** Maximum world-Y at which we'll place a border block.
     *  Borders 60 blocks in the air are nonsensical; this guards
     *  against pathological terrain. Public so the orchestrator
     *  can apply the same upper-bound when filtering cells. */
    public static final int MAX_BUILD_Y = 250;

    /** Per-column hook. Called once per XZ cell the orchestrator
     *  assigns to this style. {@code groundY} is a SNAPSHOT taken
     *  before any border was placed in the complex, so subsequent
     *  borders can't see a previous border as "ground".
     *
     *  <p>{@code stepIndex} is always 0 in the new per-cell
     *  dispatch — kept in the signature for backward-compat with
     *  the previous edge-walking shape. Styles that previously
     *  varied by stride (PostAndRail's "every 6th column is a
     *  post") now use a position hash of (x, z) instead. */
    protected abstract void renderColumn(ServerLevel level,
                                         int x, int z, int groundY,
                                         Direction outwardNormal,
                                         int stepIndex,
                                         Random rng);

    @Override
    public final void paintColumnAt(ServerLevel level,
                                     int x, int z, int groundY,
                                     Direction outwardNormal,
                                     Random rng) {
        clearHeadSpace(level, x, groundY, z);
        renderColumn(level, x, z, groundY, outwardNormal, 0, rng);
    }

    /** Resolve the ground block Y at {@code (x, z)} for the
     *  orchestrator's pre-render snapshot. WORLD_SURFACE includes
     *  leaves which would float fences on tree canopies; fall back
     *  to MOTION_BLOCKING_NO_LEAVES if the WORLD_SURFACE top is a
     *  leaf. Public so the orchestrator can take the same snapshot
     *  shape per cell. */
    public static int resolveGroundY(ServerLevel level, int x, int z) {
        int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
        BlockState top = level.getBlockState(new BlockPos(x, y, z));
        if (top.is(Blocks.OAK_LEAVES)
                || top.is(Blocks.BIRCH_LEAVES)
                || top.is(Blocks.SPRUCE_LEAVES)
                || top.is(Blocks.JUNGLE_LEAVES)
                || top.is(Blocks.DARK_OAK_LEAVES)
                || top.is(Blocks.ACACIA_LEAVES)) {
            y = level.getHeight(
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        }
        return y;
    }

    /** Clear non-air blocks from {@code groundY+1} up to
     *  {@code groundY+HEAD_CLEARANCE} so the border isn't capped
     *  by overhanging tree leaves / branches / saplings. Matches
     *  RoadPainter's ROAD_HEAD_CLEARANCE pattern.
     *
     *  <p>Stops at the first solid non-replaceable block above
     *  the clearance ceiling — won't smash through stone or
     *  through an adjacent building's roof. */
    private static final int HEAD_CLEARANCE = 3;

    private static void clearHeadSpace(ServerLevel level, int x, int groundY, int z) {
        for (int dy = 1; dy <= HEAD_CLEARANCE; dy++) {
            BlockPos p = new BlockPos(x, groundY + dy, z);
            BlockState cur = level.getBlockState(p);
            if (cur.isAir()) continue;
            // Stop at first solid block we shouldn't replace; matches
            // the path renderer's selective-clear policy so we don't
            // tear up building walls.
            if (!cur.canBeReplaced() && !isVegetation(cur)) continue;
            level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
        }
    }

    private static boolean isVegetation(BlockState s) {
        return s.is(Blocks.OAK_LEAVES)
                || s.is(Blocks.BIRCH_LEAVES)
                || s.is(Blocks.SPRUCE_LEAVES)
                || s.is(Blocks.JUNGLE_LEAVES)
                || s.is(Blocks.DARK_OAK_LEAVES)
                || s.is(Blocks.ACACIA_LEAVES)
                || s.is(Blocks.OAK_LOG)
                || s.is(Blocks.BIRCH_LOG)
                || s.is(Blocks.SPRUCE_LOG)
                || s.is(Blocks.JUNGLE_LOG)
                || s.is(Blocks.DARK_OAK_LOG)
                || s.is(Blocks.ACACIA_LOG)
                || s.is(Blocks.OAK_SAPLING)
                || s.is(Blocks.SHORT_GRASS)
                || s.is(Blocks.TALL_GRASS)
                || s.is(Blocks.FERN)
                || s.is(Blocks.LARGE_FERN);
    }

    /** Safe replacement: only set the block if the current state
     *  is air or a soft / plant-like block. Returns true iff the
     *  set happened. */
    protected static boolean placeIfSoft(ServerLevel level, BlockPos pos, BlockState state) {
        BlockState cur = level.getBlockState(pos);
        if (cur.isAir()) {
            return level.setBlock(pos, state, 3);
        }
        if (cur.getFluidState().getType() != Fluids.EMPTY) return false;
        if (cur.canBeReplaced()) {
            return level.setBlock(pos, state, 3);
        }
        return false;
    }
}
