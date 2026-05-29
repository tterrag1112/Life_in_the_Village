package tterrag1112.life_in_the_village.Npc.Economy.Channels.impl;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.ChannelQuote;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.ChannelType;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.EconomicChannel;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.TradeDirection;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.TradeIntent;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.TradeResult;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;
import tterrag1112.life_in_the_village.Village.Economy.Currency.MarketPricing;
import tterrag1112.life_in_the_village.Village.Economy.Currency.NpcEconomy;
import tterrag1112.life_in_the_village.Village.Economy.Currency.SettlementParty;
import tterrag1112.life_in_the_village.Village.Economy.Market.MarketStall;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.Optional;

/**
 * Wraps the existing market-stall + merchant infrastructure. Spec line
 * 108. Available iff the village owns a {@link BuildingType#MARKET}
 * building. Quote price runs the canonical {@link MarketPricing} pipeline
 * ({@link MarketPricing#sellPrice} for BUY, {@link MarketPricing#buyPrice}
 * for SELL) — the same function the player trade path uses — so supply/
 * demand, village law and stall custom prices are applied identically on
 * both paths. Execute moves goods and then settles money through the
 * unified {@link NpcEconomy#settlePurchase} / {@link NpcEconomy#settleSale}
 * helpers — the same money path the player trade path uses — so stall
 * payment, merchant fallback and the village market tax are applied in one
 * place.
 *
 * <p>This channel intentionally does NOT short-circuit when the
 * village's market chest is empty — the {@link #quote} method clamps
 * {@code availableQuantity} to current stock, so the router will rank
 * this channel below an in-stock alternative without hard-failing it.</p>
 */
public final class MarketChannel implements EconomicChannel {

    private static final Logger LOGGER = LogUtils.getLogger();
    // Phase 6.3.4.3.1 — one-shot diagnostic flags per JVM session.
    private static volatile boolean LOGGED_NO_MARKET     = false;
    private static volatile boolean LOGGED_NO_STOCK      = false;
    private static volatile boolean LOGGED_CEILING_BUY   = false;
    private static volatile boolean LOGGED_FLOOR_SELL    = false;
    private static volatile boolean LOGGED_QUOTE_OK      = false;

    @Override public ChannelType type() { return ChannelType.MARKET; }

    @Override public int basePriority() { return 100; }

    @Override
    public boolean isAvailable(Village village, VillageSavedData data, ServerLevel level, long tick) {
        boolean has = findMarket(village, data).isPresent();
        if (!has && !LOGGED_NO_MARKET) {
            LOGGER.warn("[MarketChannel] isAvailable=false — no MARKET building " +
                    "in village={}; channel sits out, router falls through to " +
                    "DirectBusinessChannel / other channels.",
                    village == null ? "(null)" : village.getName());
            LOGGED_NO_MARKET = true;
        }
        return has;
    }

    @Override
    public Optional<ChannelQuote> quote(TradeIntent intent, Village village,
                                        VillageSavedData data, ServerLevel level) {
        Building market = findMarket(village, data).orElse(null);
        if (market == null) return Optional.empty();

        BlockPos location = market.getShape().getOrigin();
        long policied;
        int available;

        if (intent.direction() == TradeDirection.BUY) {
            // Buyer pays the sell-side price; honour the sourcing stall's
            // custom price via the unified pipeline.
            MarketStall stall = findStallWithItem(market, intent, level, data);
            policied = MarketPricing.sellPrice(
                    tterrag1112.life_in_the_village.Village.Economy.Currency.PricingContext
                            .forNpc(intent.item(), level, village, data, stall));
            available = Math.min(intent.quantity(),
                    BuildingStorageAccess.countItem(level, market, intent.item()));
        } else {
            // Seller receives the buy-side price. Stalls are sell endpoints,
            // so no stall override on this side.
            policied = MarketPricing.buyPrice(
                    tterrag1112.life_in_the_village.Village.Economy.Currency.PricingContext
                            .forNpc(intent.item(), level, village, data, null));
            // Selling: market always accepts up to the requested quantity
            // (the merchant chest absorbs surplus). Spec line 217: laws
            // may cap this; not in v1.
            available = intent.quantity();
        }
        if (available <= 0) {
            if (!LOGGED_NO_STOCK) {
                LOGGER.warn("[MarketChannel] NO STOCK item={} at MARKET in " +
                        "village={}; channel declines, router falls through.",
                        intent.item(), village.getName());
                LOGGED_NO_STOCK = true;
            }
            return Optional.empty();
        }

        if (intent.direction() == TradeDirection.BUY && policied > intent.maxPrice()) {
            if (!LOGGED_CEILING_BUY) {
                LOGGER.warn("[MarketChannel] CEILING REJECT item={} quote={}br/unit " +
                        "EXCEEDS intent.maxPrice={}br/unit",
                        intent.item(), policied, intent.maxPrice());
                LOGGED_CEILING_BUY = true;
            }
            return Optional.empty();
        }
        if (intent.direction() == TradeDirection.SELL && policied < intent.minPrice()) {
            if (!LOGGED_FLOOR_SELL) {
                LOGGER.warn("[MarketChannel] FLOOR REJECT item={} quote={}br/unit " +
                        "BELOW intent.minPrice={}br/unit", intent.item(), policied,
                        intent.minPrice());
                LOGGED_FLOOR_SELL = true;
            }
            return Optional.empty();
        }
        if (!LOGGED_QUOTE_OK) {
            LOGGER.warn("[MarketChannel] QUOTE OK item={} dir={} {}br/unit qty={}",
                    intent.item(), intent.direction(), policied, available);
            LOGGED_QUOTE_OK = true;
        }

        // Quote valid for one in-game day; the daily tick refreshes.
        long validUntil = level.getGameTime() + 24000L;
        return Optional.of(new ChannelQuote(ChannelType.MARKET, intent,
                policied, available, 0, validUntil, location));
    }

