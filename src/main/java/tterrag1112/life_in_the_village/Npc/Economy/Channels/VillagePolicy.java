package tterrag1112.life_in_the_village.Npc.Economy.Channels;

import net.minecraft.world.item.Item;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.Optional;

/**
 * Phase 3 stub for the Phase-3-doc-22 village-laws system.
 *
 * <p>Channels read this at quote time to apply law-driven price
 * modifiers (spec section "Price modifiers", line 217). Today every
 * lookup returns "no policy active" — the doc 22 (next session)
 * implementation populates a real policy table behind the same call
 * site so this file becomes the resolver, not a stub.</p>
 *
 * <h3>Why a stub now</h3>
 * The brief explicitly orders 23 (channels) before 22 (laws); without
 * a stub, channel quote methods would have nowhere to read pricing
 * laws from and we'd have to retrofit the policy lookup later. Wiring
 * the call site now means doc 22 only has to populate the lookup, not
 * find every channel that needs it.
 */
public final class VillagePolicy {

    private VillagePolicy() {}

    /**
     * Multiplier applied to a sell price (channel → buyer). Spec line
     * 219 example: {@code MARKET_TAX_DOUBLE} → MarketChannel +10% to
     * sell price → returns 1.10. Phase 3 stub: 1.0.
     */
    public static double sellMultiplier(Village village, ChannelType channel, Item item) {
        return 1.0;
    }

    /**
     * Multiplier applied to a buy price (channel → seller pays).
     * Phase 3 stub: 1.0.
     */
    public static double buyMultiplier(Village village, ChannelType channel, Item item) {
        return 1.0;
    }

    /**
     * Optional hard cap on a quote's per-unit price. Used by
     * {@code PRICE_CEILING_FOOD} and similar laws. Phase 3 stub: empty.
     */
    public static Optional<Long> priceCeiling(Village village, ChannelType channel, Item item) {
        return Optional.empty();
    }

    /**
     * Subsidy bonus added to a seller's per-unit price. Used by
     * {@code SUBSIDIZE_FARMER} and similar laws. Phase 3 stub: 0.
     */
    public static long subsidyBonus(Village village, ChannelType channel, Item item) {
        return 0L;
    }
}
