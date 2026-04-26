package tterrag1112.life_in_the_village.Npc.Laws;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Item;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.ChannelType;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.Optional;

/**
 * Channel-side facade over the law system. Replaces the old
 * {@code Npc.Economy.Channels.VillagePolicy} stub: every quote method
 * that needs to apply a law-driven modifier calls into here.
 *
 * <p>Method signatures match the previous stub's so the channel impls
 * only swap their import; the body remains a one-liner per modifier.</p>
 */
public final class LawPriceHooks {

    private LawPriceHooks() {}

    /** Combined sell-side multiplier (channel → buyer). */
    public static double sellMultiplier(Village village, ChannelType channel, Item item) {
        VillagePolicy policy = policyOf(village);
        if (policy == null) return 1.0;
        double mult = 1.0;
        for (VillageLaw law : policy.activeLaws()) {
            if (policy.isSuspended(law)) continue;
            if (!appliesToChannel(law, channel)) continue;
            LawEffect e = LawEffectRegistry.get(law);
            if (e == null) continue;
            // Sell-side: market tax raises the buyer's price.
            if (channel == ChannelType.MARKET) {
                mult *= e.marketTaxMultiplier(village, paramsOf(policy, law));
            }
        }
        return mult;
    }

    /** Combined buy-side multiplier (channel → seller pays). */
    public static double buyMultiplier(Village village, ChannelType channel, Item item) {
        VillagePolicy policy = policyOf(village);
        if (policy == null) return 1.0;
        double mult = 1.0;
        for (VillageLaw law : policy.activeLaws()) {
            if (policy.isSuspended(law)) continue;
            if (!appliesToChannel(law, channel)) continue;
            LawEffect e = LawEffectRegistry.get(law);
            if (e == null) continue;
            if (channel == ChannelType.MARKET) {
                // Same multiplier in v1; spec doesn't separate the two.
                mult *= e.marketTaxMultiplier(village, paramsOf(policy, law));
            }
        }
        return mult;
    }

    /** Hard ceiling on per-unit price (PRICE_CEILING_FOOD). Empty when no cap applies. */
    public static Optional<Long> priceCeiling(Village village, ChannelType channel, Item item) {
        if (!isFood(item)) return Optional.empty();
        VillagePolicy policy = policyOf(village);
        if (policy == null) return Optional.empty();
        long lowest = Long.MAX_VALUE;
        boolean any = false;
        for (VillageLaw law : policy.activeLaws()) {
            if (policy.isSuspended(law)) continue;
            LawEffect e = LawEffectRegistry.get(law);
            if (e == null) continue;
            long cap = e.foodPriceCeiling(village, paramsOf(policy, law));
            if (cap >= 0 && cap < lowest) { lowest = cap; any = true; }
        }
        return any ? Optional.of(lowest) : Optional.empty();
    }

    /** Per-unit floor (PRICE_FLOOR_FOOD). Returns 0 when no floor applies. */
    public static long priceFloor(Village village, ChannelType channel, Item item) {
        if (!isFood(item)) return 0L;
        VillagePolicy policy = policyOf(village);
        if (policy == null) return 0L;
        long highest = 0L;
        for (VillageLaw law : policy.activeLaws()) {
            if (policy.isSuspended(law)) continue;
            LawEffect e = LawEffectRegistry.get(law);
            if (e == null) continue;
            long floor = e.foodPriceFloor(village, paramsOf(policy, law));
            if (floor > highest) highest = floor;
        }
        return highest;
    }

    /**
     * Subsidy bonus added to a seller's per-unit price. v1 only applies
     * when the seller is a profession-matched producer using
     * DIRECT_BUSINESS — those bronze flow direct buyer→seller so the
     * subsidy is the simplest place to land. Other channel types
     * return 0 here; their subsidies are paid daily out of the
     * treasury via {@link LawTaxHooks#dailySubsidyForProfession}.
     */
    public static long subsidyBonus(Village village, ChannelType channel, Item item) {
        return 0L;
    }

    /**
     * True when CARAVAN trades from outside should be blocked
     * (FOREIGN_TRADER_BAN). Hooked from the caravan channel's
     * isAvailable path.
     */
    public static boolean caravanBlocked(Village village) {
        VillagePolicy policy = policyOf(village);
        if (policy == null) return false;
        for (VillageLaw law : policy.activeLaws()) {
            if (policy.isSuspended(law)) continue;
            LawEffect e = LawEffectRegistry.get(law);
            if (e != null && e.blocksForeignCaravan(village, paramsOf(policy, law))) return true;
        }
        return false;
    }

    // ── Internals ──────────────────────────────────────────────────────────

    private static VillagePolicy policyOf(Village village) {
        return village == null ? null : village.getPolicy();
    }

    private static LawParams paramsOf(VillagePolicy policy, VillageLaw law) {
        return policy.getParams(law).orElse(LawParams.empty());
    }

    private static boolean isFood(Item item) {
        return item != null && item.components().has(DataComponents.FOOD);
    }

    /**
     * Cheap channel-applicability filter so a law that doesn't touch
     * a channel can be skipped during multiplier folding. v1 only
     * filters the obviously-irrelevant pairs; sell/buy multipliers fold
     * in market-only laws inline above.
     */
    private static boolean appliesToChannel(VillageLaw law, ChannelType channel) {
        return switch (law) {
            case MARKET_TAX_DOUBLE, MARKET_TAX_REDUCED -> channel == ChannelType.MARKET;
            default -> true;
        };
    }
}
