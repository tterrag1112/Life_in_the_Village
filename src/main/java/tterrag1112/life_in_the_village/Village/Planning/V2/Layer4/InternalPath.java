package tterrag1112.life_in_the_village.Village.Planning.V2.Layer4;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.RoadShape;

import java.util.List;

/**
 * Layout Rework — an explicit village-internal path (e.g. a residential variant
 * lane) carried from the planner to the render pass: its centerline waypoints
 * (floor-Y, surface-snapped by the planner) + the road tier it realizes at
 * ({@link RoadShape.RoadTier#FOOTPATH} for residential lanes). Rendered through
 * the unified {@code VillageRoadRealizer.realizePaths} (the same placer the
 * streets use), not a parallel painter. Lives in Layer4 so the planner
 * {@code Result} can carry it without a Layer4→Layer5 dependency.
 */
public record InternalPath(List<BlockPos> waypoints, RoadShape.RoadTier tier) {}
