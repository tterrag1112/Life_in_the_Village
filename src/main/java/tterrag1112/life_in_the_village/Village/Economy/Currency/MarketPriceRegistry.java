package tterrag1112.life_in_the_village.Village.Economy.Currency;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.Item;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tterrag1112.life_in_the_village.Life_in_the_village;

import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

public class MarketPriceRegistry extends
        SimplePreparableReloadListener<Map<String, MarketPriceData>> {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(MarketPriceRegistry.class);
    private static final Gson GSON = new Gson();
    public static final String DEFAULT_CULTURE = "default";

    public static final MarketPriceRegistry INSTANCE = new MarketPriceRegistry();

    private Map<String, MarketPriceData> cultures = new HashMap<>();

    private MarketPriceRegistry() {}

    @Override
    protected Map<String, MarketPriceData> prepare(ResourceManager manager,
                                                   ProfilerFiller profiler) {
        Map<String, MarketPriceData> loaded = new HashMap<>();

        manager.listResources("market_prices",
                path -> path.getNamespace().equals(Life_in_the_village.MODID)
                        && path.getPath().endsWith(".json")
        ).forEach((location, resource) -> {
            try {
                JsonObject json = GSON.fromJson(
                        new InputStreamReader(resource.open()),
                        JsonObject.class
                );

                String culture = json.has("culture")
                        ? json.get("culture").getAsString() : DEFAULT_CULTURE;

                Map<Item, MarketPriceData.ItemPrice> prices = new HashMap<>();
                for (var el : json.getAsJsonArray("prices")) {
                    JsonObject p = el.getAsJsonObject();
                    Identifier itemId =
                            Identifier.parse(p.get("item").getAsString());
                    long buy  = p.get("buy").getAsLong();
                    long sell = p.get("sell").getAsLong();

                    BuiltInRegistries.ITEM.getOptional(itemId).ifPresent(item ->
                            prices.put(item, new MarketPriceData.ItemPrice(item, buy, sell))
                    );
                }

                loaded.put(culture, new MarketPriceData(culture, prices));
                LOGGER.info("Loaded market prices for culture '{}': {} items",
                        culture, prices.size());

            } catch (Exception e) {
                LOGGER.error("Failed to load market prices from {}: {}",
                        location, e.getMessage());
            }
        });

        return loaded;
    }

    @Override
    protected void apply(Map<String, MarketPriceData> prepared,
                         ResourceManager manager, ProfilerFiller profiler) {
        this.cultures = new HashMap<>(prepared);
    }

    public void loadFromServer(ResourceManager manager) {
        Map<String, MarketPriceData> loaded = prepare(manager, null);
        apply(loaded, manager, null);
    }

    public MarketPriceData getCulture(String culture) {
        return cultures.getOrDefault(culture,
                cultures.getOrDefault(DEFAULT_CULTURE, null));
    }

    public MarketPriceData getDefault() {
        return getCulture(DEFAULT_CULTURE);
    }
}
