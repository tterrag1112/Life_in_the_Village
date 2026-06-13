package tterrag1112.life_in_the_village.Village.Planning.V2.Layer4;

import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * V2 junction — a point where two or more {@link RoadSegment}s
 * meet. Populated during Phase 5; the anchor itself is also a junction
 * (the spine's logical centre, used by Layer 5 plaza generation).
 */
public record Junction(BlockPos pos, List<RoadSegment> segments) {
}
