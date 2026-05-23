package tterrag1112.life_in_the_village.Village.Buildings.Complex;

import tterrag1112.life_in_the_village.Village.Buildings.FarmPlot.CropType;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Detour A — declarative spec for a building's complex envelope.
 *
 * <p>Looked up at planning time via
 * {@link BuildingComplexRegistry#get(String, tterrag1112.life_in_the_village.Village.Buildings.BuildingType)}.
 * All scaling is relative to the parent building's NBT footprint:
 * the planner reads the farmhouse dimensions via
 * {@code StructureSizeCache} and multiplies by
 * {@link #radiusMultiplier} to get the flood-fill claim radius. No
 * absolute "farmhouse is 15×10" constant lives anywhere — change
 * the NBT, the complex scales with it.
 *
 * <p>{@link #blockBudget} is the cell count available to plots +
 * paths + tool shed; the farmhouse's own footprint is reserved
 * separately and doesn't count against this budget.
 *
 * <p>The spec is intentionally generic across complex domains —
 * BLACKSMITH and TEMPLE registrations will reuse this record with
 * domain-appropriate values. Domain-specific fields
 * ({@link #plotTypeMix}) stay non-null but may be empty for
 * domains that don't subdivide into plots.
 *
 * @param radiusMultiplier   flood-fill radius = farmhouse longest
 *                           dim × this. ~3.0 for a farm.
 * @param blockBudget        max cells the flood-fill may claim
 *                           (excludes farmhouse footprint).
 * @param minPlotSize        smallest viable plot, in cells. Plots
 *                           below this after BSP+clip are dropped
 *                           or merged.
 * @param targetPlotCount    BSP target. May produce ±1; the
 *                           "tight complex" reduction may halve.
 * @param plotTypeMix        weighted-random distribution over
 *                           {@link CropType}. Weights must sum
 *                           >0 (validated). Empty for non-farm
 *                           complexes.
 * @param borderStylePool    palette the per-plot border assigner
 *                           draws from. Non-empty.
 * @param pathStyle          topology strategy for the inter-plot
 *                           path graph.
 * @param floodFillSlopeLimit  cell admissibility cap in degrees
 *                             (~6 for plains-friendly farms).
 * @param blockedBiomes      veto set; flood-fill seed must NOT be
 *                           in any tag.
 */
public record BuildingComplexSpec(
        float radiusMultiplier,
        int blockBudget,
        int minPlotSize,
        int targetPlotCount,
        Map<CropType, Float> plotTypeMix,
        List<BorderStyleId> borderStylePool,
        PathTopology pathStyle,
        int floodFillSlopeLimit,
        Set<BiomeTag> blockedBiomes) {

    public BuildingComplexSpec {
        if (radiusMultiplier <= 0) {
            throw new IllegalArgumentException(
                    "radiusMultiplier must be positive: " + radiusMultiplier);
        }
        if (blockBudget < 0) {
            throw new IllegalArgumentException(
                    "blockBudget must be non-negative: " + blockBudget);
        }
        if (minPlotSize <= 0) {
            throw new IllegalArgumentException(
                    "minPlotSize must be positive: " + minPlotSize);
        }
        if (targetPlotCount < 0) {
            throw new IllegalArgumentException(
                    "targetPlotCount must be non-negative: " + targetPlotCount);
        }
        if (floodFillSlopeLimit < 0) {
            throw new IllegalArgumentException(
                    "floodFillSlopeLimit must be non-negative: " + floodFillSlopeLimit);
        }
        if (pathStyle == null) pathStyle = PathTopology.SPINE_WITH_BRANCHES;

        // Defensive copies; null-tolerant.
        if (plotTypeMix == null) {
            plotTypeMix = Map.of();
        } else if (!plotTypeMix.isEmpty()) {
            EnumMap<CropType, Float> stable = new EnumMap<>(CropType.class);
            stable.putAll(plotTypeMix);
            plotTypeMix = Map.copyOf(stable);
        } else {
            plotTypeMix = Map.of();
        }
        borderStylePool = borderStylePool == null ? List.of() : List.copyOf(borderStylePool);
        blockedBiomes   = blockedBiomes   == null ? Set.of()  : Set.copyOf(blockedBiomes);

        if (borderStylePool.isEmpty()) {
            throw new IllegalArgumentException(
                    "borderStylePool must contain at least one style");
        }
    }
}
