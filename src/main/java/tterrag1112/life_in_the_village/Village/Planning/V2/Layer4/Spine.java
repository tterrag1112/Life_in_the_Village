package tterrag1112.life_in_the_village.Village.Planning.V2.Layer4;

import net.minecraft.core.BlockPos;

/**
 * V2 primary village road through (offset from) the anchor along
 * {@code SiteContext.primarySpineDirection}. One per village.
 *
 * <p>The phased planner produces this in Phase 2 from the total
 * selected building frontage; Phase 5 may trim it.
 */
public record Spine(BlockPos start, BlockPos end, int width) implements RoadSegment {
}
