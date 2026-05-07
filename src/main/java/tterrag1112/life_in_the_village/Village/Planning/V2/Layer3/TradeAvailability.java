package tterrag1112.life_in_the_village.Village.Planning.V2.Layer3;

import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.ViabilityTier;

/**
 * V2 reconciliation gate: which {@link Category} demands can be
 * satisfied by trade at a given {@link ViabilityTier}? Tradeable
 * {@link Requires} entries fall back to trade only when the tier
 * supports it; otherwise the requiring building drops if no local
 * provider exists.
 *
 * <p>Tier rationale:
 * <ul>
 *   <li>{@code OUTPOST} — no trade. Self-sufficient or drops.</li>
 *   <li>{@code HAMLET} — one trade-route hop; raw materials common,
 *       perishables not.</li>
 *   <li>{@code TOWN} / {@code CITY} — free trade.</li>
 *   <li>{@code UNVIABLE} — never reaches reconciliation.</li>
 * </ul>
 *
 * <p>Defaults are tunable by visual feedback.
 */
public final class TradeAvailability {

    private TradeAvailability() {}

    public static boolean canTradeSatisfy(ViabilityTier tier, Category category) {
        return switch (tier) {
            case OUTPOST  -> false;
            case HAMLET   -> tradeableForHamlet(category);
            case TOWN     -> true;
            case CITY     -> true;
            case UNVIABLE -> false;
        };
    }

    private static boolean tradeableForHamlet(Category category) {
        return switch (category) {
            case METAL_ORE, WOOD, FUEL, FLOUR -> true;
            case LIVESTOCK, FOOD              -> false;
            default                           -> false;
        };
    }
}