    @Override
    public TradeResult execute(ChannelQuote quote, TradeIntent intent, ServerLevel level) {
        VillageSavedData data = VillageSavedData.get(level);
        Village village = data.getVillageById(intent.villageId()).orElse(null);
        if (village == null) return TradeResult.fail("village missing");
        Building market = findMarket(village, data).orElse(null);
        if (market == null) return TradeResult.fail("market missing");

        int qty = Math.min(quote.availableQuantity(), intent.quantity());
        if (qty <= 0) return TradeResult.fail("nothing to trade");

        if (intent.direction() == TradeDirection.BUY) {
            return executeBuy(intent, market, qty, quote.pricePerUnit(), level, data);
        }
        return executeSell(intent, market, village, qty, quote.pricePerUnit(), level, data);
    }

    // ── Internals ──────────────────────────────────────────────────────────

    private static TradeResult executeBuy(TradeIntent intent, Building market, int qty,
                                          long pricePerUnit, ServerLevel level,
                                          VillageSavedData data) {
        // Reread stock — quote may have been issued ticks ago.
        int actuallyAvailable = BuildingStorageAccess.countItem(level, market, intent.item());
        qty = Math.min(qty, actuallyAvailable);
        if (qty <= 0) return TradeResult.fail("market sold out");
        long total = pricePerUnit * qty;

        // Move items first; if take fails the trade aborts cleanly.
        if (!BuildingStorageAccess.takeItem(level, market, intent.item(), qty)) {
            return TradeResult.fail("take failed");
        }

        // Settle money via the unified helper (debit buyer, credit stall
        // owner / merchant, collect the village market tax once). Goods
        // already moved above; the helper is money-only.
        TownspersonMob buyer = TownspersonMob.findByUUID(level, intent.actorId()).orElse(null);
        MarketStall stall = findStallWithItem(market, intent, level, data);
        TownspersonMob merchant = findMerchant(level, market);
        CurrencyValue cost = CurrencyValue.of(total);
        Village v = data.getVillageById(intent.villageId()).orElse(null);

        // buyer == null is an unloaded-NPC edge case (never a real player —
        // every channel caller is an NPC). Mirror the legacy behaviour:
        // no debit/credit, but the market tax still applies once.
        SettlementParty buyerParty = buyer != null
                ? SettlementParty.npc(buyer) : SettlementParty.none();
        SettlementParty endpoint = buyer != null
                ? NpcEconomy.resolveStallEndpoint(level, stall, merchant)
                : SettlementParty.none();

        if (!NpcEconomy.settlePurchase(buyerParty, endpoint, cost, v, level, data)) {
            // Couldn't pay — return items.
            BuildingStorageAccess.storeItem(level, market,
                    new ItemStack(intent.item(), qty));
            return TradeResult.fail("buyer cannot pay");
        }
        return TradeResult.ok(qty, total);
    }

    private static TradeResult executeSell(TradeIntent intent, Building market, Village village,
                                           int qty, long pricePerUnit, ServerLevel level,
                                           VillageSavedData data) {
        long total = pricePerUnit * qty;
        BuildingStorageAccess.storeItem(level, market, new ItemStack(intent.item(), qty));
        TownspersonMob seller = TownspersonMob.findByUUID(level, intent.actorId()).orElse(null);
        if (seller != null) {
            // Money-only settle: the market pays the seller. No payer entity
            // (the market mints the payment, as before) and no sell-side tax.
            NpcEconomy.settleSale(SettlementParty.none(),
                    SettlementParty.npc(seller), CurrencyValue.of(total), level);
        }
        // Listing entry so other NPCs see the new market stock for
        // pricing — matches the legacy SellToMarketGoal path.
        if (seller != null) {
            tterrag1112.life_in_the_village.Village.Economy.VillageEconomy.postListing(
                    level, village.getId(), seller, intent.item(), qty, level.getGameTime());
        }
        return TradeResult.ok(qty, total);
    }

    private static Optional<Building> findMarket(Village village, VillageSavedData data) {
        if (village == null) return Optional.empty();
        return village.getBuildingIds().stream()
                .map(data::getBuildingById)
                .filter(Optional::isPresent).map(Optional::get)
                .filter(b -> b.getType() == BuildingType.MARKET)
                .findFirst();
    }

    private static MarketStall findStallWithItem(Building market, TradeIntent intent,
                                                 ServerLevel level, VillageSavedData data) {
        return data.getStallsForMarket(market.getId()).stream()
                .filter(s -> s.isActive() && !s.getChestPos().equals(BlockPos.ZERO))
                .filter(s -> {
                    var be = level.getBlockEntity(s.getChestPos());
                    if (!(be instanceof net.minecraft.world.Container chest)) return false;
                    for (int i = 0; i < chest.getContainerSize(); i++) {
                        if (chest.getItem(i).is(intent.item())) return true;
                    }
                    return false;
                })
                .findFirst().orElse(null);
    }

    private static TownspersonMob findMerchant(ServerLevel level, Building market) {
        return level.getEntitiesOfClass(TownspersonMob.class,
                market.getShape().toAABB().inflate(16),
                mob -> mob.getProfession() == Profession.MERCHANT
        ).stream().findFirst().orElse(null);
    }

}
