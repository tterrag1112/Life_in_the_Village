// FILE: src/main/java/tterrag1112/life_in_the_village/Village/VillageTypeRegistry.java
package tterrag1112.life_in_the_village.Village;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tterrag1112.life_in_the_village.Village.Planning.Rules.ShapeRule;
import tterrag1112.life_in_the_village.Village.Planning.Terrain.TerrainStrategy;

import java.io.InputStreamReader;
import java.util.*;

public class VillageTypeRegistry
        extends SimplePreparableReloadListener<Map<String, VillageTypeData>> {

    private static final Logger LOGGER = LoggerFactory.getLogger(VillageTypeRegistry.class);
    private static final Gson GSON = new Gson();
    public static final String DEFAULT_TYPE = "default_village";

    public static final VillageTypeRegistry INSTANCE = new VillageTypeRegistry();
    private Map<String, VillageTypeData> types = new HashMap<>();

    private VillageTypeRegistry() {}

    @Override
    protected Map<String, VillageTypeData> prepare(ResourceManager manager,
                                                   ProfilerFiller profiler) {
        Map<String, VillageTypeData> loaded = new HashMap<>();

        manager.listResources("village_types",
                path -> path.getPath().endsWith(".json")
        ).forEach((location, resource) -> {
            try {
                JsonObject json = GSON.fromJson(
                        new InputStreamReader(resource.open()), JsonObject.class);

                String type = json.get("type").getAsString();
                String culture = json.has("culture")
                        ? json.get("culture").getAsString() : "default";

                // Starter buildings
                List<VillageTypeData.StarterBuilding> buildings = new ArrayList<>();
                for (var el : json.getAsJsonArray("starter_buildings")) {
                    JsonObject b = el.getAsJsonObject();
                    int minCount = b.has("min_count") ? b.get("min_count").getAsInt() : 1;
                    int maxCount = b.has("max_count") ? b.get("max_count").getAsInt() : minCount;
                    buildings.add(new VillageTypeData.StarterBuilding(
                            b.get("type").getAsString(),
                            b.get("structure").getAsString(),
                            minCount, maxCount));
                }

                // Shape profile
                VillageTypeData.VillageShapeProfile shapeProfile =
                        VillageTypeData.VillageShapeProfile.defaultProfile();
                if (json.has("shape_profile")) {
                    JsonObject sp = json.getAsJsonObject("shape_profile");
                    VillageTypeData.ShapeType shapeType = VillageTypeData.ShapeType.RADIAL;
                    if (sp.has("shape_type")) {
                        try {
                            shapeType = VillageTypeData.ShapeType.valueOf(
                                    sp.get("shape_type").getAsString().toUpperCase());
                        } catch (IllegalArgumentException ignored) {
                            LOGGER.warn("Unknown shape_type in {}", location);
                        }
                    }
                    boolean forcedAxis = sp.has("forced_axis") && sp.get("forced_axis").getAsBoolean();
                    int maxRings = sp.has("max_rings") ? sp.get("max_rings").getAsInt() : 2;
                    float streetDensity = sp.has("street_density")
                            ? sp.get("street_density").getAsFloat() : 1.0f;
                    boolean walledByDefault = sp.has("walled_by_default")
                            && sp.get("walled_by_default").getAsBoolean();
                    shapeProfile = new VillageTypeData.VillageShapeProfile(
                            shapeType, forcedAxis, maxRings, streetDensity, walledByDefault);
                }

                // Shape rules
                List<ShapeRule> shapeRules = new ArrayList<>();
                if (json.has("shape_rules")) {
                    shapeRules = new ArrayList<>();
                    for (var el : json.getAsJsonArray("shape_rules")) {
                        if (!el.isJsonObject()) continue;
                        ShapeRule rule = ShapeRule.Registry.parse(el.getAsJsonObject());
                        if (rule != null) shapeRules.add(rule);
                    }
                }

                // Terrain strategy
                String strategyName = json.has("terrain_strategy")
                        ? json.get("terrain_strategy").getAsString() : null;
                TerrainStrategy strategy = TerrainStrategy.fromName(strategyName);

                // Farm plot config
                VillageTypeData.FarmPlotConfig farmPlotConfig =
                        VillageTypeData.FarmPlotConfig.defaultConfig();
                if (json.has("farm_plot_config")) {
                    JsonObject fp = json.getAsJsonObject("farm_plot_config");
                    VillageTypeData.FarmPlotPlacement placement =
                            VillageTypeData.FarmPlotPlacement.PERIMETER_OUTSIDE;
                    if (fp.has("placement")) {
                        try {
                            placement = VillageTypeData.FarmPlotPlacement.valueOf(
                                    fp.get("placement").getAsString().toUpperCase());
                        } catch (IllegalArgumentException ignored) {}
                    }
                    int minDistance = fp.has("min_distance") ? fp.get("min_distance").getAsInt() : 8;
                    int maxDistance = fp.has("max_distance") ? fp.get("max_distance").getAsInt() : 32;
                    boolean allowAnimalPens = !fp.has("allow_animal_pens")
                            || fp.get("allow_animal_pens").getAsBoolean();
                    int plotsPerFarmhouse = fp.has("plots_per_farmhouse")
                            ? fp.get("plots_per_farmhouse").getAsInt() : 1;
                    farmPlotConfig = new VillageTypeData.FarmPlotConfig(
                            placement, minDistance, maxDistance,
                            allowAnimalPens, plotsPerFarmhouse);
                }
                Set<VillageTag> manualTags = EnumSet.noneOf(VillageTag.class);
                if (json.has("tags")) {
                    for (var el : json.getAsJsonArray("tags")) {
                        if (!el.isJsonPrimitive()) continue;
                        VillageTag tag = VillageTag.fromName(el.getAsString());
                        if (tag != null) manualTags.add(tag);
                        else LOGGER.warn("Unknown village tag '{}' in {}", el.getAsString(), location);
                    }
                }

                int townSquareCapacity = json.has("town_square_capacity")
                        ? json.get("town_square_capacity").getAsInt() : 4;

                VillageTypeData typeData = new VillageTypeData(
                        type, culture, buildings,
                        shapeProfile, farmPlotConfig, strategy, townSquareCapacity,
                        manualTags);
                typeData.setShapeRules(shapeRules);
                if (json.has("style")) {
                    typeData.setStyle(json.get("style").getAsString());
                }

                loaded.put(type, typeData);
                LOGGER.info("Loaded village type '{}' ({} buildings, shape={}, strategy={}, tags={})",
                        type, buildings.size(), shapeProfile.shapeType(), strategy, typeData.getTags());

            } catch (Exception e) {
                LOGGER.error("Failed to load village type from {}: {}",
                        location, e.getMessage());
            }
        });

        return loaded;
    }

    @Override
    protected void apply(Map<String, VillageTypeData> prepared,
                         ResourceManager manager, ProfilerFiller profiler) {
        this.types = new HashMap<>(prepared);
        LOGGER.info("Village type registry loaded {} types", types.size());
    }

    public VillageTypeData getType(String type) {
        return types.getOrDefault(type, types.getOrDefault(DEFAULT_TYPE, null));
    }

    public Set<String> getAvailableTypes() {
        return Collections.unmodifiableSet(types.keySet());
    }

    public void loadFromServer(ResourceManager manager) {
        Map<String, VillageTypeData> loaded = prepare(manager, null);
        apply(loaded, manager, null);
    }
}