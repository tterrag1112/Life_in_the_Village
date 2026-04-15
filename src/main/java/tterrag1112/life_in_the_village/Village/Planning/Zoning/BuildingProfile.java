// src/main/java/tterrag1112/life_in_the_village/Village/Planning/Zoning/BuildingProfile.java
package tterrag1112.life_in_the_village.Village.Planning.Zoning;

import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;

import java.util.List;

/**
 * Placement metadata for a {@link BuildingType}. Registered in
 * {@link BuildingProfileRegistry}. Declares an ordered preference list
 * (highest first), an anchor policy, and an optional avoidance rule.
 *
 * <p>Preferences are walked top-to-bottom by the matcher; the first
 * tier with a satisfiable slot wins. A building with an unsatisfiable
 * top tier simply falls through to the next — preferences are soft.
 *
 * <p>A building whose profile has a tier with a {@code required} tag
 * that no layout emits (e.g. docks requiring {@code RIVER_BANK} in
 * HILLTOP) will cascade through tiers; if every tier fails and the
 * building is {@code CORE}, placement fails with a diagnostic log.
 */
public record BuildingProfile(BuildingType type,
                              List<SlotPreference> preferences,
                              AnchorPolicy anchor,
                              AvoidanceRule avoidance) {
    public BuildingProfile {
        preferences = List.copyOf(preferences);
    }
}