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
                    JsonObject b = el.getAsJsonObject();
                    JsonArray off = b.getAsJsonArray("offset");
                    buildings.add(new VillageTypeData.StarterBuilding(
                            b.get("type").getAsString(),
                            b.get("structure").getAsString(),
                            new int[]{ off.get(0).getAsInt(),
                                    off.get(1).getAsInt(),
                                    off.get(2).getAsInt() }
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

                loaded.put(type, new VillageTypeData(
                        type, culture, buildings, npcs, items));
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
