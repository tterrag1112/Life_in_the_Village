package tterrag1112.life_in_the_village.Village.Planning.V2.Harness;

import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.DropReason;

import java.util.Map;

/**
 * Track E1 — the seven metrics computed per battery run, plus the
 * minimal identifying header. All numbers come from data the planner
 * already produces; no new instrumentation inside Layers 1-5.
 *
 * <p>{@link #aborted} is set when the planner produced no viable
 * village (placement.placed empty or villageViable=false). In that
 * case the geometric metrics (compactness, frontage, connectivity,
 * clustering) are reported as {@code Double.NaN} so the diff renderer
 * can show "-" without mistaking absence for a value of zero.
 */
public record RunMetrics(
        String terrain,
        String configLabel,
        long seed,
        boolean aborted,
        int requested,
        int placed,
        int dropped,
        double placedRate,
        Map<BuildingType, Integer> requestedPerType,
        Map<BuildingType, Integer> placedPerType,
        Map<BuildingType, Double> placedRatePerType,
        double compactness,
        double fracBuildingsOnNetwork,
        double roadCoverage,
        int networkComponents,
        double fracBuildingsOnMainComponent,
        double terrainViolence,
        double vegetationPerPlaced,
        Map<BuildingType, Double> clusteringCoherence,
        Map<DropReason, Integer> dropHistogram,
        long elapsedMs
) {}
