package tterrag1112.life_in_the_village.Npc.Religion;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.ChannelQuote;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.ChannelRouter;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.EconomicChannel;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.TradeIntent;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.TradeResult;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.Urgency;
import tterrag1112.life_in_the_village.Npc.Religion.MonasticCrafts.MonasticCraft;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Economy.BuildingEconomy;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;
import tterrag1112.life_in_the_village.Village.Economy.Currency.MarketPriceHelper;
import tterrag1112.life_in_the_village.Village.Economy.EconomicBalance;
import tterrag1112.life_in_the_village.Village.Economy.Resources.ProductionHelpers;
import tterrag1112.life_in_the_village.Village.Economy.VillageEconomy;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Religion Rework R6d — the self-sustaining monastery economy. A daily per-village
 * pass (hooked from {@code RiteScheduler.dailyTick}, alongside
 * {@link MonasteryDeveloper}) that closes the monastery's production →
 * consumption → economy loop, on the monastery building's own
 * {@link BuildingEconomy} (the shared pool; the Abbot's authority over it is the
 * offices pass).
 *
 * <ul>
 *   <li><b>Upkeep:</b> a modest daily debit ({@link EconomicBalance#MONASTERY_DAILY_UPKEEP})
 *       — a productive house stays solvent, an idle one trends negative (tracked,
 *       NOT decayed: monasteries are not rite venues, so they are outside
 *       {@code TempleProsperity}).</li>
 *   <li><b>Surplus → sell:</b> monastic goods above their target are sold to
 *       market (the revenue formula + listing of the workshop sell path), crediting
 *       the shared pool.</li>
 *   <li><b>Buy inputs:</b> the inputs of producer-backed, needed crafts (wheat for
 *       bread, honeycomb for candles, paper for books, …) are bought from the pool
 *       via the existing {@link ChannelRouter} procurement, so production keeps
 *       running.</li>
 *   <li><b>Food safety net:</b> if the store dips below a survival floor, bread is
 *       bought directly so the monks never starve (bakers keep it above the floor).</li>
 * </ul>
 */
public final class MonasteryEconomy {

    private MonasteryEconomy() {}

    private static final int  SELL_CAP_PER_GOOD = 8;   // surplus units sold per good/pass
    private static final int  FOOD_FLOOR        = 16;  // bread below this → buy bread (survival)
    private static final int  BREAD_BUY_CAP     = 8;
    private static final int  INPUT_BUFFER      = 8;   // keep ~this of each producible input
    private static final int  INPUT_BUY_CAP     = 8;

    /** Daily per-village pass. */
    public static void tickVillage(ServerLevel level, Village village,
                                   VillageSavedData data, long now) {
        if (level == null || village == null) return;
        for (UUID bid : village.getBuildingIds()) {
            Building b = data.getBuildingById(bid).orElse(null);
            if (b == null) continue;
            if (b.getType() != BuildingType.MONASTERY && b.getType() != BuildingType.ABBEY) continue;
            tickMonastery(level, village, data, b, now);
        }
    }

    private static void tickMonastery(ServerLevel level, Village village,
                                      VillageSavedData data, Building monastery, long now) {
        List<TownspersonMob> monks = MonasteryDeveloper.monksOf(level, village, data, monastery);
        if (monks.isEmpty()) return;                     // no community → no economy
        TownspersonMob agent = monks.get(0);             // the economic conduit (seller/buyer)
        BuildingEconomy econ = data.getOrCreateBuildingEconomy(monastery.getId());

        // Upkeep + a (non-decaying) solvency signal.
        econ.withdraw(EconomicBalance.MONASTERY_DAILY_UPKEEP);
        if (econ.getTreasury() >= EconomicBalance.MONASTERY_DAILY_UPKEEP) econ.resetDaysInsolvent();
        else econ.incrementDaysInsolvent();

        sellSurplus(level, village, data, monastery, econ, agent, now);
        buyInputs(level, village, data, monastery, econ, agent, monks);
        data.setDirty();
    }

    // ── Surplus → sell → the shared pool ─────────────────────────────────────
    private static void sellSurplus(ServerLevel level, Village village, VillageSavedData data,
                                    Building monastery, BuildingEconomy econ,
                                    TownspersonMob agent, long now) {
        Building market = ProductionHelpers.findMarketInVillage(agent, level).orElse(null);
        if (market == null) return;                      // nowhere to sell → keep the goods
        for (MonasticCraft c : MonasticCrafts.CRAFTS) {
            Item good = c.recipe().output();
            int stock = BuildingStorageAccess.countItem(level, monastery, good);
            int surplus = Math.min(stock - c.quota(), SELL_CAP_PER_GOOD);
            if (surplus <= 0) continue;
            if (!BuildingStorageAccess.takeItem(level, monastery, good, surplus)) continue;
            // Surplus sells into the market's stall chests (the only merchant
            // inventory), not the market BUILDING. Clean-fail: anything the
            // stalls can't absorb returns to the monastery — never destroyed —
            // and only the sold portion earns revenue.
            ItemStack toMarket = new ItemStack(good, surplus);
            tterrag1112.life_in_the_village.Village.Markets.Complex.MarketInventory.store(level, market, toMarket);
            int sold = surplus - toMarket.getCount();
            if (toMarket.getCount() > 0) {
                BuildingStorageAccess.storeItem(level, monastery, toMarket);
            }
            if (sold <= 0) continue;
            surplus = sold;
            long revenue = Math.max(1L, Math.round(VillageEconomy.getBasePrice(good) * 0.8)) * surplus;
            econ.depositRevenue(revenue);                // → the shared pool (not chest coins)
            VillageEconomy.postListing(level, village.getId(), agent, good, surplus, now);
        }
    }

    // ── Buy inputs from the pool (producer-backed crafts) + food safety net ──
    private static void buyInputs(ServerLevel level, Village village, VillageSavedData data,
                                  Building monastery, BuildingEconomy econ,
                                  TownspersonMob agent, List<TownspersonMob> monks) {
        for (MonasticCraft c : MonasticCrafts.CRAFTS) {
            if (!MonasticCrafts.isSupported(level, monastery, c)) continue;
            if (MonasticCrafts.need(level, monastery, c) <= 0) continue;   // already stocked

            boolean isFood = c.recipe().output() == Items.BREAD;
            boolean hasProducer = monks.stream().anyMatch(m -> MonasticCrafts.isProducer(m, c));

            if (isFood && !hasProducer) {
                // No baker — buy bread directly so the monks don't starve.
                int breadStock = BuildingStorageAccess.countItem(level, monastery, Items.BREAD);
                if (breadStock < FOOD_FLOOR) {
                    buyInput(level, village, data, monastery, econ, agent, Items.BREAD,
                            Math.min(BREAD_BUY_CAP, FOOD_FLOOR - breadStock));
                }
                continue;
            }
            if (!hasProducer) continue;                  // no maker → don't stockpile its inputs

            // Buy the recipe's inputs up to a small working buffer so production runs.
            for (Map.Entry<Item, Integer> in : c.recipe().inputs().entrySet()) {
                int stock = BuildingStorageAccess.countItem(level, monastery, in.getKey());
                if (stock >= INPUT_BUFFER) continue;
                buyInput(level, village, data, monastery, econ, agent, in.getKey(),
                        Math.min(INPUT_BUY_CAP, INPUT_BUFFER - stock));
            }
        }
    }

    /**
     * Bounded procurement of {@code want} of {@code item} from the best channel,
     * paid from the monastery pool and deposited to the store. Mirrors
     * {@code AbstractProductionBehavior.executeBuy}'s core (the agent's wallet is
     * a transient conduit, fully restored on failure/leftover).
     */
    private static void buyInput(ServerLevel level, Village village, VillageSavedData data,
                                 Building monastery, BuildingEconomy econ,
                                 TownspersonMob agent, Item item, int want) {
        if (want <= 0 || econ.getTreasury() <= 0) return;
        long ceiling = Math.max(1L, MarketPriceHelper.getDynamicSellPrice(level, village, item));
        TradeIntent intent = TradeIntent.buy(item, want, agent.getUUID(), monastery.getId(),
                village.getId(), ceiling, Urgency.NORMAL, Set.of());
        ChannelQuote quote = ChannelRouter.findBestChannel(intent, village, data, level).orElse(null);
        if (quote == null) return;

        long price = quote.pricePerUnit();
        int qty = (int) Math.min(quote.availableQuantity(),
                Math.max(0L, (long) ((econ.getTreasury() * 0.9) / Math.max(1L, price))));
        qty = Math.min(qty, want);
        if (qty <= 0) return;

        ChannelQuote partial = new ChannelQuote(quote.channel(), quote.intent(), price, qty,
                quote.travelTimeTicks(), quote.quoteValidUntilTick(), quote.location());
        long total = price * qty;
        long fromPool = Math.min(total, econ.getTreasury());
        if (fromPool <= 0) return;
        econ.withdraw(fromPool);
        agent.getWallet().receive(CurrencyValue.of(fromPool));

        EconomicChannel channel = ChannelRouter.registeredChannels().stream()
                .filter(c -> c.type() == partial.channel()).findFirst().orElse(null);
        TradeResult result = channel == null
                ? TradeResult.fail("channel missing")
                : channel.execute(partial, intent, level);

        if (!result.success()) {                          // refund the pool
            agent.getWallet().spend(CurrencyValue.of(fromPool));
            econ.depositRevenue(fromPool);
            return;
        }
        BuildingStorageAccess.storeItem(level, monastery,
                new ItemStack(item, result.quantityTraded()));
        long leftover = total - result.totalBronze();     // partial fill → refund the pool
        if (leftover > 0) {
            agent.getWallet().spend(CurrencyValue.of(leftover));
            econ.depositRevenue(leftover);
        }
    }
}
