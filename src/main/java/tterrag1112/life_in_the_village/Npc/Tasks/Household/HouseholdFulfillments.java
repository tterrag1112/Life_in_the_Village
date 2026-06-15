package tterrag1112.life_in_the_village.Npc.Tasks.Household;

import tterrag1112.life_in_the_village.Npc.Tasks.FulfillmentRegistry;
import tterrag1112.life_in_the_village.Npc.Tasks.Objective;

/**
 * T2 — registers the household food task's two strategies (the bake-vs-buy
 * choice) into the shared registry. Called once from
 * {@code Fulfillments.install()} at mod init, alongside the producer + smith
 * registrations.
 *
 * <p>Both strategies key on {@code MAINTAIN_STOCK} (the household food
 * objective). They self-filter to the household BREAD task in their
 * {@code canFulfill}, so they never act on a producer's {@code MaintainStock}
 * intermediate reserve (different item, different scope/board) and a producer's
 * {@code BuyFulfillment} never acts on the household task (it requires the NPC's
 * profession + workplace, which the household food task does not match).</p>
 */
public final class HouseholdFulfillments {

    private HouseholdFulfillments() {}

    public static void register(FulfillmentRegistry registry) {
        // Bake (score 10) outranks buy (score 2) when both can act, so a
        // household with a baker + wheat bakes rather than spending coin.
        registry.register(Objective.Type.MAINTAIN_STOCK, new BakeFulfillment());
        registry.register(Objective.Type.MAINTAIN_STOCK, new BuyFoodFulfillment());
    }
}
