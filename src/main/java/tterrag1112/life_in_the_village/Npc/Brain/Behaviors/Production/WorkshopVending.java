package tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Profession.WorkplaceAssignmentManager;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CoinHelper;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;
import tterrag1112.life_in_the_village.Village.Economy.VillageEconomy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * E-S1 — sell-side surplus detection and market-trip execution lifted
 * from {@link AbstractProductionBehavior} so that future context-based
 * production behaviors can detect and dispatch surplus sales through
 * the same pipeline.
 *
 * <h3>Two public surfaces</h3>
 * <ol>
 *   <li>{@link #computeSurplus} — pure query: given the output building,
 *       sellable item list, stock quotas, and surplus threshold, returns
 *       the items-and-quantities over the keep floor.  Stateless.</li>
 *   <li>{@link #isSellTime} — pure predicate: wraps the sell-window +
 *       throttle logic, consuming the caller's {@code lastDailySellTick}
 *       by value.</li>
 *   <li>{@link #executeSell} — the static market-trip helper formerly
 *       known as {@code AbstractProductionBehavior.executeSellForWorkshop}.
 *       {@link SellToMarketBehavior} now calls this directly;
 *       {@code AbstractProductionBehavior} keeps a forwarding delegate
 *       (also named {@code executeSellForWorkshop}) for any code that
 *       still references it via the old class.</li>
 * </ol>
 *
 * <p>Zero behavior delta: logic is character-for-character identical to
 * the pre-extraction methods.</p>
 */
public final class WorkshopVending {

    private WorkshopVending() {}

    // =========================================================================
    // Surplus detection
    // =========================================================================

    /**
     * Returns the per-item surplus over the keep floor, ready to pass to
     * {@link #executeSell}.  Empty map means nothing to sell.
     *
     * @param level            the server level
     * @param outputBuilding   the building whose storage is checked
     * @param sellableOutputs  items eligible for sale (from the behavior's hook)
     * @param quotas           per-item keep floors (may be empty)
     * @param defaultThreshold fallback floor when an item has no entry in
     *                         {@code quotas} (see
     *                         {@link AbstractProductionBehavior#DEFAULT_SURPLUS_THRESHOLD})
     */
    public static Map<Item, Integer> computeSurplus(
            ServerLevel level,
            Building outputBuilding,
            List<Item> sellableOutputs,
            Map<Item, Integer> quotas,
            int defaultThreshold) {

        if (sellableOutputs.isEmpty()) return Map.of();
        if (outputBuilding == null) return Map.of();

        Map<Item, Integer> result = new LinkedHashMap<>();
        for (Item item : sellableOutputs) {
            int stock = BuildingStorageAccess.countItem(level, outputBuilding, item);
            int keep = quotas.getOrDefault(item, defaultThreshold);
            if (stock > keep) result.put(item, stock - keep);
        }
        return result;
    }

    // =========================================================================
    // Sell-time predicate
    // =========================================================================

    /**
     * Returns {@code true} when the sell window is open and the throttle
     * interval has elapsed.
     *
     * @param gameTime          current game time from {@code level.getGameTime()}
     * @param lastDailySellTick the caller's per-instance tick of last sale
     *                          (pass {@code Long.MIN_VALUE} for "never sold")
     * @param sellWindowDayTick day-tick threshold after which selling is
     *                          permitted (from the behavior's hook)
     * @param minSellInterval   minimum ticks between successive sales
     */
    public static boolean isSellTime(
            long gameTime,
            long lastDailySellTick,
            int sellWindowDayTick,
            long minSellInterval) {

        long dayTime = gameTime % 24000;
        long todayBase = gameTime - dayTime;
        return dayTime >= sellWindowDayTick
                && lastDailySellTick < todayBase
                && (gameTime - lastDailySellTick) >= minSellInterval;
    }

    // =========================================================================
    // Market-trip execution
    // =========================================================================

    /**
     * Execute the sell trip: remove {@code toSell} items from
     * {@code outputBuilding}, push to market stalls via
     * {@link tterrag1112.life_in_the_village.Village.Markets.Complex.MarketInventory#store},
     * credit revenue to the first workshop container as coins, and fire
     * {@link WorkplaceAssignmentManager#onWorkplaceSale}.
     *
     * <p>Unsold remainder (no stall capacity) is returned to the workshop
     * rather than destroyed.  Revenue is only credited for actually-stored
     * goods.</p>
     *
     * <p>Formerly {@code AbstractProductionBehavior.executeSellForWorkshop}.
     * {@code AbstractProductionBehavior} keeps a public forwarding stub
     * under that name so no existing callers break.</p>
     *
     * @return total revenue in bronze units (0 if nothing sold)
     */
    public static long executeSell(
            ServerLevel level,
            TownspersonMob entity,
            Building outputBuilding,
            Building market,
            Map<Item, Integer> toSell) {

        if (toSell.isEmpty()) return 0L;
        if (outputBuilding == null || market == null) return 0L;

        VillageSavedData data = VillageSavedData.get(level);
        long totalRevenue = 0;

        for (Map.Entry<Item, Integer> entry : toSell.entrySet()) {
            Item item = entry.getKey();
            int qty = entry.getValue();
            if (!BuildingStorageAccess.takeItem(level, outputBuilding, item, qty)) continue;

            // Goods go to the seller's own stall first, else across the
            // market's other stall chests. The market BUILDING is never a
            // sink (market-stall-unification). Clean-fail: if no stall chest
            // can absorb, return goods to the workshop and skip this item —
            // no revenue is credited for goods that didn't actually land.
            ItemStack toMarket = new ItemStack(item, qty);
            tterrag1112.life_in_the_village.Village.Markets.Complex.MarketInventory
                    .store(level, market, toMarket);
            int stored = qty - toMarket.getCount();
            if (stored < qty) {
                // Return the unsold remainder to the workshop — never destroyed.
                BuildingStorageAccess.storeItem(level, outputBuilding,
                        new ItemStack(item, qty - stored));
            }
            if (stored <= 0) continue;

            final int soldQty = stored;
            totalRevenue += Math.max(1L,
                    Math.round(VillageEconomy.getBasePrice(item) * 0.8)) * soldQty;
            entity.getAssignedVillageName()
                    .flatMap(data::getVillageByName)
                    .ifPresent(v -> VillageEconomy.postListing(
                            level, v.getId(), entity, item, soldQty, level.getGameTime()));
        }

        if (totalRevenue > 0) {
            CurrencyValue earnings = CurrencyValue.of(totalRevenue);
            List<Container> containers = BuildingStorageAccess.findInventories(level, outputBuilding);
            if (!containers.isEmpty() && containers.get(0) instanceof SimpleContainer sc) {
                CoinHelper.giveCoins(sc, earnings);
            }
            WorkplaceAssignmentManager.onWorkplaceSale(
                    level, outputBuilding.getId(), (int) totalRevenue);
        }
        return totalRevenue;
    }
}
