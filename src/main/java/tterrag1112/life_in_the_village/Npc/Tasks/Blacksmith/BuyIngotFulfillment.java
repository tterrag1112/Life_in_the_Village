package tterrag1112.life_in_the_village.Npc.Tasks.Blacksmith;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production.BlacksmithCrafts;
import tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production.WorkshopProcurement;
import tterrag1112.life_in_the_village.Npc.Tasks.Fulfillment;
import tterrag1112.life_in_the_village.Npc.Tasks.Task;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskActor;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskContext;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskExecutor;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;

import java.util.Map;

/**
 * T1 — fulfills {@code Acquire(IRON_INGOT)} / {@code MaintainStock(IRON_INGOT)}
 * by BUYING ingots through the shared procurement pipeline
 * ({@link WorkshopProcurement#buy}), the same path the legacy blacksmith
 * uses to source ore.
 *
 * <p>{@link #canFulfill}: the objective targets iron (we only buy ingots
 * in T1). Actual market availability + affordability are enforced
 * downstream by {@link WorkshopProcurement#buy} / the ChannelRouter, which
 * declines silently when no ingots are for sale or funds are short —
 * exactly as the legacy buy path relies on, so no duplicate market query
 * is open-coded here.</p>
 *
 * <p>{@link #score}: HIGHER when the building has NO ore or NO coal on hand
 * (the "can't smelt → buy" branch the prompt specifies); LOWER when it
 * could smelt. Paired with {@link SmeltFulfillment} (which scores 5.0 when
 * it CAN smelt), the dispatcher buys only when smelting isn't viable.</p>
 *
 * <p><b>Executor:</b> a fire-and-forget order — placing the buy is a single
 * async pipeline call (the ingots arrive via the channel system, not a
 * walk), so the executor places the order once and reports DONE. The
 * MaintainStock/Acquire task is satisfied when the ingots land and the
 * next source refresh sees the reserve met (or the tool task's iron
 * dependency clears).</p>
 */
public final class BuyIngotFulfillment implements Fulfillment {

    private final WorkshopProcurement.DiagFlags diag = new WorkshopProcurement.DiagFlags();

    @Override
    public boolean canFulfill(Task task, TaskActor actor, TaskContext ctx) {
        Item target = IronObjectives.itemOf(task.objective()).orElse(null);
        if (target != Items.IRON_INGOT) return false; // T1 buys iron ingots only
        TownspersonMob npc = ctx.npc().orElse(null);
        if (npc == null) return false;
        return BlacksmithPlans.building(ctx.level(), npc).isPresent();
    }

    @Override
    public double score(Task task, TaskActor actor, TaskContext ctx) {
        TownspersonMob npc = ctx.npc().orElse(null);
        if (npc == null) return 0.0;
        Building building = BlacksmithPlans.building(ctx.level(), npc).orElse(null);
        if (building == null) return 0.0;
        ServerLevel level = ctx.level();
        boolean hasCoal = BuildingStorageAccess.countItem(level, building, Items.COAL)
                >= BlacksmithCrafts.SMELT_FUEL.getOrDefault(Items.COAL, 2);
        boolean hasOre = hasAnyIronOre(level, building, npc);
        // No ore or no coal ⇒ cannot smelt ⇒ strongly prefer buying (score
        // above SmeltFulfillment's 5.0). Otherwise score below it so smelting
        // wins when both strategies are viable.
        return (!hasOre || !hasCoal) ? 6.0 : 1.0;
    }

    @Override
    public TaskExecutor executor() {
        return new OrderExecutor(diag);
    }

    /** True if the building holds the ore input of any iron smelting recipe the
     *  NPC can run (RAW_IRON / IRON_ORE depending on the recipe set). */
    private static boolean hasAnyIronOre(ServerLevel level, Building building, TownspersonMob npc) {
        return BlacksmithPlans.smeltRecipeFor(Items.IRON_INGOT, npc)
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
            Building building = BlacksmithPlans.building(level, npc).orElse(null);
            if (building == null) return Result.FAILED;

            int want = IronObjectives.qtyOf(task.objective());
            if (want <= 0) return Result.FAILED;
            // Buy only the shortfall against current stock.
            int stock = BuildingStorageAccess.countItem(level, building, Items.IRON_INGOT);
            int shortfall = Math.max(1, want - stock);

            WorkshopProcurement.buy(npc, building, Map.of(Items.IRON_INGOT, shortfall),
                    level, diag, "TaskBuyIngot");
            placed = true;
            // The order is async; DONE here releases the claim. The next source
            // refresh re-evaluates: if ingots haven't arrived the Acquire/
            // MaintainStock reopens (still under target), but a fresh buy is
            // only attempted when no ore/coal makes smelting non-viable.
            return Result.DONE;
        }
    }
}
