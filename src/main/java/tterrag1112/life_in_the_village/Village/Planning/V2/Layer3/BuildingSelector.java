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
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * V2 Layer 3 building selection. Reads tier and inclination from the
 * {@link SiteContext}, looks up base counts in {@link InclinationProfile},
 * and emits a list of {@link BuildingType}s with multiplicity for
 * the dependency resolver and solver.
 *
 * <h3>Filters applied (in order)</h3>
 * <ol>
 *   <li>Strategy-level exclusion ({@link LayoutStrategy#excludedBuildings})
 *       — Track E1 prompt 5. Buildings the selected strategy can't
 *       support (e.g. MINE in {@code industrial_haufendorf} which
 *       has no cliff anchor) are dropped at selection time so they
 *       don't reach the placer and drop there.</li>
 *   <li>Hard terrain aggregates ({@link PlacementProfile#requiresAggregates}) —
 *       silently skipped if absent.</li>
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
    /** Salt mixed into the seed for selection-count sampling so it
     *  doesn't share Random state with other variation samplers. */
    private static final long SELECTION_SALT = 0x5E1E_C710_BABE_500AL;

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
        Random rng = new Random(ctx.seed() ^ SELECTION_SALT);

        // Track E1 prompt 5 — strategy-level exclusion. Resolved at
        // the start so the filter is one map read per type. When the
        // strategy hasn't been selected yet (UNVIABLE-adjacent early
        // dumps), the set is empty and the loop falls through.
        Set<BuildingType> excluded = ctx.strategy() != null
                && ctx.strategy().strategy() != null
                ? ctx.strategy().strategy().excludedBuildings()
                : Set.of();

        for (BuildingType type : profile.baseCounts().keySet()) {
            int target = profile.countFor(type, tier);
            if (target <= 0) continue;

            // 1. Strategy-level exclusion.
            if (excluded.contains(type)) {
                LOGGER.info("selection {}: excluded by strategy {} (target was {})",
                        type, ctx.strategy().strategy().id(), target);
                continue;
            }

            PlacementProfile pp = PlacementDefaults.get(type);
            if (pp == null) continue;

            // 2. Hard terrain aggregates.
            if (!aggregatesPresent(pp.requiresAggregates(), fmap)) continue;

            // 3. NBT availability.
            if (!availability.isAvailable(culture, style, type, LEVEL)) {
                unavailable.add(new UnavailableBuilding(type,
                        "no NBT authored for " + culture));
                continue;
            }

            // A3: sample the actual count from an auto-derived range
            // around the profile target. Floor preserves required
            // singletons (target=1 stays 1).
            int count = sampleCount(target, rng);
            if (count != target) {
                LOGGER.info("selection {}: target={} sampled={}", type, target, count);
            }
            for (int i = 0; i < count; i++) selected.add(type);
        }
        return new SelectionResult(selected, unavailable);
    }

    /** Triangular-ish range sampler with the profile target as the
     *  mode. Range derived as
     *  {@code [ceil(t*0.7), floor(t*1.25)]} per Cycle 2 spec; required
     *  singletons (target=1) collapse to 1 so TOWN_HALL etc. stay
     *  exact. */
    private static int sampleCount(int target, Random rng) {
        if (target <= 1) return target;
        int lo = (int) Math.ceil(target * 0.7);
        int hi = (int) Math.floor(target * 1.25);
        if (hi <= lo) return target;
        // Triangular: u<f → lo + sqrt(u*(hi-lo)*(target-lo));
        // u≥f → hi - sqrt((1-u)*(hi-lo)*(hi-target)).
        double u = rng.nextDouble();
        double f = (target - lo) / (double) (hi - lo);
        double x = u < f
                ? lo + Math.sqrt(u * (hi - lo) * (target - lo))
                : hi - Math.sqrt((1 - u) * (hi - lo) * (hi - target));
        return Math.max(lo, Math.min(hi, (int) Math.round(x)));
    }

    private static boolean aggregatesPresent(Set<TerrainAggregate> required,
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
