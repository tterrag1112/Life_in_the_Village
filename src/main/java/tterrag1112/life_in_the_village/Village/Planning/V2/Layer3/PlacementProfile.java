package tterrag1112.life_in_the_village.Village.Planning.V2.Layer3;

import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-{@code BuildingType} placement profile. Drives the
 * {@code PhasedPlanner}'s candidate scoring.
 *
 * <p>{@code centrality}: 0 = wants outskirts, 1 = wants anchor.
 * Implemented as {@code 1 - |centrality - normDistFromAnchor|},
 * peaking at {@code centrality} fraction of the village radius.
 *
 * <p>{@code requiresAggregates}: hard requirements at the
 * {@link BuildingSelector} stage. Missing → count for this type
 * collapses to 0 (the building is never selected, never reaches
 * the solver).
 *
 * <p>{@code requiresPresent}: dependency check at solver time. Drops
 * the building with {@link DropReason#DEPENDENCY_MISSING} if any
 * listed type wasn't successfully placed earlier.
 *
 * <p>{@code provides} / {@code requires}: categorical supply +
 * demand consumed by {@link ReconciliationEngine} after initial
 * selection. {@code provides} entries top up a per-village pool
 * that {@code requires} entries draw from. Tradeable demands fall
 * back to trade when local supply is exhausted (gated by
 * {@link TradeAvailability}). Both default to empty list — types
 * without categorical authoring participate in selection but
 * contribute nothing to the reconciliation pool.
 */
public record PlacementProfile(
        boolean required,
        Priority priority,
        SizeClass sizeClass,
        double centrality,
        List<BuildingType> requiresPresent,
        Map<TerrainFactor, Double> terrainWeights,
        Map<AdjacencyFactor, Double> adjacencyWeights,
        Set<TerrainAggregate> requiresAggregates,
        List<Provides> provides,
        List<Requires> requires) {

    /** Backwards-compat constructor for entries that don't yet
     *  carry categorical authoring (provides/requires default to
     *  empty list). */
    public PlacementProfile(boolean required, Priority priority,
                            SizeClass sizeClass, double centrality,
                            List<BuildingType> requiresPresent,
                            Map<TerrainFactor, Double> terrainWeights,
                            Map<AdjacencyFactor, Double> adjacencyWeights,
                            Set<TerrainAggregate> requiresAggregates) {
        this(required, priority, sizeClass, centrality, requiresPresent,
                terrainWeights, adjacencyWeights, requiresAggregates,
                List.of(), List.of());
    }
}
