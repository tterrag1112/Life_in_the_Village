package tterrag1112.life_in_the_village.Village.Planning.V2.Layer3;

import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-{@code BuildingType} placement profile. Drives the
 * {@link PlacementSolver}'s candidate scoring.
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
 */
public record PlacementProfile(
        boolean required,
        Priority priority,
        SizeClass sizeClass,
        double centrality,
        List<BuildingType> requiresPresent,
        Map<TerrainFactor, Double> terrainWeights,
        Map<AdjacencyFactor, Double> adjacencyWeights,
        Set<TerrainAggregate> requiresAggregates) {
}
