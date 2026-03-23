package tterrag1112.life_in_the_village.Village;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.InputStreamReader;
import java.util.*;

public class VillageTypeRegistry extends SimplePreparableReloadListener<Map<String, VillageTypeData>> {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(VillageTypeRegistry.class);
    private static final Gson GSON = new Gson();
    public static final String DEFAULT_TYPE = "default";

    public static final VillageTypeRegistry INSTANCE = new VillageTypeRegistry();

    private Map<String, VillageTypeData> types = new HashMap<>();

    private VillageTypeRegistry() {}

    @Override
    protected Map<String, VillageTypeData> prepare(ResourceManager manager,
                                                   ProfilerFiller profiler) {
        System.out.println("VillageTypeRegistry prepare() called");
        manager.listResources("village_types", path -> true)
                .forEach((loc, res) -> System.out.println("  Found resource: " + loc));
        manager.listResources("", path -> path.getPath().contains("village"))
                .forEach((loc, res) -> System.out.println("  Village resource: " + loc));

        Map<String, VillageTypeData> loaded = new HashMap<>();

        manager.listResources("village_types",
                path -> path.getPath().endsWith(".json")
        ).forEach((location, resource) -> {
            try {
                JsonObject json = GSON.fromJson(
                        new InputStreamReader(resource.open()),
                        JsonObject.class
                );

                String type = json.get("type").getAsString();
                String culture = json.has("culture")
                        ? json.get("culture").getAsString() : "default";

                List<VillageTypeData.StarterBuilding> buildings = new ArrayList<>();
                for (var el : json.getAsJsonArray("starter_buildings")) {
                    JsonObject b  = el.getAsJsonObject();
                    int minCount  = b.has("min_count") ? b.get("min_count").getAsInt() : 1;
                    int maxCount  = b.has("max_count") ? b.get("max_count").getAsInt() : minCount;
                    buildings.add(new VillageTypeData.StarterBuilding(
                            b.get("type").getAsString(),
                            b.get("structure").getAsString(),
                            minCount, maxCount
                    ));
                }

                List<VillageTypeData.StarterNpc> npcs = new ArrayList<>();
                for (var el : json.getAsJsonArray("starter_npcs")) {
                    JsonObject n = el.getAsJsonObject();
                    npcs.add(new VillageTypeData.StarterNpc(
                            n.get("profession").getAsString(),
                            n.get("building_type").getAsString(),
                            n.get("family_role").getAsString()
                    ));
                }

                List<VillageTypeData.StarterItem> items = new ArrayList<>();
                if (json.has("starter_items")) {
                    for (var el : json.getAsJsonArray("starter_items")) {
                        JsonObject i = el.getAsJsonObject();
                        items.add(new VillageTypeData.StarterItem(
                                i.get("building_type").getAsString(),
                                i.get("item").getAsString(),
                                i.get("count").getAsInt()
                        ));
                    }
                }

                VillageTypeData.VillageShapeProfile shapeProfile = VillageTypeData.VillageShapeProfile.defaultProfile();
                if (json.has("shape_profile")) {
                    JsonObject sp = json.getAsJsonObject("shape_profile");

                    VillageTypeData.ShapeType shapeType = VillageTypeData.ShapeType.RADIAL;
                    if (sp.has("shape_type")) {
                        try {
                            shapeType = VillageTypeData.ShapeType.valueOf(
                                    sp.get("shape_type").getAsString().toUpperCase());
                        } catch (IllegalArgumentException ignored) {
                            LOGGER.warn("Unknown shape_type '{}' in {}, defaulting to RADIAL",
                                    sp.get("shape_type").getAsString(), location);
                        }
                    }

                    boolean forcedAxis      = sp.has("forced_axis")
                            && sp.get("forced_axis").getAsBoolean();
                    int maxRings             = sp.has("max_rings")
                            ? sp.get("max_rings").getAsInt() : 2;
                    float streetDensity      = sp.has("street_density")
                            ? sp.get("street_density").getAsFloat() : 1.0f;
                    boolean walledByDefault  = sp.has("walled_by_default")
                            && sp.get("walled_by_default").getAsBoolean();

                    shapeProfile = new VillageTypeData.VillageShapeProfile(
                            shapeType, forcedAxis, maxRings, streetDensity, walledByDefault);
                }

                loaded.put(type, new VillageTypeData(
                        type, culture, buildings, npcs, items, shapeProfile));
                LOGGER.info("Loaded village type '{}'", type);

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
        return types.getOrDefault(type,
                types.getOrDefault(DEFAULT_TYPE, null));
    }

    public Set<String> getAvailableTypes() {
        return Collections.unmodifiableSet(types.keySet());
    }
    public void loadFromServer(ResourceManager manager) {
        Map<String, VillageTypeData> loaded = prepare(manager, null);
        apply(loaded, manager, null);
    }
}
