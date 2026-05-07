package tterrag1112.life_in_the_village.Village.Planning.V2.Layer3;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;

/**
 * V2 Layer 3 → Layer 4 hand-off. Records a building's chosen
 * position, the actual variant footprint after rotation, the
 * rotation, the priority, and the variant id.
 *
 * <p>Carrying the actual variant footprint by construction is the
 * structural reason V2 sidesteps V1's slot/decoration mismatch:
 * variant is selected before footprint is locked in, so reservation
 * and decoration use the same dimensions.
 */
public record PlacedBuilding(
        BuildingType type,
        BlockPos centre,
        Footprint footprint,
        Rotation rotation,
        Priority priority,
        String variantId) {
}
