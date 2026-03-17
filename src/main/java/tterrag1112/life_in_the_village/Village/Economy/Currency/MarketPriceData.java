package tterrag1112.life_in_the_village.Village.Economy.Currency;

import net.minecraft.world.item.Item;

import java.util.Map;
import java.util.Optional;

public class MarketPriceData {

    public record ItemPrice(Item item, long buyPrice, long sellPrice) {}

    private final String culture;
    private final Map<Item, ItemPrice> prices;

    public MarketPriceData(String culture, Map<Item, ItemPrice> prices) {
        this.culture = culture;
        this.prices = Map.copyOf(prices);
    }

    public String getCulture() { return culture; }

    public Optional<ItemPrice> getPrice(Item item) {
        return Optional.ofNullable(prices.get(item));
    }

    public Map<Item, ItemPrice> getAllPrices() { return prices; }
}
