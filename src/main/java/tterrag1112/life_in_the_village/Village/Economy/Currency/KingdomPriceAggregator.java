package tterrag1112.life_in_the_village.Village.Economy.Currency;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import tterrag1112.life_in_the_village.Kingdom.Kingdom;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Phase 5d — cross-village price aggregation for the kingdom price board.
 * For each tradeable (explicit-price) item across the kingdom's villages,
 * computes the per-village sell price (what a player pays) and surfaces the
 * cheapest source + dearest market + spread — making the 1c arbitrage the
 * caravans exploit legible.
 *
 * <p>Cost: O(villages × items) over the daily-cached {@code VillageSimData}
 * multiplier (no per-tick or live-entity scan). Bounded by {@link
 * #MAX_VILLAGES} so a huge kingdom can't blow the packet/loop.
 */
public final class KingdomPriceAggregator {

    /** Hard cap on villages aggregated (price board is a summary). */
    public static final int MAX_VILLAGES = 24;

    private KingdomPriceAggregator() {}

    /** One item's cross-village price summary. */
    public record ItemSpread(String itemId, String itemName,
                             String cheapestVillage, long cheapestPrice,
                             String dearestVillage, long dearestPrice,
                             List<VillagePrice> perVillage) {
        public long spread() { return Math.max(0, dearestPrice - cheapestPrice); }
    }

    /** Per-village price for the drill-in. */
    public record VillagePrice(String villageName, long sellPrice) {}

    /**
     * Builds the price board for a kingdom. Returns one {@link ItemSpread}
     * per tradeable item that at least one village prices, sorted by spread
     * (biggest arbitrage first). Empty when the kingdom has no villages.
     */
    public static List<ItemSpread> aggregate(ServerLevel level, Kingdom kingdom,
                                             VillageSavedData data) {
        List<Village> villages = new ArrayList<>();
        for (UUID vid : kingdom.getVillageIds()) {
            data.getVillageById(vid).ifPresent(villages::add);
            if (villages.size() >= MAX_VILLAGES) break;
        }
        List<ItemSpread> out = new ArrayList<>();
        if (villages.isEmpty()) return out;

        for (Item item : MarketPriceHelper.getAllExplicitPrices().keySet()) {
            List<VillagePrice> perVillage = new ArrayList<>();
            String cheapV = "", dearV = "";
            long cheapP = Long.MAX_VALUE, dearP = Long.MIN_VALUE;

            for (Village v : villages) {
                long price = MarketPriceHelper.getDynamicSellPrice(level, v, item);
                if (price <= 0) continue;
                perVillage.add(new VillagePrice(v.getName(), price));
                if (price < cheapP) { cheapP = price; cheapV = v.getName(); }
                if (price > dearP)  { dearP = price;  dearV = v.getName(); }
            }
            if (perVillage.isEmpty()) continue;

            String name = item.getName().getString();
            out.add(new ItemSpread(
                    BuiltInRegistries.ITEM.getKey(item).toString(), name,
                    cheapV, cheapP, dearV, dearP, perVillage));
        }
        // Biggest spread first — the most legible arbitrage at the top.
        out.sort((a, b) -> Long.compare(b.spread(), a.spread()));
        return out;
    }
}
