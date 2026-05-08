package tterrag1112.life_in_the_village.Village.Planning.V2.Layer3;

import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Decoration.Variants.Style;

import java.util.Set;

/**
 * V2 service: answers "which authored variants exist for this
 * (culture, style, type, level) combination on disk?"
 *
 * <p>{@link BuildingSelector} consults this at selection time so
 * unauthored {@code BuildingType}s never reach the solver.
 * {@code VariantResolver#pickVariantIdForV2} consults it so V2 only
 * ever names a variant id whose NBT actually exists — partial authoring degrades
 * gracefully (a culture with only {@code cottage} authored for HOUSE
 * gets every HOUSE as a cottage).
 *
 * <p>The interface is what matters; the canonical implementation is
 * {@link StructureAvailabilityRegistry}, an eager-scan reload
 * listener registered on {@code AddServerReloadListenersEvent}.
 */
public interface BuildingAvailability {

    /**
     * @return the set of variantIds with an authored
     *         {@code level_<level>.nbt} for the given combination.
     *         Empty set means the type is unavailable.
     */
    Set<String> availableVariants(String culture, Style style,
                                  BuildingType type, int level);

    /** Convenience: true iff at least one variant is authored. */
    default boolean isAvailable(String culture, Style style,
                                BuildingType type, int level) {
        return !availableVariants(culture, style, type, level).isEmpty();
    }
}
