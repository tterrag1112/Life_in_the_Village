package tterrag1112.life_in_the_village.Npc.Tasks.Producer;

import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Tasks.Blacksmith.BlacksmithSpec;
import tterrag1112.life_in_the_village.Npc.Tasks.FulfillmentRegistry;
import tterrag1112.life_in_the_village.Npc.Tasks.Objective;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskContext;

import java.util.List;

/**
 * The roster of {@link ProductionTaskSpec}s the Task System knows about, and
 * the single place each spec's generic infrastructure is wired:
 * <ul>
 *   <li>{@link #generateAll} drives every applicable spec's
 *       {@link ProductionTaskSource} during the dispatcher's lazy refresh.</li>
 *   <li>{@link #registerFulfillments} registers each spec's generic craft +
 *       surplus-sell fulfillments into the shared registry.</li>
 * </ul>
 *
 * <p>This is the producer sweep's single edit point: add a spec to {@link #ALL}
 * and the source + generic fulfillments light up. Anything genuinely
 * profession-specific (the blacksmith's smelt / buy intermediate acquisition)
 * is still registered by that profession's own {@code Fulfillments} helper.</p>
 *
 * <p>This phase ships exactly {@code { BlacksmithSpec }}. (Migration gating is
 * still owned by {@code TaskMigration}; a spec only fires for an NPC whose
 * profession matches both that gate and the spec.)</p>
 */
public final class ProducerSpecs {

    private ProducerSpecs() {}

    /** Every production spec on the Task System. */
    public static final List<ProductionTaskSpec> ALL = List.of(
            BlacksmithSpec.INSTANCE
    );

    /** Refresh the board for whichever spec matches {@code npc}, if any. */
    public static void generateAll(ServerLevel level, TownspersonMob npc, TaskContext ctx) {
        for (ProductionTaskSpec spec : ALL) {
            if (npc.getProfession() != spec.profession()) continue;
            ProductionTaskSource.forNpc(level, npc, spec).ifPresent(src -> src.generate(ctx));
        }
    }

    /** Register the generic craft + surplus-sell fulfillment for every spec. */
    public static void registerFulfillments(FulfillmentRegistry registry) {
        for (ProductionTaskSpec spec : ALL) {
            registry.register(Objective.Type.PROVIDE_ITEM, new CraftOutputFulfillment(spec));
            registry.register(Objective.Type.SELL_SURPLUS, new SellSurplusFulfillment(spec));
        }
    }
}
