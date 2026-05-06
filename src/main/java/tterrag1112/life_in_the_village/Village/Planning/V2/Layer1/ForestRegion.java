package tterrag1112.life_in_the_village.Village.Planning.V2.Layer1;

import net.minecraft.core.BlockPos;

/**
 * A coherent forest patch produced by {@link V2FeatureMap#forestRegions()}.
 *
 * <p>Built by dilating FOREST cells by 3 cells (chebyshev) so nearby
 * tree clusters merge, then taking 8-connected components. Components
 * smaller than 8 cells are filtered out.
 *
 * @param centre     cell-centroid world position of the dilated region
 * @param area       dilated cell count (the region's overall extent)
 * @param coreCells  count of original {@code FOREST} cells inside the
 *                   dilated region — the actual tree-bearing cell
 *                   count, before dilation
 * @param bbox       inclusive XZ bounding box in world coordinates
 */
public record ForestRegion(BlockPos centre, int area, int coreCells,
                           BoundingBox bbox) {
}
