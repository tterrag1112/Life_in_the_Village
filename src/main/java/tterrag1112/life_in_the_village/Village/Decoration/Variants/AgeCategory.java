package tterrag1112.life_in_the_village.Village.Decoration.Variants;

/**
 * Concrete village age bucket derived from {@code villageAgeYears}
 * (NPC Phase 4 doc 30).
 *
 * <p>Distinct from {@link AgePreference} which is the variant-side
 * soft bias and may be {@code ANY}. Doc 15 defaults all newly-spawned
 * villages to {@link #FRESH} until village history is wired up.</p>
 */
public enum AgeCategory {
    FRESH, WEATHERED, ANCIENT
}
