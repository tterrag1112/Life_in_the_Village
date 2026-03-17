package tterrag1112.life_in_the_village.Kingdom;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tterrag1112.life_in_the_village.Life_in_the_village;

import java.io.InputStreamReader;
import java.util.*;

public class KingdomTitleRegistry extends
        SimplePreparableReloadListener<Map<String, KingdomTitleData>> {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(KingdomTitleRegistry.class);
    private static final Gson GSON = new Gson();
    public static final String DEFAULT_CULTURE = "default";

    public static final KingdomTitleRegistry INSTANCE =
            new KingdomTitleRegistry();

    private Map<String, KingdomTitleData> cultures = new HashMap<>();

    private KingdomTitleRegistry() {}

    @Override
    protected Map<String, KingdomTitleData> prepare(
            ResourceManager manager, ProfilerFiller profiler) {
        Map<String, KingdomTitleData> loaded = new HashMap<>();

        manager.listResources("kingdom_titles",
                path -> path.getNamespace().equals(Life_in_the_village.MODID)
                        && path.getPath().endsWith(".json")
        ).forEach((location, resource) -> {
            try {
                JsonObject json = GSON.fromJson(
                        new InputStreamReader(resource.open()),
                        JsonObject.class);

                String culture = json.has("culture")
                        ? json.get("culture").getAsString() : DEFAULT_CULTURE;

                Map<String, String> titles = new HashMap<>();
                JsonObject titlesObj = json.getAsJsonObject("titles");
                titlesObj.entrySet().forEach(e ->
                        titles.put(e.getKey(), e.getValue().getAsString()));

                loaded.put(culture, new KingdomTitleData(culture, titles));
                LOGGER.info("Loaded kingdom titles for culture '{}'", culture);

            } catch (Exception e) {
                LOGGER.error("Failed to load kingdom titles from {}: {}",
                        location, e.getMessage());
            }
        });

        return loaded;
    }

    @Override
    protected void apply(Map<String, KingdomTitleData> prepared,
                         ResourceManager manager, ProfilerFiller profiler) {
        this.cultures = new HashMap<>(prepared);
    }

    public void loadFromServer(ResourceManager manager) {
        apply(prepare(manager, null), manager, null);
    }

    public KingdomTitleData getCulture(String culture) {
        return cultures.getOrDefault(culture,
                cultures.getOrDefault(DEFAULT_CULTURE, fallback()));
    }

    private KingdomTitleData fallback() {
        return new KingdomTitleData("fallback", Map.of(
                "ruler_male", "King", "ruler_female", "Queen",
                "lord_male", "Lord", "lord_female", "Lady",
                "knight_male", "Sir", "knight_female", "Dame",
                "citizen", "Citizen"
        ));
    }
}