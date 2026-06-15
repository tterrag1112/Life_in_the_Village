package tterrag1112.life_in_the_village.Npc.Tasks.Producer;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Homestead.ContextProductionBehavior.Plan;
import tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production.CarpenterCrafts;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.AmenityType;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The CARPENTER's {@link ProductionTaskSpec} — profession DATA consumed by the
 * generic producer infra.
 *
 * <p>No intermediates: every recipe input (logs) is PURCHASED via the generic
 * {@link BuyFulfillment}. {@link #intermediateInputsOf} returns the full recipe
 * input set so missing inputs spawn lazy {@code Acquire} tasks.</p>
 *
 * <p>Workstation: {@link AmenityType#CRAFTING_TABLE}, consistent with
 * {@link tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production.CarpenterProductionBehavior}.</p>
 */
public final class CarpenterSpec implements ProductionTaskSpec {

    public static final CarpenterSpec INSTANCE = new CarpenterSpec();

    private CarpenterSpec() {}

    @Override public Profession profession()     { return Profession.CARPENTER; }
    @Override public BuildingType buildingType() { return BuildingType.CARPENTRY; }
    @Override public int sellWindowDayTick()     { return CarpenterCrafts.SELL_WINDOW_DAY_TICK; }

    @Override
    public List<Item> finalOutputs() {
        return CarpenterCrafts.CRAFTS.stream()
                .filter(c -> c.quota() > 0)
                .map(CarpenterCrafts.CarpenterCraft::output)
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
        return CarpenterCrafts.quotas().getOrDefault(output, 0);
    }

    @Override
    public int buffer(Item output) {
        return 0;
    }

    @Override
    public List<Item> sellableOutputs() {
        return CarpenterCrafts.sellableOutputs();
    }

    @Override
    public Map<Item, Integer> finalRecipeInputs(Item output, TownspersonMob npc) {
        for (CarpenterCrafts.CarpenterCraft c : CarpenterCrafts.CRAFTS) {
            if (c.output() == output && CarpenterCrafts.meetsSkillGate(c.recipe(), npc)) {
                return c.recipe().inputs();
            }
        }
        return Map.of();
    }

    /**
     * All recipe inputs are purchased, so return the full input set to trigger
     * lazy {@code Acquire} → {@link BuyFulfillment} when inputs are short.
     */
    @Override
    public List<Item> intermediateInputsOf(Item output, TownspersonMob npc) {
        return List.copyOf(finalRecipeInputs(output, npc).keySet());
    }

    /** Craft plan: crafting table workstation, CARPENTRY XP. */
    @Override
    public Optional<Plan> craftPlan(ServerLevel level, TownspersonMob npc,
                                    Building building, Item output) {
        for (CarpenterCrafts.CarpenterCraft c : CarpenterCrafts.CRAFTS) {
            if (c.output() != output) continue;
            if (!CarpenterCrafts.meetsSkillGate(c.recipe(), npc)) continue;
            int batch = CarpenterCrafts.batchSize(level, building, c.recipe());
            if (batch <= 0) continue;
            net.minecraft.core.BlockPos table =
                    AmenityType.firstPresent(level, building, List.of(AmenityType.CRAFTING_TABLE));
            if (table == null) return Optional.empty();
            return Optional.of(new Plan(building, table,
                    c.recipe(), Skill.CARPENTRY, c.xpPerBatch(),
                    c.activityLabel(),
                    batch, /*applyMultipliers*/ true, /*recordLedger*/ true));
        }
        return Optional.empty();
    }
}
