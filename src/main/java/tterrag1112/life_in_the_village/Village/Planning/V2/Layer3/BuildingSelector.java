package tterrag1112.life_in_the_village.Village.Planning.V2.Layer3;

import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Decoration.Variants.Style;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer1.V2FeatureMap;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.SiteContext;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.ViabilityTier;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * V2 Layer 3 building selection. Reads tier and inclination from the
 * {@link SiteContext}, looks up base counts in {@link InclinationProfile},
 * and emits a list of {@link BuildingType}s with multiplicity for
 * the dependency resolver and solver.
 *
 * <h3>Filters applied (in order)</h3>
 * <ol>
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

        for (BuildingType type : profile.baseCounts().keySet()) {
            int count = profile.countFor(type, tier);
            if (count <= 0) continue;

            PlacementProfile pp = PlacementDefaults.get(type);
            if (pp == null) continue;

            // 1. Hard terrain aggregates.
            if (!aggregatesPresent(pp.requiresAggregates(), fmap)) continue;

            // 2. NBT availability.
            if (!availability.isAvailable(culture, style, type, LEVEL)) {
                unavailable.add(new UnavailableBuilding(type,
                        "no NBT authored for " + culture));
                continue;
            }

            for (int i = 0; i < count; i++) selected.add(type);
        }
        return new SelectionResult(selected, unavailable);
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
