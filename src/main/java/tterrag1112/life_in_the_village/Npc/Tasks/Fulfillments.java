package tterrag1112.life_in_the_village.Npc.Tasks;

import tterrag1112.life_in_the_village.Npc.Tasks.Blacksmith.BlacksmithFulfillments;
import tterrag1112.life_in_the_village.Npc.Tasks.Household.HouseholdFulfillments;
import tterrag1112.life_in_the_village.Npc.Tasks.Producer.ProducerSpecs;

/**
 * T1 — the single, shared {@link FulfillmentRegistry} for the whole mod.
 *
 * <p>T0 gave every {@link DoTaskBehavior} its own empty per-instance
 * registry (always declined). T1 replaces that with one process-wide
 * registry populated once at mod init ({@link #install()} from
 * {@code commonSetup}); the no-arg {@link DoTaskBehavior} constructor now
 * reads {@link #shared()}.</p>
 *
 * <p>Registration is idempotent-on-install only in the sense that
 * {@link #install()} is expected to run exactly once per JVM (commonSetup
 * fires once). Each migrated profession contributes its fulfillments
 * through its own {@code register(...)} helper called from here.</p>
 */
public final class Fulfillments {

    private Fulfillments() {}

    private static final FulfillmentRegistry SHARED = new FulfillmentRegistry();

    /** The process-wide registry the dispatcher consults. */
    public static FulfillmentRegistry shared() {
        return SHARED;
    }

    /**
     * Populate the shared registry. Call once from {@code commonSetup}.
     * Each migrated profession registers here.
     */
    public static void install() {
        // Generic, spec-driven producer fulfillments (craft + surplus-sell)
        // for every registered ProductionTaskSpec.
        ProducerSpecs.registerFulfillments(SHARED);
        // Profession-specific intermediate acquisition (the smith's
        // smelt-own-ore / buy-ingots strategies).
        BlacksmithFulfillments.register(SHARED);
        // T2 — household food (bake-vs-buy) strategies for the household-scope
        // MaintainStock(BREAD) task.
        HouseholdFulfillments.register(SHARED);
    }
}
