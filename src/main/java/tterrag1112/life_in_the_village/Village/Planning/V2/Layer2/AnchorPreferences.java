package tterrag1112.life_in_the_village.Village.Planning.V2.Layer2;

import java.util.Set;

/**
 * Track E1B — anchor-type preference shape for a
 * {@link LayoutStrategy}.
 *
 * @param primaryTypes
 *      At least one anchor of one of these types must exist (and
 *      pass {@link #minPrimaryQuality}). Selection scores by the
 *      best primary's quality.
 * @param secondaryTypes
 *      Bonus when an anchor of one of these types is within the
 *      proximity radius of the primary. See
 *      {@link StrategySelector#SECONDARY_PROXIMITY_BLOCKS}.
 * @param requireLinearFeature
 *      When {@code true}, the chosen primary must be a linear
 *      anchor type ({@code RIDGE_LINE} / {@code VALLEY_FLOOR} /
 *      {@code CLIFF_FACE} / {@code WATER_EDGE} / {@code RIVER_BEND}
 *      / {@code FOREST_EDGE}) with non-null direction.
 * @param minPrimaryQuality
 *      Minimum primary-anchor quality required for the strategy
 *      to be considered. Range {@code [0.0, 1.0]}; defaults to
 *      {@link Anchor#QUALITY_THRESHOLD} (0.20). Strategies with
 *      stronger requirements (e.g. {@code agricultural_reihendorf}
 *      wants 0.5; {@code agricultural_marschhufendorf} wants 0.6)
 *      override this.
 */
public record AnchorPreferences(
        Set<AnchorType> primaryTypes,
        Set<AnchorType> secondaryTypes,
        boolean requireLinearFeature,
        double minPrimaryQuality) {

    public AnchorPreferences {
        primaryTypes   = primaryTypes   == null ? Set.of() : Set.copyOf(primaryTypes);
        secondaryTypes = secondaryTypes == null ? Set.of() : Set.copyOf(secondaryTypes);
        if (minPrimaryQuality < 0.0) minPrimaryQuality = 0.0;
        if (minPrimaryQuality > 1.0) minPrimaryQuality = 1.0;
    }

    /** Convenience: no anchor requirements at all. Used by
     *  fallback {@code *_cluster_fallback} strategies. */
    public static AnchorPreferences any() {
        return new AnchorPreferences(Set.of(), Set.of(), false,
                Anchor.QUALITY_THRESHOLD);
    }
}
