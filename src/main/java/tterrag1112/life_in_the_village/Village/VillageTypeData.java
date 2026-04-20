// FILE: src/main/java/tterrag1112/life_in_the_village/Village/VillageTypeData.java
package tterrag1112.life_in_the_village.Village;

import tterrag1112.life_in_the_village.Village.Planning.Rules.ShapeRule;
import tterrag1112.life_in_the_village.Village.Planning.Terrain.TerrainStrategy;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Immutable data loaded from a village type JSON file.
 *
 * <h3>What's gone</h3>
 * Capitals, NPCs, and starter items have all been removed from the
 * village type data. NPCs are spawned by the building inhabitant
 * system based on what buildings end up being placed; starter items
 * are seeded elsewhere; capitals will be reintroduced as a shape
 * recipe in a later phase.
 */
public class VillageTypeData {

    public record StarterBuilding(
            String type,
            String structure,
            int minCount,
            int maxCount
    ) {
        public int resolveCount(java.util.Random rng) {
            if (maxCount <= minCount) return minCount;
            return minCount + rng.nextInt(maxCount - minCount + 1);
        }
    }

    // =========================================================================
    // Farm plot config
    // =========================================================================

    public record FarmPlotConfig(
            FarmPlotPlacement placement,
            int minDistance,
            int maxDistance,
            boolean allowAnimalPens,
            int plotsPerFarmhouse
    ) {
        public static FarmPlotConfig defaultConfig() {
            return new FarmPlotConfig(FarmPlotPlacement.PERIMETER_OUTSIDE, 8, 32, true, 1);
        }
        public static FarmPlotConfig integrated() {
            return new FarmPlotConfig(FarmPlotPlacement.INTEGRATED, 0, 16, false, 1);
        }
        public static FarmPlotConfig distantFields() {
            return new FarmPlotConfig(FarmPlotPlacement.DISTANT_FIELDS, 40, 80, true, 2);
        }
    }

    public enum FarmPlotPlacement {
        PERIMETER_OUTSIDE, INTEGRATED, DISTANT_FIELDS, NONE
    }

    // =========================================================================
    // Shape profile
    // =========================================================================

    public record VillageShapeProfile(
            ShapeType shapeType,
            boolean forcedAxis,
            int maxRings,
            float streetDensity,
            boolean walledByDefault
    ) {
        public static VillageShapeProfile defaultProfile() {
            return new VillageShapeProfile(ShapeType.RADIAL, false, 2, 1.0f, false);
        }
    }

    /**
     * All currently implemented village layouts. Adding a new layout
     * means adding a constant here, registering a recipe in
     * {@link tterrag1112.life_in_the_village.Village.Planning.Primitives.ShapeRecipe},
     * and writing the recipe class.
     */
    public enum ShapeType {
        RADIAL,
        LINEAR,
        CLUSTERED,
        RIVERINE,
        HILLTOP,
        PLAZA,
        CROSSROADS,
        CHAIN,
        ROADSIDE,
        GROVE,
        SPRAWL,
        DOCKSIDE,
        DUAL_PLAZA,
        OUTPOST,
        TERRACED,
        ENCLAVE,
        DUMBELL
    }

    // =========================================================================
    // Fields
    // =========================================================================

    private final String type;
    public final String culture;
    private final List<StarterBuilding> starterBuildings;
    private final FarmPlotConfig farmPlotConfig;
    private final VillageShapeProfile shapeProfile;
    private final TerrainStrategy terrainStrategy;
    private final int townSquareCapacity;
    private List<ShapeRule> shapeRules = List.of();
    private final Set<VillageTag> tags;


    // =========================================================================
    // Single canonical constructor
    // =========================================================================

    public VillageTypeData(String type, String culture,
                           List<StarterBuilding> starterBuildings,
                           VillageShapeProfile shapeProfile,
                           FarmPlotConfig farmPlotConfig,
                           TerrainStrategy terrainStrategy,
                           int townSquareCapacity,
                           Set<VillageTag> manualTags) {
        this.type = type;
        this.culture = culture;
        this.starterBuildings = List.copyOf(starterBuildings);
        this.shapeProfile = shapeProfile != null
                ? shapeProfile : VillageShapeProfile.defaultProfile();
        this.farmPlotConfig = farmPlotConfig != null
                ? farmPlotConfig : FarmPlotConfig.defaultConfig();
        this.terrainStrategy = terrainStrategy != null
                ? terrainStrategy : TerrainStrategy.FLAT;
        this.townSquareCapacity = townSquareCapacity > 0 ? townSquareCapacity : 6;

        // Union manual + derived tags
        Set<VillageTag> combined = EnumSet.noneOf(VillageTag.class);
        if (manualTags != null) combined.addAll(manualTags);
        combined.addAll(VillageTagDeriver.derive(
                this.terrainStrategy, this.shapeProfile.shapeType(),
                this.starterBuildings));
        this.tags = java.util.Collections.unmodifiableSet(combined);
    }

    // Keep the old canonical constructor as a deprecated overload that delegates
// with empty manual tags — avoids breaking existing call sites:
    public VillageTypeData(String type, String culture,
                           List<StarterBuilding> starterBuildings,
                           VillageShapeProfile shapeProfile,
                           FarmPlotConfig farmPlotConfig,
                           TerrainStrategy terrainStrategy,
                           int townSquareCapacity) {
        this(type, culture, starterBuildings, shapeProfile, farmPlotConfig,
                terrainStrategy, townSquareCapacity, EnumSet.noneOf(VillageTag.class));
    }

    // Also update the test-convenience constructor — just add empty tags:
    public VillageTypeData(String type, String culture,
                           List<StarterBuilding> starterBuildings) {
        this(type, culture, starterBuildings,
                VillageShapeProfile.defaultProfile(),
                FarmPlotConfig.defaultConfig(),
                TerrainStrategy.FLAT,
                4,
                EnumSet.noneOf(VillageTag.class));
    }

    // =========================================================================
    // Getters
    // =========================================================================

    public String getType() { return type; }
    public String getCulture() { return culture; }
    public List<StarterBuilding> getStarterBuildings() { return starterBuildings; }
    public VillageShapeProfile getShapeProfile() { return shapeProfile; }
    public FarmPlotConfig getFarmPlotConfig() { return farmPlotConfig; }
    public TerrainStrategy getTerrainStrategy() { return terrainStrategy; }
    public int getTownSquareCapacity() { return townSquareCapacity; }

    public List<ShapeRule> getShapeRules() { return shapeRules; }
    public void setShapeRules(List<ShapeRule> rules) {
        this.shapeRules = List.copyOf(rules);
    }
    public Set<VillageTag> getTags() { return tags;}
}