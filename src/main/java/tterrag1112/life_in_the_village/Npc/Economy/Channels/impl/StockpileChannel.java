package tterrag1112.life_in_the_village.Npc.Economy.Channels.impl;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.ChannelQuote;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.ChannelType;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.EconomicChannel;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.TradeDirection;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.TradeIntent;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.TradeResult;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.VillagePolicy;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;
import tterrag1112.life_in_the_village.Village.Economy.Currency.MarketPriceHelper;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.Optional;

/**
 * Last-resort food source for households. Spec line 144.
 *
 * <p>Constraints:</p>
 * <ul>
 *   <li>BUY only — households take from the stockpile, never deposit.</li>
 *   <li>Food items only — ItemStack must carry {@link DataComponents#FOOD}.</li>
 *   <li>Available iff the village owns a {@link BuildingType#STOCKPILE}
 *   AND a {@link Profession#STOCKPILE_KEEPER} NPC is loaded near it.</li>
 * </ul>
 *
 * <p>Pricing: 0.7 × dynamic sell price ("near-cost", spec line 149) so
 * the stockpile beats market only when food is critically needed
 * (router IMMEDIATE bonus + low base price). Priority 30 is below
 * caravan, so a delivering caravan still wins for normal urgency.</p>
 */
public final class StockpileChannel implements EconomicChannel {

    /** Near-cost markdown vs. dynamic price. Spec line 149. */
    private static final double NEAR_COST_RATIO = 0.70;

    @Override public ChannelType type() { return ChannelType.STOCKPILE; }

    @Override public int basePriority() { return 30; }

    @Override
    public boolean isAvailable(Village village, VillageSavedData data, ServerLevel level, long tick) {
        return findStockpileWithKeeper(village, data, level).isPresent();
    }

    @Override
    public Optional<ChannelQuote> quote(TradeIntent intent, Village village,
                                        VillageSavedData data, ServerLevel level) {
        if (intent.direction() != TradeDirection.BUY) return Optional.empty();
        if (!isFood(intent.item())) return Optional.empty();

        Optional<StockpileMatch> matchOpt = findStockpileWithKeeper(village, data, level);
        if (matchOpt.isEmpty()) return Optional.empty();
        StockpileMatch match = matchOpt.get();

        int stock = BuildingStorageAccess.countItem(level, match.stockpile, intent.item());
        if (stock <= 0) return Optional.empty();

        long base = MarketPriceHelper.getDynamicSellPrice(level, village, intent.item());
        long policied = Math.round(base * NEAR_COST_RATIO
                * VillagePolicy.sellMultiplier(village, ChannelType.STOCKPILE, intent.item()));
        if (policied > intent.maxPrice()) return Optional.empty();

        int qty = Math.min(intent.quantity(), stock);
        long validUntil = level.getGameTime() + 12000L; // half a day
        return Optional.of(new ChannelQuote(ChannelType.STOCKPILE, intent,
                policied, qty, 0, validUntil, match.location));
    }

    @Override
    public TradeResult execute(ChannelQuote quote, TradeIntent intent, ServerLevel level) {
        if (intent.direction() != TradeDirection.BUY) return TradeResult.fail("STOCKPILE sell not allowed");
        VillageSavedData data = VillageSavedData.get(level);
        Village village = data.getVillageById(intent.villageId()).orElse(null);
        if (village == null) return TradeResult.fail("village missing");
        Optional<StockpileMatch> matchOpt = findStockpileWithKeeper(village, data, level);
        if (matchOpt.isEmpty()) return TradeResult.fail("stockpile no longer available");
        StockpileMatch match = matchOpt.get();

        int qty = Math.min(quote.availableQuantity(), intent.quantity());
        qty = Math.min(qty, BuildingStorageAccess.countItem(level, match.stockpile, intent.item()));
        if (qty <= 0) return TradeResult.fail("stockpile empty");
        long total = quote.pricePerUnit() * qty;

        if (!BuildingStorageAccess.takeItem(level, match.stockpile, intent.item(), qty)) {
            return TradeResult.fail("take failed");
        }

        TownspersonMob buyer = TownspersonMob.findByUUID(level, intent.actorId()).orElse(null);
        CurrencyValue cost = CurrencyValue.of(total);
        if (buyer != null) {
            if (!buyer.getWallet().canAfford(cost)) {
                BuildingStorageAccess.storeItem(level, match.stockpile,
                        new ItemStack(intent.item(), qty));
                return TradeResult.fail("buyer cannot pay");
            }
            buyer.getWallet().spend(cost);
        }
        // Coin flows to the stockpile keeper (operator), not the village
        // treasury — keepers are paid producers, not collectors.
        match.keeper.getWallet().receive(cost);
        return TradeResult.ok(qty, total);
    }

    // ── Internals ──────────────────────────────────────────────────────────

    private record StockpileMatch(Building stockpile, TownspersonMob keeper, BlockPos location) {}

    private static Optional<StockpileMatch> findStockpileWithKeeper(Village village,
                                                                    VillageSavedData data,
                                                                    ServerLevel level) {
        if (village == null || level == null) return Optional.empty();
        for (var bid : village.getBuildingIds()) {
            Building b = data.getBuildingById(bid).orElse(null);
            if (b == null || b.getType() != BuildingType.STOCKPILE) continue;
            TownspersonMob keeper = level.getEntitiesOfClass(
                    TownspersonMob.class,
                    b.getShape().toAABB().inflate(16),
                    mob -> mob.getProfession() == Profession.STOCKPILE_KEEPER
            ).stream().findFirst().orElse(null);
            if (keeper == null) continue;
            return Optional.of(new StockpileMatch(b, keeper, b.getShape().getOrigin()));
        }
        return Optional.empty();
    }

    private static boolean isFood(Item item) {
        return item.components().has(DataComponents.FOOD);
    }
}
