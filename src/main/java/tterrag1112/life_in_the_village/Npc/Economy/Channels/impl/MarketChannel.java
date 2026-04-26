package tterrag1112.life_in_the_village.Npc.Economy.Channels.impl;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.ChannelQuote;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.ChannelType;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.EconomicChannel;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.TradeDirection;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.TradeIntent;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.TradeResult;
import tterrag1112.life_in_the_village.Npc.Laws.LawPriceHooks;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;
import tterrag1112.life_in_the_village.Village.Economy.Currency.MarketPriceHelper;
import tterrag1112.life_in_the_village.Village.Economy.Currency.NpcEconomy;
import tterrag1112.life_in_the_village.Village.Economy.Market.MarketStall;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.Optional;

/**
 * Wraps the existing market-stall + merchant infrastructure. Spec line
 * 108. Available iff the village owns a {@link BuildingType#MARKET}
 * building. Quote price uses {@link MarketPriceHelper#getDynamicSellPrice}
 * (BUY) or {@link MarketPriceHelper#getDynamicBuyPrice} (SELL); execute
 * delegates to the legacy {@link NpcEconomy#marketPurchase} routing so
 * stall payment + merchant fallback continue to work unchanged.
 *
 * <p>This channel intentionally does NOT short-circuit when the
 * village's market chest is empty — the {@link #quote} method clamps
 * {@code availableQuantity} to current stock, so the router will rank
 * this channel below an in-stock alternative without hard-failing it.</p>
 */
public final class MarketChannel implements EconomicChannel {

    @Override public ChannelType type() { return ChannelType.MARKET; }

    @Override public int basePriority() { return 100; }

    @Override
    public boolean isAvailable(Village village, VillageSavedData data, ServerLevel level, long tick) {
        return findMarket(village, data).isPresent();
    }

    @Override
    public Optional<ChannelQuote> quote(TradeIntent intent, Village village,
                                        VillageSavedData data, ServerLevel level) {
        Building market = findMarket(village, data).orElse(null);
        if (market == null) return Optional.empty();

        BlockPos location = market.getShape().getOrigin();
        long basePrice;
        int available;

        if (intent.direction() == TradeDirection.BUY) {
            basePrice = MarketPriceHelper.getDynamicSellPrice(level, village, intent.item());
            available = Math.min(intent.quantity(),
                    BuildingStorageAccess.countItem(level, market, intent.item()));
        } else {
            basePrice = MarketPriceHelper.getDynamicBuyPrice(level, village, intent.item());
            // Selling: market always accepts up to the requested quantity
            // (the merchant chest absorbs surplus). Spec line 217: laws
            // may cap this; not in v1.
            available = intent.quantity();
        }
        if (available <= 0) return Optional.empty();

        long policied = applyPolicy(intent, village, basePrice);
        if (intent.direction() == TradeDirection.BUY && policied > intent.maxPrice()) return Optional.empty();
        if (intent.direction() == TradeDirection.SELL && policied < intent.minPrice()) return Optional.empty();

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

        // Route payment via the legacy helper — keeps stall/merchant
        // routing identical to pre-Phase-3 behaviour.
        TownspersonMob buyer = TownspersonMob.findByUUID(level, intent.actorId()).orElse(null);
        MarketStall stall = findStallWithItem(market, intent, level, data);
        TownspersonMob merchant = findMerchant(level, market);
        CurrencyValue cost = CurrencyValue.of(total);

        if (buyer != null) {
            if (!NpcEconomy.marketPurchase(buyer, merchant, cost, level, data, stall)) {
                // Couldn't pay — return items.
                BuildingStorageAccess.storeItem(level, market,
                        new ItemStack(intent.item(), qty));
                return TradeResult.fail("buyer cannot pay");
            }
        } else {
            // Player buyer — payment is handled by the existing
            // TradeHandler invocation upstream (the channel was used as a
            // pricing/availability lookup; player flow keeps its own UI
            // path). Keep the items moved for the caller to receive.
        }

        // Village treasury slice — legacy 10%, scaled by doc 22's
        // MARKET_TAX_DOUBLE / MARKET_TAX_REDUCED multiplier.
        Village v = data.getVillageById(intent.villageId()).orElse(null);
        double mult = v == null ? 1.0
                : tterrag1112.life_in_the_village.Npc.Laws.LawTaxHooks.marketTaxMultiplier(v);
        long tax = Math.round((total / 10.0) * mult);
        if (tax > 0 && v != null) {
            v.depositToTreasury(tax);
            data.markDirty();
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
            seller.getWallet().receive(CurrencyValue.of(total));
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

    private static long applyPolicy(TradeIntent intent, Village village, long base) {
        // Doc 22 wired: tax multipliers + food price ceiling / floor +
        // direct subsidies via channel.
        double mult = intent.direction() == TradeDirection.BUY
                ? LawPriceHooks.sellMultiplier(village, ChannelType.MARKET, intent.item())
                : LawPriceHooks.buyMultiplier(village, ChannelType.MARKET, intent.item());
        long subsidy = intent.direction() == TradeDirection.SELL
                ? LawPriceHooks.subsidyBonus(village, ChannelType.MARKET, intent.item())
                : 0L;
        long policied = Math.round(base * mult) + subsidy;
        long floor = LawPriceHooks.priceFloor(village, ChannelType.MARKET, intent.item());
        if (floor > 0) policied = Math.max(floor, policied);
        return LawPriceHooks.priceCeiling(village, ChannelType.MARKET, intent.item())
                .map(cap -> Math.min(cap, policied))
                .orElse(policied);
    }

}
