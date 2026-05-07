package tterrag1112.life_in_the_village.Village.Planning.V2.Layer4;

import net.minecraft.core.BlockPos;

/**
 * V2 road segment — one piece of the spine path, or a perpendicular
 * cross street.
 *
 * <p>Sealed at two implementations: {@link SpineSegment} and
 * {@link CrossStreet}. The full spine is a list of {@code
 * SpineSegment}s held by {@link Skeleton#spinePath}; on flat terrain
 * that's typically just two pieces (forward + backward from the
 * anchor). The phased planner attaches building frontage to whichever
 * segment a building faces; reassessment may trim or remove segments
 * based on actual usage.
 */
public sealed interface RoadSegment permits SpineSegment, CrossStreet {

    BlockPos start();

    BlockPos end();

    /** Logical width in blocks (3 for VILLAGE_ROAD in V1). Used
     *  for frontage-strip dimensioning and corridor checks. */
    int width();
}
