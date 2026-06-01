package tterrag1112.life_in_the_village.Village.Planning.V2.Layer3;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Decoration.Variants.Style;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer1.V2FeatureMap;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.LayoutStrategy;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.SiteContext;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.ViabilityTier;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * V2 Layer 3 building selection. Reads tier and inclination from the
 * {@link SiteContext}, builds a population-first roster via
 * {@link PopulationRoster} (Layout Rework Step 3 / Stage 1), and emits a
 * list of {@link BuildingType}s with multiplicity for the dependency
 * resolver and solver.
 *
 * <h3>Counts</h3>
 * Counts come from {@link PopulationRoster#build} — a target NPC
 * population (mapped from tier) sized into housing + capped services.
 * The pre-Step-3 per-type triangular re-sampling is gone: the population
 * sampling + housing rounding is the variance source, and double-variance
 * would break the population target, so roster counts feed the filters
 * directly.
 *
 * <h3>Filters applied (in order)</h3>
 * <ol>
 *   <li>Strategy-level exclusion ({@link LayoutStrategy#excludedBuildings})
 *       — buildings the selected strategy can't support are dropped at
 *       selection time so they don't reach the placer and drop there.</li>
 *   <li>Hard terrain aggregates ({@link PlacementProfile#requiresAggregates}) —
 *       a backstop to the roster's own viability gate; silently skipped
 *       if absent.</li>
 *   <li>NBT availability ({@link BuildingAvailability#isAvailable}) —
 *       skipped types accumulate in {@link SelectionResult#unavailable}
 *       so the debug command can surface what's missing for the
 *       village's culture.</li>
 * </ol>
 *
 * <p>A type without a {@link PlacementDefaults} entry is silently
 * skipped (not marked unavailable — it's a roster mismatch, not an
 * authoring gap).
 */
public final class BuildingSelector {

    private static final Logger LOGGER = LoggerFactory.getLogger(BuildingSelector.class);

    /** All V1 placement happens at level 1. */
    private static final int LEVEL = 1;

    private BuildingSelector() {}

    public static SelectionResult select(SiteContext ctx, V2FeatureMap fmap,
                                         InclinationProfile profile) {
        return select(ctx, fmap, profile, StructureAvailabilityRegistry.INSTANCE);
    }

    /** Overload that takes an explicit {@link BuildingAvailability} —
     *  the production callers use the singleton. */
    public static SelectionResult select(SiteContext ctx, V2FeatureMap fmap,
                                         InclinationProfile profile,
                                         BuildingAvailability availability) {
        List<BuildingType> selected = new ArrayList<>();
        List<UnavailableBuilding> unavailable = new ArrayList<>();
        ViabilityTier tier = ctx.tier();
        if (tier == ViabilityTier.UNVIABLE) {
            return new SelectionResult(selected, unavailable);
        }

        String culture = ctx.culture().id();
        Style style = Style.RURAL;

        // Strategy-level exclusion. Resolved up front so the filter is
        // one set read per type. When the strategy hasn't been selected
        // (UNVIABLE-adjacent early dumps), the set is empty.
        Set<BuildingType> excluded = ctx.strategy() != null
                && ctx.strategy().strategy() != null
                ? ctx.strategy().strategy().excludedBuildings()
                : Set.of();

        // Population-first roster: target population → housing + capped
        // services, gated by inclination + terrain viability. Replaces
        // the per-tier count tables and the triangular sampler.
        Map<BuildingType, Integer> roster =
                PopulationRoster.build(profile, tier, fmap, ctx.seed());
        LOGGER.info("population roster tier={} inclination={}: {}",
                tier, profile.inclination(), PopulationRoster.ordered(roster));

        // Iterate in BuildingType.ordinal() order for cross-session
        // determinism of the multiplicity-expanded list.
        List<BuildingType> orderedTypes = new ArrayList<>(roster.keySet());
        orderedTypes.sort(Comparator.comparingInt(Enum::ordinal));
        for (BuildingType type : orderedTypes) {
            int count = roster.get(type);
            if (count <= 0) continue;

            // 1. Strategy-level exclusion.
            if (excluded.contains(type)) {
                LOGGER.info("selection {}: excluded by strategy {} (roster count was {})",
                        type, ctx.strategy().strategy().id(), count);
                continue;
            }

            PlacementProfile pp = PlacementDefaults.get(type);
            if (pp == null) continue;

            // 2. Hard terrain aggregates (backstop to the roster gate).
            if (!aggregatesPresent(pp.requiresAggregates(), fmap)) continue;

            // 3. NBT availability.
            if (!availability.isAvailable(culture, style, type, LEVEL)) {
                unavailable.add(new UnavailableBuilding(type,
                        "no NBT authored for " + culture));
                continue;
            }

            for (int i = 0; i < count; i++) selected.add(type);
        }
        return new SelectionResult(selected, unavailable);
    }

    /** Hard terrain-aggregate viability check. Package-visible so
     *  {@link PopulationRoster} reuses the exact same gate when it
     *  decides whether to budget population for a terrain-locked
     *  production building. */
    static boolean aggregatesPresent(Set<TerrainAggregate> required,
                                     V2FeatureMap fmap) {
        for (TerrainAggregate ta : required) {
            switch (ta) {
                case RIVER         -> { if (fmap.riverPath().isEmpty()) return false; }
                case COASTLINE     -> { if (fmap.coastline().isEmpty()) return false; }
                case FOREST_REGION -> { if (fmap.forestRegions().isEmpty()) return false; }
                case STONE_REGION  -> { if (fmap.stoneExposedRegions().isEmpty()) return false; }
                case HIGH_GROUND   -> { if (fmap.highGroundRegions().isEmpty()) return false; }
            }
        }
        return true;
    }

    /** Output of {@link #select}: the multiplicity-expanded selection
     *  plus the list of types skipped for missing NBTs. */
    public record SelectionResult(List<BuildingType> selected,
                                  List<UnavailableBuilding> unavailable) {}
}
