// FILE: src/main/java/tterrag1112/life_in_the_village/Datagen/VillageTypeBuilder.java
package tterrag1112.life_in_the_village.Village;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Planning.Terrain.TerrainStrategy;
import tterrag1112.life_in_the_village.Village.VillageTypeData.ShapeType;

/**
 * Fluent builder for village type JSON definitions. Replaces the
 * stringly-typed helper methods on {@link VillageTypeDatagen} with
 * a typed API that catches typos at compile time and reads naturally.
 *
 * <h3>Example</h3>
 * <pre>{@code
 * VillageTypeBuilder.create("default_village", "default")
 *     .shape(ShapeType.RADIAL)
 *     .terrainStrategy(TerrainStrategy.FLAT)
 *     .townSquareCapacity(5)
 *     .building(BuildingType.TOWN_HALL, "town_hall/level_1")
 *     .building(BuildingType.MARKET, "market/level_1")
 *     .buildingN(BuildingType.HOUSE, "house/level_1", 3)
 *     .buildingRange(BuildingType.FARMHOUSE, "farmhouse/level_1", 2, 4)
 *     .build();
 * }</pre>
 */
public final class VillageTypeBuilder {

    private final JsonObject root = new JsonObject();
    private final JsonObject shapeProfile = new JsonObject();
    private final JsonArray buildings = new JsonArray();

    private VillageTypeBuilder(String type, String culture) {
        root.addProperty("type", type);
        root.addProperty("culture", culture);
        // Set sensible shape profile defaults — overridden by .shape()
        shapeProfile.addProperty("shape_type", ShapeType.RADIAL.name());
        shapeProfile.addProperty("forced_axis", false);
        shapeProfile.addProperty("max_rings", 2);
        shapeProfile.addProperty("street_density", 1.0f);
        shapeProfile.addProperty("walled_by_default", false);
    }

    /** Creates a new builder. */
    public static VillageTypeBuilder create(String type, String culture) {
        return new VillageTypeBuilder(type, culture);
    }

    // ─── Shape profile ─────────────────────────────────────────────────────

    public VillageTypeBuilder shape(ShapeType shape) {
        shapeProfile.addProperty("shape_type", shape.name());
        return this;
    }

    public VillageTypeBuilder forcedAxis(boolean forced) {
        shapeProfile.addProperty("forced_axis", forced);
        return this;
    }

    public VillageTypeBuilder maxRings(int rings) {
        shapeProfile.addProperty("max_rings", rings);
        return this;
    }

    public VillageTypeBuilder streetDensity(float density) {
        shapeProfile.addProperty("street_density", density);
        return this;
    }

    public VillageTypeBuilder walled(boolean walled) {
        shapeProfile.addProperty("walled_by_default", walled);
        return this;
    }

    /** Sets the variant style profile: {@code "rural"}, {@code "urban"},
     *  or {@code "auto"} (the default when omitted). */
    public VillageTypeBuilder style(String style) {
        root.addProperty("style", style);
        return this;
    }

    // ─── Top-level fields ──────────────────────────────────────────────────

    public VillageTypeBuilder terrainStrategy(TerrainStrategy strategy) {
        root.addProperty("terrain_strategy", strategy.name());
        return this;
    }

    public VillageTypeBuilder townSquareCapacity(int capacity) {
        root.addProperty("town_square_capacity", capacity);
        return this;
    }

    // ─── Building entries ──────────────────────────────────────────────────

    /** Single instance of a building type. min/max default to 1. */
    public VillageTypeBuilder building(BuildingType type, String structure) {
        JsonObject b = new JsonObject();
        b.addProperty("type", type.name());
        b.addProperty("structure", structure);
        buildings.add(b);
        return this;
    }

    /** Exact count — places exactly {@code count} instances. */
    public VillageTypeBuilder buildingN(BuildingType type, String structure, int count) {
        JsonObject b = new JsonObject();
        b.addProperty("type", type.name());
        b.addProperty("structure", structure);
        b.addProperty("min_count", count);
        b.addProperty("max_count", count);
        buildings.add(b);
        return this;
    }

    /** Count range — planner picks a random number in [min, max] at spawn. */
    public VillageTypeBuilder buildingRange(BuildingType type, String structure,
                                            int minCount, int maxCount) {
        JsonObject b = new JsonObject();
        b.addProperty("type", type.name());
        b.addProperty("structure", structure);
        b.addProperty("min_count", minCount);
        b.addProperty("max_count", maxCount);
        buildings.add(b);
        return this;
    }

    // ─── Finalization ──────────────────────────────────────────────────────

    /** Returns the assembled JSON object. */
    public JsonObject build() {
        root.add("shape_profile", shapeProfile);
        root.add("starter_buildings", buildings);
        return root;
    }
}