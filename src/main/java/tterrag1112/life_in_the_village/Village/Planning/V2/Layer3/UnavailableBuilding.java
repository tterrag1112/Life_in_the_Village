package tterrag1112.life_in_the_village.Village.Planning.V2.Layer3;

import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;

/**
 * A building type that the {@link BuildingSelector} skipped before
 * the solver because no NBT was authored for the village's
 * {@code (culture, style, type, level)}.
 *
 * <p>Distinct from {@link DroppedBuilding}: unavailable means
 * "couldn't even try"; dropped means "tried but failed".
 */
public record UnavailableBuilding(BuildingType type, String reason) {
}
