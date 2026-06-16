package tterrag1112.life_in_the_village.Npc.Tasks;

import tterrag1112.life_in_the_village.Npc.Tasks.Blacksmith.BlacksmithFulfillments;
import tterrag1112.life_in_the_village.Npc.Tasks.Household.HouseholdFulfillments;
import tterrag1112.life_in_the_village.Npc.Tasks.Producer.DeliverFulfillment;
import tterrag1112.life_in_the_village.Npc.Tasks.Producer.ProducerSpecs;
import tterrag1112.life_in_the_village.Npc.Tasks.Scribe.ScribeWriteFulfillment;
import tterrag1112.life_in_the_village.Npc.Tasks.Objective;
import tterrag1112.life_in_the_village.Npc.Tasks.Farm.FarmAcquireFulfillment;
import tterrag1112.life_in_the_village.Npc.Tasks.Farm.AnimalTendFulfillment;
import tterrag1112.life_in_the_village.Npc.Tasks.Farm.FarmCropFulfillment;
import tterrag1112.life_in_the_village.Npc.Tasks.Farm.FarmSellFulfillment;
import tterrag1112.life_in_the_village.Npc.Tasks.Farm.ShearFulfillment;
import tterrag1112.life_in_the_village.Npc.Tasks.Farm.HoneyFulfillment;
import tterrag1112.life_in_the_village.Npc.Tasks.Priest.PriestFulfillment;
import tterrag1112.life_in_the_village.Npc.Tasks.Monk.MonkFulfillment;
import tterrag1112.life_in_the_village.Npc.Tasks.Mine.MineFulfillment;

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
     *
     * <p>PERFORM_SERVICE fulfillments are mutually exclusive by kind:</p>
     * <ul>
     *   <li>{@link ScribeWriteFulfillment} — scribal commission (self-filters)</li>
     *   <li>{@link FarmCropFulfillment} — kind in {farm_harvest, farm_replant, farm_till, farm_compost}</li>
     *   <li>{@link AnimalTendFulfillment} — kind == "animal_tend"</li>
     *   <li>{@link ShearFulfillment} — kind == "shear"</li>
     *   <li>{@link HoneyFulfillment} — kind == "collect_honey"</li>
     *   <li>{@link PriestFulfillment} — kind == "officiate_rite"</li>
     * </ul>
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
        // T3 — the scribe's scribal-commission service strategy (the only
        // PerformService strategy; it self-filters to scribal PerformService).
        SHARED.register(Objective.Type.PERFORM_SERVICE, new ScribeWriteFulfillment());
        // T5b-3 — the producer-carries delivery strategy for cascaded CRAFT
        // requests (Objective.Deliver). Nothing else handles DELIVER.
        SHARED.register(Objective.Type.DELIVER, new DeliverFulfillment());
        // G1 — farm crop service tasks (harvest/replant/till/compost for FARMER).
        SHARED.register(Objective.Type.PERFORM_SERVICE, new FarmCropFulfillment());
        // G2 — animal-tending (pasture rotation, ANIMAL_HUSBANDRY XP, disease recovery).
        SHARED.register(Objective.Type.PERFORM_SERVICE, new AnimalTendFulfillment());
        // G2b — sheep shearing (SHEPHERD role, GATED_SPECIES realized production).
        SHARED.register(Objective.Type.PERFORM_SERVICE, new ShearFulfillment());
        // G2b — honey collection (BEEKEEPER role, GATED_SPECIES realized production).
        SHARED.register(Objective.Type.PERFORM_SERVICE, new HoneyFulfillment());
        // G4 — priest rite-officiation (officiate_rite for PRIEST, sacred-building gated).
        SHARED.register(Objective.Type.PERFORM_SERVICE, new PriestFulfillment());
        // G5a — monastic craft production (monastic_craft for MONK, monastery-gated).
        SHARED.register(Objective.Type.PERFORM_SERVICE, new MonkFulfillment());
        // G5a — mine ore production (mine for MINER, mine-building gated).
        SHARED.register(Objective.Type.PERFORM_SERVICE, new MineFulfillment());
        // G1b — farmer surplus selling (SellSurplus for FARMER, coexists with
        //        SellSurplusFulfillment; profession guard discriminates).
        SHARED.register(Objective.Type.SELL_SURPLUS, new FarmSellFulfillment());
        // G1b — farmer seed buying (Acquire for FARMER, seed-item guard
        //        prevents interception of other professions' Acquire tasks).
        SHARED.register(Objective.Type.ACQUIRE, new FarmAcquireFulfillment());
    }
}
