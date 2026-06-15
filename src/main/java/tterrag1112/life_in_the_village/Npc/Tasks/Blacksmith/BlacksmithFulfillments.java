package tterrag1112.life_in_the_village.Npc.Tasks.Blacksmith;

import tterrag1112.life_in_the_village.Npc.Tasks.FulfillmentRegistry;
import tterrag1112.life_in_the_village.Npc.Tasks.Objective;

/**
 * T1 — registers the BLACKSMITH fulfillment strategies into the shared
 * {@link FulfillmentRegistry}. Called once from {@code Fulfillments.install()}.
 *
 * <ul>
 *   <li>{@code ProvideItem} → {@link CraftToolFulfillment} (the primary
 *       toolmaking act; lazily spawns an iron Acquire dependency when the
 *       building is short on iron).</li>
 *   <li>{@code Acquire} → {@link SmeltFulfillment} then
 *       {@link BuyIngotFulfillment} (the scorer picks: smelt when ore+coal
 *       are on hand, buy when not).</li>
 *   <li>{@code MaintainStock} → same two strategies (the LOW-priority iron
 *       reserve).</li>
 * </ul>
 *
 * <p>Registration order is registration-order-only for tie-breaks; the
 * dispatcher selects by {@code score}, so Smelt vs Buy is decided by their
 * availability-driven scores, not the order here.</p>
 */
public final class BlacksmithFulfillments {

    private BlacksmithFulfillments() {}

    public static void register(FulfillmentRegistry registry) {
        registry.register(Objective.Type.PROVIDE_ITEM, new CraftToolFulfillment());

        SmeltFulfillment smelt = new SmeltFulfillment();
        BuyIngotFulfillment buy = new BuyIngotFulfillment();
        registry.register(Objective.Type.ACQUIRE, smelt);
        registry.register(Objective.Type.ACQUIRE, buy);
        registry.register(Objective.Type.MAINTAIN_STOCK, smelt);
        registry.register(Objective.Type.MAINTAIN_STOCK, buy);
    }
}
