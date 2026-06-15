package tterrag1112.life_in_the_village.Npc.Tasks.Producer;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Homestead.ContextProductionBehavior.Plan;
import tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production.WeaverCrafts;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.AmenityType;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The WEAVER's {@link ProductionTaskSpec} — profession DATA consumed by the
 * generic producer infra.
 *
 * <p>No intermediates: every recipe input (string, white_wool, dyes) is
 * PURCHASED via the generic {@link BuyFulfillment}. {@link #intermediateInputsOf}
 * returns the full recipe input set so missing inputs spawn lazy
 * {@code Acquire} tasks.</p>
 *
 * <p>Workstation: {@link AmenityType#LOOM}, consistent with
 * {@link tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production.WeaverProductionBehavior}.</p>
 */
public final class WeaverSpec implements ProductionTaskSpec {

    public static final WeaverSpec INSTANCE = new WeaverSpec();

    private WeaverSpec() {}

    @Override public Profession profession()     { return Profession.WEAVER; }
    @Override public BuildingType buildingType() { return BuildingType.WEAVER; }
    @Override public int sellWindowDayTick()     { return WeaverCrafts.SELL_WINDOW_DAY_TICK; }

    @Override
    public List<Item> finalOutputs() {
        return WeaverCrafts.CRAFTS.stream()
                .filter(c -> c.quota() > 0)
                .map(WeaverCrafts.WeaverCraft::output)
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
        return WeaverCrafts.quotas().getOrDefault(output, 0);
    }

    @Override
    public int buffer(Item output) {
        return 0;
    }

    @Override
    public List<Item> sellableOutputs() {
        return WeaverCrafts.sellableOutputs();
    }

    @Override
    public Map<Item, Integer> finalRecipeInputs(Item output, TownspersonMob npc) {
        for (WeaverCrafts.WeaverCraft c : WeaverCrafts.CRAFTS) {
            if (c.output() == output && WeaverCrafts.meetsSkillGate(c.recipe(), npc)) {
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

    /** Craft plan: loom workstation, WEAVING XP. */
    @Override
    public Optional<Plan> craftPlan(ServerLevel level, TownspersonMob npc,
                                    Building building, Item output) {
        for (WeaverCrafts.WeaverCraft c : WeaverCrafts.CRAFTS) {
            if (c.output() != output) continue;
            if (!WeaverCrafts.meetsSkillGate(c.recipe(), npc)) continue;
            int batch = WeaverCrafts.batchSize(level, building, c.recipe());
            if (batch <= 0) continue;
            net.minecraft.core.BlockPos loom =
                    AmenityType.firstPresent(level, building, List.of(AmenityType.LOOM));
            if (loom == null) return Optional.empty();
            return Optional.of(new Plan(building, loom,
                    c.recipe(), Skill.WEAVING, c.xpPerBatch(),
                    c.activityLabel(),
                    batch, /*applyMultipliers*/ true, /*recordLedger*/ true));
        }
        return Optional.empty();
    }
}
