package tterrag1112.life_in_the_village.Village.Planning.V2.Layer3;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer4.RoadSegment;

/**
 * V2 Layer 3 → Layer 4 hand-off. Records a building's chosen
 * position, the actual variant footprint, the rotation, the
 * priority, the variant id, the frontage strip on the facing
 * road, and the road segment the building faces.
 *
 * <p>Both {@code frontage} and {@code facingRoad} are populated by
 * the phased planner at placement time. Layer 5 uses
 * {@code frontage} for facade decoration and {@code facingRoad}
 * for road-network adjacency queries.
 */
public record PlacedBuilding(
        BuildingType type,
        BlockPos centre,
        Footprint footprint,
        Rotation rotation,
        Priority priority,
        String variantId,
        FrontageStrip frontage,
        RoadSegment facingRoad) {
}
