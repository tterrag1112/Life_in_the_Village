package tterrag1112.life_in_the_village.Npc.Tasks.Blacksmith;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production.BlacksmithCrafts;
import tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production.WorkshopProcurement;
import tterrag1112.life_in_the_village.Npc.Tasks.Fulfillment;
import tterrag1112.life_in_the_village.Npc.Tasks.Producer.AcquireObjectives;
import tterrag1112.life_in_the_village.Npc.Tasks.Task;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskActor;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskContext;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskExecutor;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Economy.Resources.ProductionHelpers;

import java.util.Map;
import java.util.Optional;

/**
 * Blacksmith-specific intermediate acquisition: fulfills
 * {@code Acquire(IRON_INGOT)} / {@code MaintainStock(IRON_INGOT)} by BUYING
 * ingots through the shared procurement pipeline ({@link WorkshopProcurement#buy}),
 * the same path the legacy blacksmith uses to source ore.
 *
 * <p>{@link #score}: HIGHER when the building has NO ore or NO coal on hand
 * (the "can't smelt, buy" branch); LOWER when it could smelt. Paired with
 * {@link SmeltFulfillment} (5.0 when it can smelt), the dispatcher buys only
 * when smelting isn't viable.</p>
 */
public final class BuyIngotFulfillment implements Fulfillment {

    private final BlacksmithSpec spec = BlacksmithSpec.INSTANCE;
    private final WorkshopProcurement.DiagFlags diag = new WorkshopProcurement.DiagFlags();

    @Override
    public boolean canFulfill(Task task, TaskActor actor, TaskContext ctx) {
        Item target = AcquireObjectives.itemOf(task.objective()).orElse(null);
        if (target != Items.IRON_INGOT) return false; // buys iron ingots only
        TownspersonMob npc = ctx.npc().orElse(null);
        if (npc == null) return false;
        return building(ctx.level(), npc).isPresent();
    }

    @Override
    public double score(Task task, TaskActor actor, TaskContext ctx) {
        TownspersonMob npc = ctx.npc().orElse(null);
        if (npc == null) return 0.0;
        Building building = building(ctx.level(), npc).orElse(null);
        if (building == null) return 0.0;
        ServerLevel level = ctx.level();
        boolean hasCoal = BuildingStorageAccess.countItem(level, building, Items.COAL)
                >= BlacksmithCrafts.SMELT_FUEL.getOrDefault(Items.COAL, 2);
        boolean hasOre = hasAnyIronOre(level, building, npc);
        return (!hasOre || !hasCoal) ? 6.0 : 1.0;
    }

    @Override
    public TaskExecutor executor() {
        return new OrderExecutor(diag);
    }

    private Optional<Building> building(ServerLevel level, TownspersonMob npc) {
        return ProductionHelpers.findAssignedBuilding(npc, level, BuildingType.BLACKSMITH);
    }

    /** True if the building holds the ore input of an iron smelting recipe the
     *  NPC can run. */
    private boolean hasAnyIronOre(ServerLevel level, Building building, TownspersonMob npc) {
        return spec.smeltRecipeFor(Items.IRON_INGOT, npc)
                .map(r -> r.inputs().entrySet().stream().allMatch(e ->
                        BuildingStorageAccess.countItem(level, building, e.getKey()) >= e.getValue()))
                .orElse(false);
    }

    /** One-shot buy-order executor. */
    private static final class OrderExecutor implements TaskExecutor {
        private final WorkshopProcurement.DiagFlags diag;
        private boolean placed;

        OrderExecutor(WorkshopProcurement.DiagFlags diag) { this.diag = diag; }

        @Override
        public Result tick(Task task, TaskActor actor, TaskContext ctx) {
            if (placed) return Result.DONE;
            TownspersonMob npc = ctx.npc().orElse(null);
            if (npc == null) return Result.FAILED;
            ServerLevel level = ctx.level();
            Building building = ProductionHelpers
                    .findAssignedBuilding(npc, level, BuildingType.BLACKSMITH).orElse(null);
            if (building == null) return Result.FAILED;

            int want = AcquireObjectives.qtyOf(task.objective());
            if (want <= 0) return Result.FAILED;
            int stock = BuildingStorageAccess.countItem(level, building, Items.IRON_INGOT);
            int shortfall = Math.max(1, want - stock);

            WorkshopProcurement.buy(npc, building, Map.of(Items.IRON_INGOT, shortfall),
                    level, diag, "TaskBuyIngot");
            placed = true;
            return Result.DONE;
        }
    }
}
