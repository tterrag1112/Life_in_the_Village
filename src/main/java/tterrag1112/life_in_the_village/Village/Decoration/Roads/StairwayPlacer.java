package tterrag1112.life_in_the_village.Village.Decoration.Roads;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.StairsShape;

import java.util.ArrayList;
import java.util.List;

/**
 * Places stair blocks along a {@code RoadPrimitive.Stairway}'s centerline.
 *
 * <p>The centerline carries authoritative Y values (linearly interpolated
 * between the stairway's endpoints, not surface-snapped). For each
 * centerline point, this placer:
 * <ol>
 *   <li>Places a stair block at {@code (x, y, z)} oriented to match the
 *       climb direction — facing TOWARD the lower end of the local segment
 *       so a player can walk up the stair.</li>
 *   <li>Fills 1-3 blocks of solid support underneath when natural terrain
 *       is lower than the stair Y, so stairs don't float.</li>
 *   <li>Clears 2 blocks of air above the stair for headroom when natural
 *       terrain is higher than the stair Y, so the path isn't enclosed.</li>
 * </ol>
 *
 * <p>Tier governs path width: {@code tier.placedHalfWidth() - 1} stairs
 * are placed perpendicular to the climb direction.
 *
 * <p>Phase 13.1 uses {@code Blocks.STONE_STAIRS} unconditionally; tier-
 * or culture-specific stair materials are a polish pass.
 */
public final class StairwayPlacer {

    private StairwayPlacer() {}

    /**
     * Places stair blocks along the centerline.
     *
     * @return all positions where blocks were set (stairs + support fill).
     */
    public static List<BlockPos> place(ServerLevel level,
                                       List<BlockPos> centerline,
                                       PathMaterial material,
                                       RoadShape.RoadTier tier,
                                       RandomSource random) {
        List<BlockPos> placed = new ArrayList<>();
        if (centerline.size() < 2) return placed;

        int halfWidth = Math.max(1, tier.placedHalfWidth() - 1);

        for (int i = 0; i < centerline.size(); i++) {
            BlockPos centre = centerline.get(i);
            BlockPos prev = centerline.get(Math.max(0, i - 1));
            BlockPos next = centerline.get(Math.min(centerline.size() - 1, i + 1));

            Direction facing = stairFacing(prev, next);
            int[] perp = computePerpXZ(prev, next);
            BlockState stairState = pickStairState(material, facing, random);

            for (int d = -halfWidth; d <= halfWidth; d++) {
                int x = centre.getX() + perp[0] * d;
                int z = centre.getZ() + perp[1] * d;
                BlockPos placePos = new BlockPos(x, centre.getY(), z);

                fillSupportBelow(level, placePos);

                level.setBlock(placePos, stairState, 3);
                placed.add(placePos);

                // Clear headroom for the next 2 blocks above so a tall
                // natural surface doesn't enclose the stairway.
                level.setBlock(placePos.above(1),  Blocks.AIR.defaultBlockState(), 3);
                level.setBlock(placePos.above(2),  Blocks.AIR.defaultBlockState(), 3);
            }
        }
        return placed;
    }

    /**
     * Stairs face TOWARD the lower end of the segment so a player walks
     * UP from the stair's facing-direction onto the higher tread.
     *
     * <p>Uses cardinal (not diagonal) facing — Minecraft stairs only
     * support cardinal facings, and dominant-axis selection produces
     * the closest cardinal match for a near-axial chord.
     */
    private static Direction stairFacing(BlockPos prev, BlockPos next) {
        BlockPos lower  = next.getY() >= prev.getY() ? prev : next;
        BlockPos higher = lower == prev ? next : prev;
        int dx = lower.getX() - higher.getX();
        int dz = lower.getZ() - higher.getZ();
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? Direction.EAST : Direction.WEST;
        }
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    /**
     * Perpendicular cardinal axis (XZ only) for placing the stairway's
     * width. The result is one of {(1,0), (-1,0), (0,1), (0,-1)}.
     */
    private static int[] computePerpXZ(BlockPos prev, BlockPos next) {
        int dx = next.getX() - prev.getX();
        int dz = next.getZ() - prev.getZ();
        if (Math.abs(dx) >= Math.abs(dz)) {
            // Heading is X-dominant → perp is along Z
            return new int[] { 0, 1 };
        }
        // Heading is Z-dominant → perp is along X
        return new int[] { 1, 0 };
    }

    /**
     * Phase 13.1: always {@code Blocks.STONE_STAIRS}. Tier-specific stair
     * materials would query {@link PathMaterial} via a future
     * {@code stairBlock()} accessor; that polish lands when stair
     * material variation is needed.
     */
    private static BlockState pickStairState(PathMaterial material,
                                             Direction facing,
                                             RandomSource random) {
        return Blocks.STONE_STAIRS.defaultBlockState()
                .setValue(StairBlock.FACING, facing)
                .setValue(StairBlock.HALF, Half.BOTTOM)
                .setValue(StairBlock.SHAPE, StairsShape.STRAIGHT);
    }

    /**
     * Fills 1-3 blocks of stone underneath the stair when natural terrain
     * is lower (air or liquid). Stops at the first solid block to avoid
     * over-filling into deep terrain.
     */
    private static void fillSupportBelow(ServerLevel level, BlockPos stairPos) {
        for (int dy = 1; dy <= 3; dy++) {
            BlockPos check = stairPos.below(dy);
            BlockState existing = level.getBlockState(check);
            if (existing.isAir() || existing.liquid()) {
                level.setBlock(check, Blocks.STONE.defaultBlockState(), 3);
            } else {
                break;
            }
        }
    }
}
