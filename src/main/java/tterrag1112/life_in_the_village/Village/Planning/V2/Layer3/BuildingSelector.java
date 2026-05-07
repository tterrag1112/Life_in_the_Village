package tterrag1112.life_in_the_village.Village.Planning.V2.Layer3;

import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer1.V2FeatureMap;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.SiteContext;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.ViabilityTier;

import java.util.ArrayList;
import java.util.List;

/**
 * V2 Layer 3 building selection. Reads tier and inclination from the
 * {@link SiteContext}, looks up base counts in {@link InclinationProfile},
 * filters by hard terrain requirements
 * ({@link PlacementProfile#requiresAggregates}), and emits a list of
 * {@link BuildingType}s with multiplicity for the dependency
 * resolver and solver.
 *
 * <p>Selection happens before any positioning. A building filtered
 * here never reaches the solver — its {@link DropReason} is
 * {@link DropReason#NOT_SELECTED}, recorded by the caller.
 */
public final class BuildingSelector {

    private BuildingSelector() {}

    /**
     * @return list of building types to attempt placement for, with
     *         multiplicity. Empty if tier is UNVIABLE or no rostered
     *         types qualify.
     */
    public static List<BuildingType> select(SiteContext ctx, V2FeatureMap fmap,
                                            InclinationProfile profile) {
        List<BuildingType> out = new ArrayList<>();
        ViabilityTier tier = ctx.tier();
        if (tier == ViabilityTier.UNVIABLE) return out;

        for (BuildingType type : profile.baseCounts().keySet()) {
            int count = profile.countFor(type, tier);
            if (count <= 0) continue;
            PlacementProfile pp = PlacementDefaults.get(type);
            if (pp == null) continue;
            if (!aggregatesPresent(pp.requiresAggregates(), fmap)) continue;
            for (int i = 0; i < count; i++) out.add(type);
        }
        return out;
    }

    private static boolean aggregatesPresent(java.util.Set<TerrainAggregate> required,
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
}
