// src/main/java/tterrag1112/life_in_the_village/Village/Economy/Currency/MarketPriceData.java
package tterrag1112.life_in_the_village.Village.Economy.Currency;

import net.minecraft.world.item.Item;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * Immutable price table for a culture.
 *
 * <h3>Fallback defaults</h3>
 * Items not explicitly listed fall back to {@code defaultBuy} / {@code defaultSell}
 * from the JSON. This means the economy is complete for every item in the game
 * without needing to enumerate every single one.
 */
public class MarketPriceData {

    public record ItemPrice(Item item, long buyPrice, long sellPrice) {}

    private final String culture;
    private final Map<Item, ItemPrice> prices;
    private final long defaultBuy;
    private final long defaultSell;

    public MarketPriceData(String culture, Map<Item, ItemPrice> prices,
                           long defaultBuy, long defaultSell) {
        this.culture     = culture;
        this.prices      = Map.copyOf(prices);
        this.defaultBuy  = defaultBuy;
        this.defaultSell = defaultSell;
    }

    public String getCulture()     { return culture; }
    public long getDefaultBuy()    { return defaultBuy; }
    public long getDefaultSell()   { return defaultSell; }

    /** Explicit ItemPrice from the JSON, empty if not listed. */
    public Optional<ItemPrice> getPrice(Item item) {
        return Optional.ofNullable(prices.get(item));
    }

    /** Sell price for any item — uses default if not explicitly listed. */
    public long getSellPriceOrDefault(Item item) {
        ItemPrice p = prices.get(item);
        return p != null ? p.sellPrice() : defaultSell;
    }

    /** Buy price for any item — uses default if not explicitly listed. */
    public long getBuyPriceOrDefault(Item item) {
        ItemPrice p = prices.get(item);
        return p != null ? p.buyPrice() : defaultBuy;
    }

    /** Returns true if the item has an explicit entry in the JSON. */
    public boolean isExplicitlyListed(Item item) {
        return prices.containsKey(item);
    }

    /** All explicitly-listed prices. */
    public Map<Item, ItemPrice> getAllPrices() { return prices; }

    /** All explicitly-listed items. */
    public Collection<Item> getAllListedItems() { return prices.keySet(); }
}