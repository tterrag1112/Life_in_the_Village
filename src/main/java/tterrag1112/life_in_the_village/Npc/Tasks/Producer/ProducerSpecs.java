package tterrag1112.life_in_the_village.Npc.Tasks.Producer;

import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Tasks.FulfillmentRegistry;
import tterrag1112.life_in_the_village.Npc.Tasks.Objective;
import tterrag1112.life_in_the_village.Npc.Tasks.Blacksmith.BlacksmithSpec;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskContext;

import java.util.List;

/**
 * The roster of {@link ProductionTaskSpec}s the Task System knows about, and
 * the single place each spec's generic infrastructure is wired:
 * <ul>
 *   <li>{@link #generateAll} drives every applicable spec's
 *       {@link ProductionTaskSource} during the dispatcher's lazy refresh.</li>
 *   <li>{@link #registerFulfillments} registers each spec's generic craft +
 *       surplus-sell fulfillments into the shared registry, plus a
 *       {@link BuyFulfillment} for buy-only specs (all except the blacksmith,
 *       which uses its own bespoke smelt-vs-buy scoring).</li>
 * </ul>
 *
 * <p>This is the producer sweep's single edit point: add a spec to {@link #ALL}
 * and the source + generic fulfillments light up. Anything genuinely
 * profession-specific (the blacksmith's smelt / buy intermediate acquisition)
 * is still registered by that profession's own {@code Fulfillments} helper.</p>
 */
public final class ProducerSpecs {

    private ProducerSpecs() {}

    /** Every production spec on the Task System. */
    public static final List<ProductionTaskSpec> ALL = List.of(
            BlacksmithSpec.INSTANCE,
            CandleSpec.INSTANCE,
            WeaverSpec.INSTANCE,
            CarpenterSpec.INSTANCE,
            StonemasonSpec.INSTANCE,
            BakerSpec.INSTANCE,
            MillerSpec.INSTANCE
    );

    /**
     * Specs that use the generic {@link BuyFulfillment} for input acquisition.
     * The blacksmith is excluded here -- it uses its own bespoke
     * {@code BuyIngotFulfillment} with smelt-vs-buy scoring registered by
     * {@code BlacksmithFulfillments}.
     */
    private static final List<ProductionTaskSpec> BUY_ONLY_SPECS = List.of(
            CandleSpec.INSTANCE,
            WeaverSpec.INSTANCE,
            CarpenterSpec.INSTANCE,
            StonemasonSpec.INSTANCE,
            BakerSpec.INSTANCE,
            MillerSpec.INSTANCE
    );

    /** Refresh the board for whichever spec matches {@code npc}, if any. */
    public static void generateAll(ServerLevel level, TownspersonMob npc, TaskContext ctx) {
        for (ProductionTaskSpec spec : ALL) {
            if (npc.getProfession() != spec.profession()) continue;
            ProductionTaskSource.forNpc(level, npc, spec).ifPresent(src -> src.generate(ctx));
        }
    }

    /** Register the generic craft + surplus-sell fulfillment for every spec,
     *  and the generic buy fulfillment for buy-only specs. */
    public static void registerFulfillments(FulfillmentRegistry registry) {
        for (ProductionTaskSpec spec : ALL) {
            registry.register(Objective.Type.PROVIDE_ITEM, new CraftOutputFulfillment(spec));
            registry.register(Objective.Type.SELL_SURPLUS, new SellSurplusFulfillment(spec));
        }
        for (ProductionTaskSpec spec : BUY_ONLY_SPECS) {
            registry.register(Objective.Type.ACQUIRE, new BuyFulfillment(spec));
            registry.register(Objective.Type.MAINTAIN_STOCK, new BuyFulfillment(spec));
        }
    }
}
