package tterrag1112.life_in_the_village.Village.Planning.V2.Layer1;

import net.minecraft.core.BlockPos;

/**
 * A connected region of elevated terrain — a "hill" — produced by
 * {@link V2FeatureMap#highGroundRegions()}.
 *
 * <p>A region qualifies as high ground iff its cells exceed the
 * 25th-percentile elevation of the scan area by ≥8 blocks AND the
 * region covers ≥4 cells. Smaller bumps are filtered out.
 *
 * @param peak       world position of the highest cell in the region
 *                   (XZ at the cell centre, Y at the peak elevation)
 * @param centre     cell-centroid world position of the region
 * @param area       cell count
 * @param bbox       inclusive XZ bounding box in world coordinates
 * @param prominence {@code peak.getY() - baselineY}, where baselineY
 *                   is the 25th-percentile elevation of the scan
 */
public record HighGround(BlockPos peak, BlockPos centre, int area,
                         BoundingBox bbox, int prominence) {
}
