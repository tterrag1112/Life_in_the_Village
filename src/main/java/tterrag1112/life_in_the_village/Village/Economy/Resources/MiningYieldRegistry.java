package tterrag1112.life_in_the_village.Village.Economy.Resources;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tterrag1112.life_in_the_village.Life_in_the_village;

import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MiningYieldRegistry extends
        SimplePreparableReloadListener<Map<String, MiningYieldData>> {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(MiningYieldRegistry.class);
    private static final Gson GSON = new Gson();
    public static final String DEFAULT_TYPE = "default";

    public static final MiningYieldRegistry INSTANCE = new MiningYieldRegistry();
    private Map<String, MiningYieldData> types = new HashMap<>();

    private MiningYieldRegistry() {}

    @Override
    protected Map<String, MiningYieldData> prepare(ResourceManager manager,
                                                   ProfilerFiller profiler) {
        Map<String, MiningYieldData> loaded = new HashMap<>();

        manager.listResources("mining_yields",
                path -> path.getNamespace().equals(Life_in_the_village.MODID)
                        && path.getPath().endsWith(".json")
        ).forEach((location, resource) -> {
            try {
                JsonObject json = GSON.fromJson(
                        new InputStreamReader(resource.open()),
                        JsonObject.class
                );

                String type = json.get("type").getAsString();
                List<MiningYieldData.YieldEntry> yields = new ArrayList<>();

                for (var el : json.getAsJsonArray("yields")) {
                    JsonObject y = el.getAsJsonObject();
                    Identifier itemId =
                            Identifier.parse(y.get("item").getAsString());
                    MiningYieldData.Rarity rarity =
                            MiningYieldData.Rarity.valueOf(
                                    y.get("rarity").getAsString());
                    int min = y.get("min").getAsInt();
                    int max = y.get("max").getAsInt();

                    BuiltInRegistries.ITEM.getOptional(itemId)
                            .ifPresent(item ->
                                    yields.add(new MiningYieldData.YieldEntry(
                                            item, rarity, min, max))
                            );
                }

                loaded.put(type, new MiningYieldData(type, yields));
                LOGGER.info("Loaded mining yields for type '{}': {} entries",
                        type, yields.size());

            } catch (Exception e) {
                LOGGER.error("Failed to load mining yields from {}: {}",
                        location, e.getMessage());
            }
        });

        return loaded;
    }

    @Override
    protected void apply(Map<String, MiningYieldData> prepared,
                         ResourceManager manager, ProfilerFiller profiler) {
        this.types = new HashMap<>(prepared);
    }

    public void loadFromServer(ResourceManager manager) {
        apply(prepare(manager, null), manager, null);
    }

    public MiningYieldData getDefault() {
        return types.getOrDefault(DEFAULT_TYPE, null);
    }
}
