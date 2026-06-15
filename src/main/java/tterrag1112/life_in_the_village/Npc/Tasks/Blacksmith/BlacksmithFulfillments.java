package tterrag1112.life_in_the_village.Npc.Tasks.Blacksmith;

import tterrag1112.life_in_the_village.Npc.Tasks.FulfillmentRegistry;
import tterrag1112.life_in_the_village.Npc.Tasks.Objective;

/**
 * Registers the BLACKSMITH's genuinely-specific intermediate-acquisition
 * strategies (smelt own ore, buy ingots) into the shared
 * {@link FulfillmentRegistry}. Called once from {@code Fulfillments.install()}.
 *
 * <p>The generic craft fulfillment ({@code ProvideItem}) and the generic
 * surplus-sell fulfillment ({@code SellSurplus}) are NOT registered here &mdash;
 * those are spec-driven and registered for every producer via
 * {@code ProducerSpecs.registerFulfillments}. This helper now contains only
 * what is unique to the smith: the two ways it sources its own iron.</p>
 *
 * <ul>
 *   <li>{@code Acquire} / {@code MaintainStock} (intermediates) &rarr;
 *       {@link SmeltFulfillment} then {@link BuyIngotFulfillment} (the scorer
 *       smelts when ore+coal are on hand, buys when not).</li>
 * </ul>
 */
public final class BlacksmithFulfillments {

    private BlacksmithFulfillments() {}

    public static void register(FulfillmentRegistry registry) {
        SmeltFulfillment smelt = new SmeltFulfillment();
        BuyIngotFulfillment buy = new BuyIngotFulfillment();
        registry.register(Objective.Type.ACQUIRE, smelt);
        registry.register(Objective.Type.ACQUIRE, buy);
        registry.register(Objective.Type.MAINTAIN_STOCK, smelt);
        registry.register(Objective.Type.MAINTAIN_STOCK, buy);
    }
}
