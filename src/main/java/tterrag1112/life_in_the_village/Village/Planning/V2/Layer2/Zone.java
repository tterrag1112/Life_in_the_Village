package tterrag1112.life_in_the_village.Village.Planning.V2.Layer2;

import net.minecraft.core.BlockPos;

/**
 * Layout Rework Stage 3a — one land-use zone of a {@link ZonePartition}.
 *
 * <p>A zone is a contiguous center-out band of buildable cells, tagged
 * with a {@link NucleusKind} land-use role. Zones are produced by
 * slicing the terrain-warped cost-distance field (distance from the
 * village anchor) into ascending bands and assigning each band the next
 * role in the inclination's center-out role order (an inner CIVIC core,
 * then the residential/commercial middle, then the RURAL/RESOURCE
 * fringe).
 *
 * <p>This is inspectable planning data only — no placement or road code
 * reads it in Stage 3a. Stage 3c places buildings into zones by matching
 * a building's {@link NucleusAffinity} kind to a zone of that kind.
 *
 * @param id           dense zone index, {@code 0..n-1} in center-out
 *                     order (0 = innermost)
 * @param kind         land-use role for this zone
 * @param centroid     world-space centroid of the zone's cells (y is the
 *                     centroid cell's surface elevation)
 * @param cellCount    number of FeatureMap cells assigned to this zone
 * @param minBandCost  lowest cost-distance (from anchor) in the zone's
 *                     band, in cost units
 * @param maxBandCost  highest cost-distance in the zone's band, in cost
 *                     units
 */
public record Zone(
        int id,
        NucleusKind kind,
        BlockPos centroid,
        int cellCount,
        int minBandCost,
        int maxBandCost) {
}
