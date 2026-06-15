package tterrag1112.life_in_the_village.Npc.Tasks.Blacksmith;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Tasks.Execution.ContextPlanExecutor;
import tterrag1112.life_in_the_village.Npc.Tasks.Fulfillment;
import tterrag1112.life_in_the_village.Npc.Tasks.Task;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskActor;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskContext;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskExecutor;
import tterrag1112.life_in_the_village.Village.AmenityType;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Economy.Resources.ProductionRecipe;

import java.util.List;
import java.util.Optional;

/**
 * T1 — fulfills {@code Acquire(IRON_INGOT)} / {@code MaintainStock(IRON_INGOT)}
 * by SMELTING own ore + coal at the furnace. Awards {@link
 * tterrag1112.life_in_the_village.Npc.Skills.Skill#SMELTING} (the T1 SMELTING
 * binding).
 *
 * <p>{@link #canFulfill}: a smelt recipe for the target ingot exists + ore
 * input on hand + coal fuel on hand + a furnace. {@link #score}: higher
 * when ore+coal are on hand (so the scorer prefers smelting when it can),
 * lower otherwise — paired with {@link BuyIngotFulfillment} (which scores
 * higher when ore/coal are absent), the dispatcher smelts when able and
 * buys when not.</p>
 */
public final class SmeltFulfillment implements Fulfillment {

    @Override
    public boolean canFulfill(Task task, TaskActor actor, TaskContext ctx) {
        Item target = IronObjectives.itemOf(task.objective()).orElse(null);
        if (target == null) return false;
        TownspersonMob npc = ctx.npc().orElse(null);
        if (npc == null) return false;
        ServerLevel level = ctx.level();
        Building building = BlacksmithPlans.building(level, npc).orElse(null);
        if (building == null) return false;
        if (AmenityType.firstPresent(level, building, List.of(AmenityType.FURNACE)) == null) {
            return false;
        }
        ProductionRecipe recipe = BlacksmithPlans.smeltRecipeFor(target, npc).orElse(null);
        if (recipe == null) return false;
        // A runnable smelt plan requires ore inputs AND coal fuel for ≥1 batch.
        return BlacksmithPlans.smeltPlan(level, npc, building, recipe).isPresent();
    }

    @Override
    public double score(Task task, TaskActor actor, TaskContext ctx) {
        // canFulfill already confirmed ore+coal+furnace, so smelting is viable:
        // score it ABOVE BuyIngotFulfillment's flush-buy baseline. The more
        // inputs on hand, the stronger the preference.
        return 5.0;
    }

    @Override
    public TaskExecutor executor() {
        return new ContextPlanExecutor((level, npc, t) -> {
            Item target = IronObjectives.itemOf(t.objective()).orElse(null);
            if (target == null) return Optional.empty();
            Building building = BlacksmithPlans.building(level, npc).orElse(null);
            if (building == null) return Optional.empty();
            ProductionRecipe recipe = BlacksmithPlans.smeltRecipeFor(target, npc).orElse(null);
            if (recipe == null) return Optional.empty();
            return BlacksmithPlans.smeltPlan(level, npc, building, recipe);
        });
    }
}
