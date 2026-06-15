package tterrag1112.life_in_the_village.Npc.Tasks.Producer;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Tasks.Assignment;
import tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Homestead.ContextProductionBehavior.Plan;
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
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Economy.Resources.ProductionHelpers;

import java.util.List;
import java.util.Optional;

/**
 * The generic <b>craft</b> fulfillment for {@code ProvideItem(final)},
 * generalized from the blacksmith pilot's {@code CraftToolFulfillment}.
 * Parameterized by a {@link ProductionTaskSpec}; reuses
 * {@link ContextPlanExecutor} to drive the shared production state machine for
 * the {@link Plan} the spec builds.
 *
 * <h3>Lazy intermediate dependency (preserved + generic)</h3>
 * If the building is short on a self-produced intermediate the recipe needs
 * (iron for the smith), this fulfillment does NOT gate the model: it ensures an
 * {@code Acquire(intermediate, n)} task exists on the same board (created once,
 * idempotent) and reports {@link #canFulfill} = false so the final task stays
 * open until the intermediate arrives. The Acquire is satisfied by the
 * profession's own intermediate-acquisition fulfillment(s) &mdash; for the smith,
 * smelt-own-ore or buy-from-market (the scorer picks). Professions with no
 * intermediate step never trip this branch.
 */
public final class CraftOutputFulfillment implements Fulfillment {

    private final ProductionTaskSpec spec;

    public CraftOutputFulfillment(ProductionTaskSpec spec) {
        this.spec = spec;
    }

    @Override
    public boolean canFulfill(Task task, TaskActor actor, TaskContext ctx) {
        if (!(task.objective() instanceof Objective.ProvideItem provide)) return false;
        TownspersonMob npc = ctx.npc().orElse(null);
        if (npc == null || npc.getProfession() != spec.profession()) return false;
        // Only handle this spec's own final outputs.
        if (!spec.finalOutputs().contains(provide.item())) return false;

        ServerLevel level = ctx.level();
        Building building = building(level, npc).orElse(null);
        if (building == null) return false;

        // Short on a self-produced intermediate? Lazily spawn its Acquire and
        // wait (do not gate the model).
        for (Item inter : spec.intermediateInputsOf(provide.item(), npc)) {
            int perBatch = spec.finalRecipeInputs(provide.item(), npc).getOrDefault(inter, 0);
            if (perBatch <= 0) continue;
            int stock = BuildingStorageAccess.countItem(level, building, inter);
            if (stock < perBatch) {
                ensureIntermediateDependency(task, ctx, inter, perBatch);
                return false;
            }
        }
        // A runnable plan (workstation present + all inputs on hand for >= 1 batch).
        return spec.craftPlan(level, npc, building, provide.item()).isPresent();
    }

    @Override
    public double score(Task task, TaskActor actor, TaskContext ctx) {
        // Producing a final is the profession's primary act; a flat high score.
        // Inter-task ordering is Priority on the board, not here.
        return 10.0;
    }

    @Override
    public TaskExecutor executor() {
        return new ContextPlanExecutor((level, npc, task) -> {
            if (!(task.objective() instanceof Objective.ProvideItem provide)) return Optional.empty();
            Building building = building(level, npc).orElse(null);
            if (building == null) return Optional.empty();
            return spec.craftPlan(level, npc, building, provide.item());
        });
    }

    private Optional<Building> building(ServerLevel level, TownspersonMob npc) {
        return ProductionHelpers.findAssignedBuilding(npc, level, spec.buildingType());
    }

    // ── Lazy Acquire(intermediate) dependency (generic) ──────────────────────

    private void ensureIntermediateDependency(Task finalTask, TaskContext ctx,
                                              Item intermediate, int needed) {
        ServerLevel level = ctx.level();
        TaskSavedData data = TaskSavedData.get(level);
        IssuerRef issuer = finalTask.issuer();
        TaskBoard board = data.board(issuer);

        TaskId acqId = ProductionTaskIds.stable(issuer,
                "acquire:" + ProductionTaskIds.key(intermediate) + ":for:" + finalTask.id().value());
        if (board.get(acqId).isPresent()) return; // already spawned

        int qty = Math.max(1, needed);
        // HIGH so the intermediate is fetched promptly once a final is blocked
        // on it; spawned ONLY in response to a real final task, so it never
        // pre-empts production in general (no final deficit -> no Acquire).
        Priority pr = new Priority(TaskPriority.HIGH, 0.8f);
        Task acq = new Task(acqId, issuer, new Objective.Acquire(intermediate, qty), pr,
                TaskFilter.ANY, new Assignment(), List.of(), 0L, finalTask.id());
        data.addTask(issuer, acq);
    }
}
