package tterrag1112.life_in_the_village.Village.Planning.V2.Layer3;

import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;

/**
 * Records a building that was selected by the inclination but not
 * placed by Layer 3, with the {@link DropReason} and a one-line
 * detail string. Surfaced by the debug command so cascade effects
 * (e.g. BAKERY dropped because MILLER dropped) are visible.
 */
public record DroppedBuilding(BuildingType type, DropReason reason, String detail) {
}
