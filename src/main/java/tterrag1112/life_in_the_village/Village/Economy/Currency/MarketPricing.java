package tterrag1112.life_in_the_village.Village.Economy.Currency;

import net.minecraft.world.item.Item;
import tterrag1112.life_in_the_village.Kingdom.KingdomLawEffects;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.ChannelType;
import tterrag1112.life_in_the_village.Npc.Laws.LawPriceHooks;
import tterrag1112.life_in_the_village.Village.Reputation.ReputationManager;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.UUID;

/**
 * The single canonical market-pricing function (economy Phase 1a).
 *
 * <p>Both the player trade path ({@code TradeHandler}) and the NPC channel
 * path ({@code MarketChannel}) call this so prices are computed through one
 * documented modifier pipeline instead of two disjoint inline stacks. This
 * layer composes the base + supply/demand core ({@link MarketPriceHelper} /
 * {@link DynamicPriceCalculator}) with the law, stall and relationship
 * modifiers; it does <em>not</em> re-derive base pricing.</p>
 *
 * <h3>Pipeline (ordered)</h3>
 * <ol>
 *   <li><b>Base price</b> — {@link MarketPriceHelper} culture table.</li>
 *   <li><b>Supply/demand</b> — {@link DynamicPriceCalculator} multiplier
 *       (sell), or multiplier × 0.6 (buy margin).</li>
 *   <li><b>Stall custom price</b> — {@link MarketStall#getEffectivePrice}
 *       when a stall sources the item. Buyer-pays side only; the owner's
 *       set price wins over the auto-computed market value.</li>
 *   <li><b>Village law</b> — {@link LawPriceHooks} tax multiplier, food
 *       price floor/ceiling, and (sell-to-market) subsidy. Applies to all
 *       sales regardless of buyer.</li>
 *   <li><b>Reputation discount</b> — {@link ReputationManager}; PLAYER buy
 *       side only.</li>
 *   <li><b>Profession perk</b> — {@link tterrag1112.life_in_the_village.Profession.ProfessionPerkManager};
 *       PLAYER sell side only.</li>
 *   <li><b>Kingdom tariff</b> — {@link KingdomLawEffects}; PLAYER only,
 *       cross-kingdom.</li>
 * </ol>
 *
 * <p>Two public entry points mirror the existing vocabulary:
 * {@link #sellPrice} is what a buyer pays to acquire an item (player buying
 * / NPC buying); {@link #buyPrice} is what a seller receives (player selling
 * / NPC selling). Intent min/max clamping stays a channel-routing concern in
 * {@code MarketChannel}; it is not a price modifier.</p>
 */
public final class MarketPricing {

    private MarketPricing() {}

    // =========================================================================
    // Public entry points
    // =========================================================================

    /**
     * Price a buyer pays to acquire {@code ctx.item()} — the player-buying /
     * NPC-buying side. Runs the full ordered pipeline. Never below 1 bronze.
     */
    public static long sellPrice(PricingContext ctx) {
        Item item = ctx.item();
        UUID villageId = ctx.villageId();

        // 1+2. Base price scaled by village supply/demand.
        long price = MarketPriceHelper.getDynamicSellPrice(
                ctx.level(), ctx.village(), item);

        // 3. Stall custom price (buyer-pays side only) — owner's price wins.
        if (ctx.stall() != null) {
            price = ctx.stall().getEffectivePrice(item, price);
        }

        // 4. Village law — tax multiplier + food floor/ceiling, all buyers.
        price = applyLawSell(ctx.village(), item, price);

        // 5+7. Relationship modifiers — player only.
        if (ctx.buyerType() == BuyerType.PLAYER
                && ctx.player() != null && villageId != null) {
            // Reputation discount on the buyer's price.
            price = ReputationManager.applyDiscount(
                    price, ctx.player(), villageId, ctx.level());
            // Kingdom TRADE_TARIFFS — outsiders pay a surcharge.
            double tariff = KingdomLawEffects.tradeBuyMultiplier(
                    ctx.player(), ctx.data(), villageId);
            price = Math.round(price * tariff);
        }

        return Math.max(1, price);
    }

    /**
     * Price a seller receives for {@code ctx.item()} — the player-selling /
     * NPC-selling side. Runs the full ordered pipeline. Never below 1 bronze.
     *
     * <p>Stall custom prices do not apply here: a stall is a sell endpoint
     * (the buyer pays the stall's price), not a buyer.</p>
     */
    public static long buyPrice(PricingContext ctx) {
        Item item = ctx.item();
        UUID villageId = ctx.villageId();

        // 1+2. Base buy price (includes the ×0.6 merchant margin) scaled by
        // village supply/demand.
        long price = MarketPriceHelper.getDynamicBuyPrice(
                ctx.level(), ctx.village(), item);

        // 4. Village law — tax multiplier + subsidy + food floor/ceiling.
        price = applyLawBuy(ctx.village(), item, price);

        // 6+7. Relationship modifiers — player only.
        if (ctx.buyerType() == BuyerType.PLAYER && ctx.player() != null) {
            // MERCHANT_BETTER_PRICES perk raises what the merchant pays.
            price = tterrag1112.life_in_the_village.Profession.ProfessionPerkManager
                    .applyMerchantBuyPricePerk(ctx.player(), price);
            // Kingdom TRADE_TARIFFS — outsiders receive less.
            if (villageId != null) {
                double tariff = KingdomLawEffects.tradeSellMultiplier(
                        ctx.player(), ctx.data(), villageId);
                price = Math.round(price * tariff);
            }
        }

        return Math.max(1, price);
    }

    // =========================================================================
    // Village-law policy (shared with the former MarketChannel.applyPolicy)
    // =========================================================================

    /** Buyer-pays side: market tax multiplier + food price floor/ceiling. */
    private static long applyLawSell(Village village, Item item, long base) {
        if (village == null) return base;
        double mult = LawPriceHooks.sellMultiplier(village, ChannelType.MARKET, item);
        long policied = Math.round(base * mult);
        return clampToLawBand(village, item, policied);
    }

    /** Seller-receives side: market tax multiplier + subsidy + floor/ceiling. */
    private static long applyLawBuy(Village village, Item item, long base) {
        if (village == null) return base;
        double mult = LawPriceHooks.buyMultiplier(village, ChannelType.MARKET, item);
        long subsidy = LawPriceHooks.subsidyBonus(village, ChannelType.MARKET, item);
        long policied = Math.round(base * mult) + subsidy;
        return clampToLawBand(village, item, policied);
    }

    /** Applies the food price floor then ceiling, mirroring the legacy order. */
    private static long clampToLawBand(Village village, Item item, long policied) {
        long floor = LawPriceHooks.priceFloor(village, ChannelType.MARKET, item);
        if (floor > 0) policied = Math.max(floor, policied);
        long finalPolicied = policied;
        return LawPriceHooks.priceCeiling(village, ChannelType.MARKET, item)
                .map(cap -> Math.min(cap, finalPolicied))
                .orElse(policied);
    }
}
