package tterrag1112.life_in_the_village.Village.Decoration.Variants;

/**
 * Doc 15 — village-age category bias. Variant selection consults
 * {@code agePreference} once {@code villageAgeYears} (NPC Phase 4
 * village history) is available; until then, all newly-spawned
 * villages default to {@code FRESH}.
 */
public enum AgePreference {
    FRESH, WEATHERED, ANCIENT, ANY
}
