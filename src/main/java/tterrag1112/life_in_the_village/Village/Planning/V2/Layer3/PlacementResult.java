package tterrag1112.life_in_the_village.Village.Planning.V2.Layer3;

import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;

import java.util.List;
import java.util.Map;

/**
 * V2 Layer 3 result. Carries placed and dropped buildings, the
 * unavailable list (filtered out at selection because no NBT was
 * authored), per-type counts, and the village-viability flag.
 *
 * <p>{@code villageViable} is {@code false} if any
 * {@code required:true} building dropped. Layer 2 should ordinarily
 * prevent this by returning {@code UNVIABLE} for sites that can't
 * fit a TOWN_HALL; Layer 3 carries the flag as a safety belt.
 */
public record PlacementResult(
        List<PlacedBuilding> placed,
        List<DroppedBuilding> dropped,
        List<UnavailableBuilding> unavailable,
        Map<BuildingType, Integer> placedCounts,
        boolean villageViable) {
}
