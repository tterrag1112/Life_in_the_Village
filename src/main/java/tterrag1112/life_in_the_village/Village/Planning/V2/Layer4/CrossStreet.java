package tterrag1112.life_in_the_village.Village.Planning.V2.Layer4;

import net.minecraft.core.BlockPos;

/**
 * V2 perpendicular cross street. Inserted on demand during
 * Phase 4 when a cluster of failed-for-road-reasons buildings
 * accumulates; one cross street can serve multiple buildings.
 *
 * @param start          one endpoint
 * @param end            other endpoint
 * @param width          logical width (3 in V1)
 * @param spineJunction  the point where this cross street meets
 *                       the spine — recorded as a {@link Junction}
 *                       in Phase 5
 */
public record CrossStreet(BlockPos start, BlockPos end, int width,
                          BlockPos spineJunction) implements RoadSegment {
}
