package tterrag1112.life_in_the_village.Village.Planning.V2.Layer4;

import net.minecraft.core.BlockPos;

/**
 * V2 single piece of the village spine. The full spine is a list of
 * SpineSegments held by {@link Skeleton#spinePath}; on flat terrain
 * the list is typically two segments (forward + backward from the
 * anchor). On terrain that forced reactive deflection, the list
 * includes additional arc segments.
 *
 * <p>Each segment exposes {@code start}/{@code end}/{@code width}
 * for corridor-intersection checks (shared with cross streets via
 * the {@link RoadSegment} sealed interface).
 */
public record SpineSegment(BlockPos start, BlockPos end, int width)
        implements RoadSegment {
}
