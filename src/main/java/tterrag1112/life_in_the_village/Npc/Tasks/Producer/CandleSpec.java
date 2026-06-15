package tterrag1112.life_in_the_village.Npc.Tasks.Producer;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Homestead.ContextProductionBehavior.Plan;
import tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production.CandleCrafts;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The CANDLEMAKER's {@link ProductionTaskSpec} — profession DATA consumed by
 * the generic producer infra ({@link ProductionTaskSource} +
 * {@link CraftOutputFulfillment} + {@link SellSurplusFulfillment} +
 * {@link BuyFulfillment}).
 *
 * <p>No intermediates: every recipe input (honeycomb, string, stick, coal,
 * iron_nugget) is PURCHASED via the generic {@link BuyFulfillment}. When a
 * craft is blocked on a missing input, {@link #intermediateInputsOf} returns
 * all recipe inputs so {@link CraftOutputFulfillment} lazily spawns an
 * {@code Acquire(input)} task that {@link BuyFulfillment} satisfies.</p>
 *
 * <p>No workstation: the candlemaker works at the building origin
 * ({@code Plan.workstationPos = null}), consistent with
 * {@link tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production.CandlemakerProductionBehavior}.</p>
 */
public final class CandleSpec implements ProductionTaskSpec {

    public static final CandleSpec INSTANCE = new CandleSpec();

    private CandleSpec() {}

    @Override public Profession profession()     { return Profession.CANDLEMAKER; }
    @Override public BuildingType buildingType() { return BuildingType.CANDLEMAKER; }
    @Override public int sellWindowDayTick()     { return CandleCrafts.SELL_WINDOW_DAY_TICK; }

    @Override
    public List<Item> finalOutputs() {
        return CandleCrafts.CRAFTS.stream()
                .filter(c -> c.quota() > 0)
                .map(CandleCrafts.CandleCraft::output)
                .distinct()
                .toList();
    }

    /** No self-produced intermediates — all inputs are purchased. */
    @Override
    public List<Item> intermediateOutputs() {
        return List.of();
    }

    @Override
    public int quota(Item output) {
        return CandleCrafts.quotas().getOrDefault(output, 0);
    }

    /** No intermediate reserve buffer needed (no intermediates). */
    @Override
    public int buffer(Item output) {
        return 0;
    }

    @Override
    public List<Item> sellableOutputs() {
        return CandleCrafts.sellableOutputs();
    }

    @Override
    public Map<Item, Integer> finalRecipeInputs(Item output, TownspersonMob npc) {
        for (CandleCrafts.CandleCraft c : CandleCrafts.CRAFTS) {
            if (c.output() == output && CandleCrafts.meetsSkillGate(c.recipe(), npc)) {
                return c.recipe().inputs();
            }
        }
        return Map.of();
    }

    /**
     * All recipe inputs are purchased (no self-produced intermediate exists),
     * so override to return the full input set — every missing input triggers
     * a lazy {@code Acquire} → {@link BuyFulfillment}.
     */
    @Override
    public List<Item> intermediateInputsOf(Item output, TownspersonMob npc) {
        return List.copyOf(finalRecipeInputs(output, npc).keySet());
    }

    /** Craft plan: no workstation (candlemaker works at origin), CANDLEMAKING XP. */
    @Override
    public Optional<Plan> craftPlan(ServerLevel level, TownspersonMob npc,
                                    Building building, Item output) {
        for (CandleCrafts.CandleCraft c : CandleCrafts.CRAFTS) {
            if (c.output() != output) continue;
            if (!CandleCrafts.meetsSkillGate(c.recipe(), npc)) continue;
            int batch = CandleCrafts.batchSize(level, building, c.recipe());
            if (batch <= 0) continue;
            return Optional.of(new Plan(building, /*workstationPos*/ null,
                    c.recipe(), Skill.CANDLEMAKING, c.xpPerBatch(),
                    c.activityLabel(),
                    batch, /*applyMultipliers*/ true, /*recordLedger*/ true));
        }
        return Optional.empty();
    }
}
