package tterrag1112.life_in_the_village.Npc.Tasks.Blacksmith;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Tasks.Execution.ContextPlanExecutor;
import tterrag1112.life_in_the_village.Npc.Tasks.Fulfillment;
import tterrag1112.life_in_the_village.Npc.Tasks.Producer.AcquireObjectives;
import tterrag1112.life_in_the_village.Npc.Tasks.Task;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskActor;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskContext;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskExecutor;
import tterrag1112.life_in_the_village.Village.AmenityType;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Economy.Resources.ProductionHelpers;

import java.util.List;
import java.util.Optional;

/**
 * Blacksmith-specific intermediate acquisition: fulfills
 * {@code Acquire(ingot)} / {@code MaintainStock(ingot)} by SMELTING own ore +
 * coal at the furnace, awarding {@code Skill.SMELTING}. Pairs with
 * {@link BuyIngotFulfillment}: the dispatcher smelts when ore+coal are on hand
 * and buys when not. The smelt {@link Plan} itself is built by
 * {@link BlacksmithSpec#intermediatePlan} (the spec's data).
 *
 * <p>This is the part that is genuinely blacksmith-specific (a profession with
 * no self-produced intermediate has no analogue), so it stays a per-profession
 * fulfillment rather than collapsing into the generic infra.</p>
 */
public final class SmeltFulfillment implements Fulfillment {

    private final BlacksmithSpec spec = BlacksmithSpec.INSTANCE;

    @Override
    public boolean canFulfill(Task task, TaskActor actor, TaskContext ctx) {
        Item target = AcquireObjectives.itemOf(task.objective()).orElse(null);
        if (target == null) return false;
        TownspersonMob npc = ctx.npc().orElse(null);
        if (npc == null) return false;
        ServerLevel level = ctx.level();
        Building building = building(level, npc).orElse(null);
        if (building == null) return false;
        if (AmenityType.firstPresent(level, building, List.of(AmenityType.FURNACE)) == null) {
            return false;
        }
        // A runnable smelt plan requires ore inputs AND coal fuel for >=1 batch.
        return spec.intermediatePlan(level, npc, building, target).isPresent();
    }

    @Override
    public double score(Task task, TaskActor actor, TaskContext ctx) {
        // canFulfill already confirmed ore+coal+furnace; score ABOVE the buy
        // strategy's flush-buy baseline so smelting wins when viable.
        return 5.0;
    }

    @Override
    public TaskExecutor executor() {
        return new ContextPlanExecutor((level, npc, t) -> {
            Item target = AcquireObjectives.itemOf(t.objective()).orElse(null);
            if (target == null) return Optional.empty();
            Building building = building(level, npc).orElse(null);
            if (building == null) return Optional.empty();
            return spec.intermediatePlan(level, npc, building, target);
        });
    }

    private Optional<Building> building(ServerLevel level, TownspersonMob npc) {
        return ProductionHelpers.findAssignedBuilding(npc, level, BuildingType.BLACKSMITH);
    }
}
