package tterrag1112.life_in_the_village.Village.Planning.V2.Layer1;

import net.minecraft.core.BlockPos;

/**
 * A region of exposed natural stone produced by
 * {@link V2FeatureMap#stoneExposedRegions()}.
 *
 * <p>Built by dilating {@code STONE_EXPOSED} cells by 2 cells
 * (chebyshev — tighter than forest, since stone is more localised),
 * then taking 8-connected components. Components smaller than 6
 * cells are filtered out.
 *
 * @param centre     cell-centroid world position
 * @param area       dilated cell count (region's overall extent)
 * @param coreCells  count of original {@code STONE_EXPOSED} cells
 *                   inside the dilated region
 * @param bbox       inclusive XZ bounding box in world coordinates
 * @param avgSlope   mean {@code localSlope} across the core cells —
 *                   distinguishes flat exposed bedrock (low) from
 *                   cliff faces (high). Captured now so future cliff
 *                   classification doesn't have to re-scan.
 */
public record StoneRegion(BlockPos centre, int area, int coreCells,
                          BoundingBox bbox, int avgSlope) {
}
