package tterrag1112.life_in_the_village.Village.Roads.Terrain;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import tterrag1112.life_in_the_village.Village.Roads.Terrain.GreatRoadProfile.PositionClassification;

import java.util.List;

/**
 * Conservative terrain smoother for a GREAT_ROAD dense centerline.
 *
 * <h3>What it does</h3>
 * <ul>
 *   <li><b>NORMAL</b> (|delta| ≤ 1) — nudges the surface ±1 block so the road
 *       placer gets an exactly flat foundation:
 *       <ul>
 *         <li>terrain 1 above profile → clear that one block so the heightmap
 *             returns profileY</li>
 *         <li>terrain 1 below profile → fill that gap with an Old Realm block</li>
 *       </ul>
 *   </li>
 *   <li><b>LOWERED</b> (terrain significantly above profile) — excavates blocks
 *       from profileY up to terrainY − 1, capped at
 *       {@value #MAX_EXCAVATION_DEPTH} blocks, so OrganicRoadPlacer places the
 *       road in the cut rather than on top of the hill.  Only solid, non-player-
 *       placed blocks are removed.</li>
 * </ul>
 * RAISED and SLOPED positions are left to {@link RoadSupportBuilder} and
 * {@link RetainingWallBuilder} respectively and are skipped here.
 *
 * <h3>Build order</h3>
 * Must be called <em>before</em>
 * {@link tterrag1112.life_in_the_village.Village.Decoration.Roads.OrganicRoadPlacer}
 * so the heightmap already returns the correct surface when road blocks are painted.
 */
public final class RoadSmoother {

    private RoadSmoother() {}

    /** Maximum number of blocks excavated above profile Y for a LOWERED position. */
    static final int MAX_EXCAVATION_DEPTH = 8;

    // =========================================================================
    // Entry point
    // =========================================================================

    /**
     * Applies conservative smoothing along the centerline.
     *
     * @param level    server level
     * @param dense    block-dense centerline
     * @param profileY smoothed profile from {@link GreatRoadProfile#computeProfile}
     * @param classes  per-position classification
     */
    public static void smooth(ServerLevel level,
                               List<BlockPos> dense,
                               int[] profileY,
                               List<PositionClassification> classes) {
        for (int i = 0; i < dense.size(); i++) {
            PositionClassification cls = classes.get(i);
            if (cls == PositionClassification.RAISED
                    || cls == PositionClassification.SLOPED_LEFT
                    || cls == PositionClassification.SLOPED_RIGHT) continue;

            BlockPos pos = dense.get(i);
            int terrainY = pos.getY();
            int profile  = profileY[i];

            if (cls == PositionClassification.NORMAL) {
                smoothNormal(level, pos, terrainY, profile);
            } else if (cls == PositionClassification.LOWERED) {
                excavateCut(level, pos, terrainY, profile);
            }
        }
    }

    // =========================================================================
    // Smooth operations
    // =========================================================================

    private static void smoothNormal(ServerLevel level, BlockPos center,
                                      int terrainY, int profileY) {
        if (terrainY == profileY) return; // already correct

        if (terrainY == profileY + 1) {
            // Terrain 1 too high — clear single block at terrainY - 1 (the surface block)
            BlockPos surfaceBlock = new BlockPos(center.getX(), terrainY - 1, center.getZ());
            if (!level.isLoaded(surfaceBlock)) return;
            BlockState bs = level.getBlockState(surfaceBlock);
            if (bs.isSolidRender() && !isProtectedBlock(bs)) {
                level.setBlock(surfaceBlock, Blocks.AIR.defaultBlockState(), 3);
            }
        } else if (terrainY == profileY - 1) {
            // Terrain 1 too low — fill single block at terrainY
            BlockPos fillPos = new BlockPos(center.getX(), terrainY, center.getZ());
            if (!level.isLoaded(fillPos)) return;
            BlockState existing = level.getBlockState(fillPos);
            if (existing.isAir() || existing.canBeReplaced()) {
                level.setBlock(fillPos,
                        RetainingWallBuilder.oldRealmBlock(
                                center.getX(), terrainY, center.getZ()), 3);
            }
        }
    }

    private static void excavateCut(ServerLevel level, BlockPos center,
                                     int terrainY, int profileY) {
        // Excavate blocks from profileY up to terrainY - 1 so the surface sits at profileY
        int excavateDepth = Math.min(terrainY - profileY, MAX_EXCAVATION_DEPTH);
        if (excavateDepth <= 0) return;

        for (int dy = 0; dy < excavateDepth; dy++) {
            int y = profileY + dy;
            BlockPos target = new BlockPos(center.getX(), y, center.getZ());
            if (!level.isLoaded(target)) return;
            BlockState bs = level.getBlockState(target);
            if (bs.isSolidRender() && !isProtectedBlock(bs)) {
                level.setBlock(target, Blocks.AIR.defaultBlockState(), 3);
            }
        }
    }

    // =========================================================================
    // Block guards
    // =========================================================================

    /** Never excavate blocks that might be player-built or architecturally significant. */
    private static boolean isProtectedBlock(BlockState state) {
        // Stripped logs = building material
        if (state.is(Blocks.STRIPPED_OAK_LOG)
                || state.is(Blocks.STRIPPED_SPRUCE_LOG)
                || state.is(Blocks.STRIPPED_DARK_OAK_LOG)
                || state.is(Blocks.STRIPPED_BIRCH_LOG)
                || state.is(Blocks.STRIPPED_JUNGLE_LOG)
                || state.is(Blocks.STRIPPED_ACACIA_LOG)
                || state.is(Blocks.STRIPPED_MANGROVE_LOG)
                || state.is(Blocks.STRIPPED_CHERRY_LOG)) return true;
        // Crafted stone that would not appear naturally
        if (state.is(Blocks.STONE_BRICKS)
                || state.is(Blocks.MOSSY_STONE_BRICKS)
                || state.is(Blocks.CRACKED_STONE_BRICKS)
                || state.is(Blocks.CHISELED_STONE_BRICKS)
                || state.is(Blocks.POLISHED_ANDESITE)
                || state.is(Blocks.POLISHED_DIORITE)
                || state.is(Blocks.POLISHED_GRANITE)) return true;
        return false;
    }
}
