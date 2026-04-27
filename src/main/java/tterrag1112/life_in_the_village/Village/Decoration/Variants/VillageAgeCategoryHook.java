package tterrag1112.life_in_the_village.Village.Decoration.Variants;

/**
 * Doc 15 — single point of entry for "what age bucket is this
 * village in?". NPC Phase 4 doc 30 introduces village history; until
 * that lands, every newly-spawned village is treated as
 * {@link AgeCategory#FRESH}. Swapping this implementation is the
 * one-line change once {@code villageAgeYears} is available.
 */
public final class VillageAgeCategoryHook {

    private VillageAgeCategoryHook() {}

    /**
     * Resolves the age bucket for a village in the middle of being
     * planned. Returns {@link AgeCategory#FRESH} unconditionally for
     * P0a-06 (the matcher needs an answer before NPC Phase 4 ships);
     * the eventual implementation will look up
     * {@code villageAgeYears} via the village history log.
     */
    public static AgeCategory forNewVillage() {
        return AgeCategory.FRESH;
    }
}
