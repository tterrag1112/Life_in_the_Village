package tterrag1112.life_in_the_village.Village.Decoration.Adjunct;

/**
 * Phase 6.3.3.d — semantic tag for what activity an adjunct plot
 * makes available to its parent building's residents.
 *
 * <p>The {@link AdjunctPlotType} enum already carries dimensional
 * metadata (footprint half-extents, slope tolerance); this layer adds
 * a single activity tag per plot type. Tag is the recommended query
 * dimension for "can this household tend chickens / grow vegetables /
 * keep livestock?"-shape questions; raw type filtering remains for
 * cases that need the specific plot variant.
 *
 * <h3>Single-tag-per-type starting choice</h3>
 * <p>Each {@code AdjunctPlotType} carries exactly one
 * {@code ActivityTag} in 6.3.3.d. If multi-tag emerges as needed (e.g.
 * HERB_GARDEN being both CROP and a future HERBALISM tag), the
 * accessor on AdjunctPlotType is the integration point —
 * {@code activityTag()} can be promoted to {@code Set<ActivityTag>
 * activityTags()} without breaking the API.
 */
public enum ActivityTag {
    /** Chicken / fowl tending. {@link AdjunctPlotType#HOMESTEAD_COOP}. */
    POULTRY,
    /** Pigs / cows / sheep tending. {@link AdjunctPlotType#HOMESTEAD_PEN},
     *  {@link AdjunctPlotType#PADDOCK}. */
    LIVESTOCK,
    /** Vegetable / herb cultivation. {@link AdjunctPlotType#HOMESTEAD_GARDEN},
     *  {@link AdjunctPlotType#KITCHEN_GARDEN}, {@link AdjunctPlotType#HERB_GARDEN}. */
    CROP,
    /** Beekeeping. {@link AdjunctPlotType#HOMESTEAD_BEES}. */
    BEES,
    /** Fruit orchards. {@link AdjunctPlotType#HOMESTEAD_ORCHARD}. */
    ORCHARD,
    /** Blacksmith / metalwork. {@link AdjunctPlotType#FORGE_YARD}. */
    METALWORK,
    /** Hide tanning / drying. {@link AdjunctPlotType#DRYING_RACK_YARD}. */
    TANNING,
    /** Kiln / firing. {@link AdjunctPlotType#KILN_YARD}. */
    POTTERY,
    /** Log stacking / sawing. {@link AdjunctPlotType#LOG_YARD},
     *  {@link AdjunctPlotType#HOMESTEAD_WOODSHED}. */
    LUMBER,
    /** Baking / cooking shed. {@link AdjunctPlotType#OVEN_SHED}. */
    COOKING,
    /** Contemplative / ornamental gardens. {@link AdjunctPlotType#MEDITATION_GARDEN},
     *  {@link AdjunctPlotType#FORMAL_GARDEN}. */
    LEISURE,
    /** Tinkerer's workshop / craft annex. {@link AdjunctPlotType#HOMESTEAD_WORKSHOP}. */
    WORKSHOP;
}
