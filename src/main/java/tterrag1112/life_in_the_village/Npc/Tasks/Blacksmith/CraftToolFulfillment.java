package tterrag1112.life_in_the_village.Npc.Tasks.Blacksmith;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Tasks.Assignment;
import tterrag1112.life_in_the_village.Npc.Tasks.Execution.ContextPlanExecutor;
import tterrag1112.life_in_the_village.Npc.Tasks.Fulfillment;
import tterrag1112.life_in_the_village.Npc.Tasks.IssuerRef;
import tterrag1112.life_in_the_village.Npc.Tasks.Objective;
import tterrag1112.life_in_the_village.Npc.Tasks.Priority;
import tterrag1112.life_in_the_village.Npc.Tasks.Task;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskActor;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskBoard;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskContext;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskExecutor;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskFilter;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskId;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskSavedData;
import tterrag1112.life_in_the_village.Profession.Tasks.TaskPriority;
import tterrag1112.life_in_the_village.Village.AmenityType;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Economy.Resources.ProductionRecipe;

import java.util.List;
import java.util.Optional;

/**
 * T1 — fulfills {@code ProvideItem(tool)} by forging the tool at the anvil.
 *
 * <p><b>Lazy iron dependency (D5).</b> If the building lacks the iron the
 * recipe needs, this fulfillment does NOT block the model: it ensures an
 * {@code Acquire(IRON_INGOT, n)} task exists on the same board (created
 * once, idempotent) and reports {@link #canFulfill} = false so the tool
 * task stays open until iron arrives. The Acquire task is satisfied by
 * {@link SmeltFulfillment} (own ore+coal) or {@link BuyIngotFulfillment}
 * (market) — the scorer picks. This is what makes smelting a lazily-spawned
 * dependency of toolmaking rather than a blanket gate.</p>
 */
public final class CraftToolFulfillment implements Fulfillment {

    @Override
    public boolean canFulfill(Task task, TaskActor actor, TaskContext ctx) {
        if (!(task.objective() instanceof Objective.ProvideItem provide)) return false;
        TownspersonMob npc = ctx.npc().orElse(null);
        if (npc == null) return false;
        ServerLevel level = ctx.level();

        Building building = BlacksmithPlans.building(level, npc).orElse(null);
        if (building == null) return false;

        // Anvil present?
        if (AmenityType.firstPresent(level, building, List.of(AmenityType.ANVIL)) == null) {
            return false;
        }

        // A craftable recipe for this output the NPC is skilled enough to run.
        ProductionRecipe recipe = BlacksmithPlans.craftRecipeFor(provide.item(), npc).orElse(null);
        if (recipe == null) return false;

        // Iron check: do we have the recipe's iron input on hand for ≥ 1 batch?
        int ironPerBatch = recipe.inputs().getOrDefault(Items.IRON_INGOT, 0);
        if (ironPerBatch > 0) {
            int ironStock = BuildingStorageAccess.countItem(level, building, Items.IRON_INGOT);
            if (ironStock < ironPerBatch) {
                // Lazily ensure the Acquire(IRON_INGOT) dependency exists, then
                // wait. The tool task is NOT a smelting gate — it simply blocks
                // on its own iron dependency.
                ensureIronDependency(task, ctx, ironPerBatch);
                return false;
            }
        }
        // Non-iron inputs (sticks etc.) must also be present for a batch.
        if (BlacksmithPlans.craftPlan(level, npc, building, recipe).isEmpty()) {
            return false;
        }
        return true;
    }

    @Override
    public double score(Task task, TaskActor actor, TaskContext ctx) {
        // Toolmaking is the smith's primary act; a flat high score. Inter-task
        // ordering is handled by Priority on the board, not here (this scorer
        // only ranks among strategies FOR THE SAME objective, and ProvideItem
        // has just this one strategy in T1).
        return 10.0;
    }

    @Override
    public TaskExecutor executor() {
        // The plan is resolved from the TASK passed to the executor each tick,
        // so the no-arg executor() contract holds without any out-of-band bind.
        return new ContextPlanExecutor((level, npc, task) -> {
            if (!(task.objective() instanceof Objective.ProvideItem provide)) {
                return Optional.empty();
            }
            Building building = BlacksmithPlans.building(level, npc).orElse(null);
            if (building == null) return Optional.empty();
            ProductionRecipe recipe = BlacksmithPlans.craftRecipeFor(provide.item(), npc).orElse(null);
            if (recipe == null) return Optional.empty();
            return BlacksmithPlans.craftPlan(level, npc, building, recipe);
        });
    }

    // ── Lazy Acquire(IRON_INGOT) dependency ───────────────────────────────────

    private void ensureIronDependency(Task toolTask, TaskContext ctx, int ironNeeded) {
        ServerLevel level = ctx.level();
        TaskSavedData data = TaskSavedData.get(level);
        IssuerRef issuer = toolTask.issuer();
        TaskBoard board = data.board(issuer);

        TaskId acqId = BlacksmithTaskSource.stableId(issuer, "acquire:iron_for:" + toolTask.id().value());
        if (board.get(acqId).isPresent()) return; // already spawned

        // Acquire enough iron for a sensible batch (the recipe's per-batch need
        // × a small batch). Keep it modest in T1: one batch's worth, min 1.
        int qty = Math.max(1, ironNeeded);
        Objective obj = new Objective.Acquire(Items.IRON_INGOT, qty);
        // HIGH so iron is fetched promptly once a tool task is blocked on it —
        // but it is spawned ONLY in response to a real tool task, so it never
        // pre-empts toolmaking in general (no tool deficit → no Acquire).
        Priority pr = new Priority(TaskPriority.HIGH, 0.8f);
        Task acq = new Task(acqId, issuer, obj, pr, TaskFilter.ANY,
                new Assignment(), List.of(), 0L, toolTask.id());
        data.addTask(issuer, acq);
    }
}
