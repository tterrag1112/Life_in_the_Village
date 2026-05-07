package tterrag1112.life_in_the_village.Village.Planning.V2.Layer4;

import net.minecraft.core.BlockPos;

/**
 * V2 road segment — primary spine, perpendicular cross street.
 *
 * <p>Sealed at two implementations: {@link Spine} and
 * {@link CrossStreet}. The phased planner attaches building
 * frontage to whichever segment they face; reassessment may
 * trim or remove segments based on actual usage.
 */
public sealed interface RoadSegment permits Spine, CrossStreet {

    BlockPos start();

    BlockPos end();

    /** Logical width in blocks (3 for VILLAGE_ROAD in V1). Used
     *  for frontage-strip dimensioning and corridor checks. */
    int width();
}
