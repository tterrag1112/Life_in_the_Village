package tterrag1112.life_in_the_village.Village.Planning.V2.Layer4;

import net.minecraft.core.BlockPos;

/**
 * V2 road segment — one chord-decomposed piece of the routed road network.
 *
 * <p>Sealed at one implementation: {@link SpineSegment}. The phased planner
 * attaches building frontage to whichever segment a building faces.
 *
 * <p>A6 teardown: {@code CrossStreet} removed from the permits clause;
 * the cross-street pre-pass was deleted in Stage 3e and the type is gone.
 */
public sealed interface RoadSegment permits SpineSegment {

    BlockPos start();

    BlockPos end();

    /** Logical width in blocks (3 for VILLAGE_ROAD in V1). Used
     *  for frontage-strip dimensioning and corridor checks. */
    int width();
}
