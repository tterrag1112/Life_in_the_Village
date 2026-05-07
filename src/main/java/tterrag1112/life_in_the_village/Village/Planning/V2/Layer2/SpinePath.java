package tterrag1112.life_in_the_village.Village.Planning.V2.Layer2;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.RoadPrimitive;

import java.util.List;

/**
 * V2 spine — an ordered list of road primitives forming the
 * village's primary road. The path always starts and ends with the
 * mean direction along {@link #primaryAxis} but may include arc
 * deflection segments in between when terrain forced the planner
 * to bend around an obstacle.
 *
 * <p>For most villages on flat terrain, {@code segments} is just
 * {@code [forward StraightRoad, backward StraightRoad]} or even
 * a single segment. Arc-deflected paths grow the list.
 *
 * @param primaryAxis  cardinal axis the spine's mean direction
 *                     follows (chosen by Layer 2)
 * @param segments     ordered list of primitives; segments[0] is
 *                     the path's starting end and the last segment
 *                     is the ending end. Junctions between
 *                     consecutive segments are implicit (segment N
 *                     ends where segment N+1 begins)
 * @param start        first primitive's start point — the path's
 *                     "start" end
 * @param end          last primitive's end point — the path's
 *                     "end" end
 * @param totalLength  sum of segment lengths in blocks
 */
public record SpinePath(CardinalAxis primaryAxis,
                        List<RoadPrimitive> segments,
                        BlockPos start,
                        BlockPos end,
                        int totalLength) {
}
