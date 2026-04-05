// src/main/java/tterrag1112/life_in_the_village/Village/Economy/Currency/MarketPriceHelper.java
package tterrag1112.life_in_the_village.Village.Economy.Currency;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/**
 * Single entry point for all market price lookups.
 *
 * <h3>Why this exists</h3>
 * Previously, price lookups were scattered across
 * {@code VillageEconomy.getBasePrice()}, {@code TradeHandler},
 * {@code MerchantGoal}, {@code SellToMarketGoal}, and individual
 * profession goals — each with slightly different fallback logic and
 * direct calls into {@code MarketPriceRegistry.INSTANCE.getDefault()}.
 *
 * <p>This helper provides one consistent API for:
 * <ul>
 *   <li>Base prices (raw JSON values, with fallback defaults)</li>
 *   <li>Dynamic prices (base + supply/demand multiplier via
 *       {@link DynamicPriceCalculator})</li>
 *   <li>Culture-aware variants</li>
 * </ul>
 *
 * <h3>Fallback behaviour</h3>
 * Every method returns a valid price. If an item isn't explicitly listed
 * in the JSON, the culture's {@code default_buy}/{@code default_sell} is
 * used. If the culture itself is missing, the {@code default} culture is
 * used. If no culture is loaded at all, a hard-coded minimum of 1/2 bronze
 * is returned.
 */
public final class MarketPriceHelper {

    /** Hard-coded absolute fallback if the registry is completely empty. */
    private static final long EMERGENCY_BUY  = 1L;
    private static final long EMERGENCY_SELL = 2L;

    private MarketPriceHelper() {}

    // =========================================================================
    // Base prices (no village context)
    // =========================================================================

    /**
     * Base sell price (what a player pays to buy) for an item.
     * Uses the default culture. Falls back to the culture's default_sell,
     * then to an emergency constant.
     */
    public static long getBaseSellPrice(Item item) {
        MarketPriceData data = MarketPriceRegistry.INSTANCE.getDefault();
        if (data == null) return EMERGENCY_SELL;
        return data.getSellPriceOrDefault(item);
    }

    /**
     * Base buy price (what a player receives when selling) for an item.
     */
    public static long getBaseBuyPrice(Item item) {
        MarketPriceData data = MarketPriceRegistry.INSTANCE.getDefault();
        if (data == null) return EMERGENCY_BUY;
        return data.getBuyPriceOrDefault(item);
    }

    /** Culture-specific base sell price. */
    public static long getBaseSellPrice(String culture, Item item) {
        MarketPriceData data = MarketPriceRegistry.INSTANCE.getCulture(culture);
        if (data == null) return getBaseSellPrice(item);
        return data.getSellPriceOrDefault(item);
    }

    /** Culture-specific base buy price. */
    public static long getBaseBuyPrice(String culture, Item item) {
        MarketPriceData data = MarketPriceRegistry.INSTANCE.getCulture(culture);
        if (data == null) return getBaseBuyPrice(item);
        return data.getBuyPriceOrDefault(item);
    }

    // =========================================================================
    // Dynamic prices (with village supply/demand)
    // =========================================================================

    /**
     * Dynamic sell price — base price modified by village supply/demand.
     * Falls back to base price if the village is null.
     */
    public static long getDynamicSellPrice(ServerLevel level,
                                           Village village, Item item) {
        //long base = getBaseSellPrice(village != null ? village.getCulture() : null, item);
        long base = getBaseSellPrice(item);
        if (village == null) return base;
        VillageSavedData data = VillageSavedData.get(level);
        return DynamicPriceCalculator.getSellPrice(level, village, data, item, base);
    }

    public static long getDynamicBuyPrice(ServerLevel level,
                                          Village village, Item item) {
       // long base = getBaseBuyPrice(village != null ? village.getCulture() : null, item);
        long base = getBaseBuyPrice(item);
        if (village == null) return base;
        VillageSavedData data = VillageSavedData.get(level);
        return DynamicPriceCalculator.getBuyPrice(level, village, data, item, base);
    }

    /** Dynamic sell price by village ID. */
    public static long getDynamicSellPrice(ServerLevel level,
                                           UUID villageId, Item item) {
        Village village = VillageSavedData.get(level)
                .getVillageById(villageId).orElse(null);
        return getDynamicSellPrice(level, village, item);
    }

    /** Dynamic buy price by village ID. */
    public static long getDynamicBuyPrice(ServerLevel level,
                                          UUID villageId, Item item) {
        Village village = VillageSavedData.get(level)
                .getVillageById(villageId).orElse(null);
        return getDynamicBuyPrice(level, village, item);
    }

    // =========================================================================
    // Queries
    // =========================================================================

    /** True if the item has an explicit entry in the default culture. */
    public static boolean isExplicitlyListed(Item item) {
        MarketPriceData data = MarketPriceRegistry.INSTANCE.getDefault();
        return data != null && data.isExplicitlyListed(item);
    }

    /** All items explicitly priced in the default culture. */
    public static Collection<Item> getAllListedItems() {
        MarketPriceData data = MarketPriceRegistry.INSTANCE.getDefault();
        return data != null ? data.getAllListedItems() : java.util.List.of();
    }

    /** Returns the full ItemPrice record if explicitly listed. */
    public static Optional<MarketPriceData.ItemPrice> getExplicitPrice(Item item) {
        MarketPriceData data = MarketPriceRegistry.INSTANCE.getDefault();
        if (data == null) return Optional.empty();
        return data.getPrice(item);
    }
}