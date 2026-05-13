package tterrag1112.life_in_the_village.Village.Planning.V2.Layer2;

import java.util.Set;

/**
 * Track E1B — pre-scoring filter for a {@link LayoutStrategy}.
 *
 * <p>Strategies that fail conditions never reach scoring; they
 * appear in the selection log as "rejected, &lt;reason&gt;".
 *
 * @param tierMin
 *      Inclusive minimum tier. Defaults to {@code OUTPOST}.
 * @param tierMax
 *      Inclusive maximum tier. Defaults to {@code CITY}. Used by
 *      strategies like {@code defensive_einzelhof} (HAMLET-only)
 *      or {@code civic_angerdorf} (TOWN+).
 * @param incompatibleAnchors
 *      Anchor types whose presence at quality ≥ 0.5 disqualifies
 *      this strategy. Lets a strategy say "don't pick me if a
 *      stronger feature dominates the site" — e.g. an
 *      INDUSTRIAL-haufendorf fallback might list
 *      {@code CLIFF_FACE} so it never beats
 *      {@code industrial_mining} on a mining site.
 */
public record StrategyConditions(
        ViabilityTier tierMin,
        ViabilityTier tierMax,
        Set<AnchorType> incompatibleAnchors) {

    public StrategyConditions {
        if (tierMin == null) tierMin = ViabilityTier.OUTPOST;
        if (tierMax == null) tierMax = ViabilityTier.CITY;
        incompatibleAnchors = incompatibleAnchors == null
                ? Set.of()
                : Set.copyOf(incompatibleAnchors);
    }

    /** Any tier, no anchor exclusions. */
    public static StrategyConditions any() {
        return new StrategyConditions(ViabilityTier.OUTPOST,
                ViabilityTier.CITY, Set.of());
    }

    /** Tier band check: returns true if {@code tier} ∈ [tierMin, tierMax]
     *  via target-building-count comparison (CITY=50 → OUTPOST=4). */
    public boolean tierInBand(ViabilityTier tier) {
        if (tier == null || tier == ViabilityTier.UNVIABLE) return false;
        int t = tier.targetBuildingCount;
        return t >= tierMin.targetBuildingCount
                && t <= tierMax.targetBuildingCount;
    }
}
