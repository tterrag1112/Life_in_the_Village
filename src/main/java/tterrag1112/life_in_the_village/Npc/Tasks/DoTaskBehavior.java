package tterrag1112.life_in_the_village.Npc.Tasks;

import com.google.common.collect.ImmutableMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.Behavior;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Brain.Memories.NpcMemoryTypes;

import java.util.ArrayList;
import java.util.List;

/**
 * The universal Task-System dispatcher. Wired into the WORK activity, but
 * ONLY when {@link TaskSystemConfig#ENABLED} (see
 * {@code TownspersonMob.makeBrain}) — with the flag off it is never added
 * to the brain, so it has zero effect.
 *
 * <p>Per evaluation it: gathers the eligible, ranked tasks from every
 * board the NPC belongs to; claims the top task; picks the best
 * {@link Fulfillment} via the {@link FulfillmentRegistry} score; and
 * (in later phases) dispatches to that strategy's {@link TaskExecutor},
 * managing the claim lifecycle.</p>
 *
 * <p><b>T0 behavior:</b> the registry is empty and boards are empty, so
 * no actionable task is ever found. The dispatcher then sets the
 * {@code NO_ACTIONABLE_WORK} signal (exactly as the production behaviors
 * do when idle) and yields, never starting — a clean no-op that lets the
 * idle director / hobby behaviors take over. No per-tick work and no log
 * output on the idle path.</p>
 */
public final class DoTaskBehavior extends Behavior<TownspersonMob> {

    /** Shared per-instance registry. Empty in T0 (populated in T1+). */
    private final FulfillmentRegistry registry;

    public DoTaskBehavior() {
        this(new FulfillmentRegistry());
    }

    /** Default behavior run-duration cap, matching the other WORK behaviors. */
    private static final int MAX_RUN = 60;

    public DoTaskBehavior(FulfillmentRegistry registry) {
        // No required memories: the dispatcher self-gates in
        // checkExtraStartConditions.
        super(ImmutableMap.of(), MAX_RUN);
        this.registry = registry;
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, TownspersonMob entity) {
        TaskContext ctx = new TaskContext(level, entity);
        TaskActor actor = new NpcActor(entity.getUUID());

        Task top = pickTop(level, actor, ctx);
        if (top == null) {
            // No actionable task. Signal idle so the idle director / hobby
            // behaviors run, then decline to start. No log spam: this is the
            // common path every tick the boards are empty (all of T0).
            entity.getBrain().setMemory(NpcMemoryTypes.NO_ACTIONABLE_WORK.get(), Boolean.TRUE);
            return false;
        }

        // T1+: claim `top`, choose its best Fulfillment via the registry
        // score, and begin execution. Unreachable in T0 (boards empty),
        // so no execution machinery ships here.
        return false;
    }

    /**
     * The highest-ranked task across the NPC's boards that has at least
     * one applicable, score-positive {@link Fulfillment}. Returns
     * {@code null} when nothing is actionable. In T0 this is always
     * {@code null} (empty boards / empty registry).
     */
    private Task pickTop(ServerLevel level, TaskActor actor, TaskContext ctx) {
        TaskSavedData data = TaskSavedData.get(level);

        List<Task> candidates = new ArrayList<>();
        for (IssuerRef ref : ctx.memberships()) {
            data.boardIfPresent(ref).ifPresent(board ->
                    candidates.addAll(board.rankedEligibleFor(actor, ctx)));
        }
        if (candidates.isEmpty()) return null;

        // Re-rank the merged cross-board list (each board was sorted on its
        // own; the merge needs a global ordering).
        candidates.sort(TaskBoard.RANKING);

        for (Task task : candidates) {
            if (hasApplicableFulfillment(task, actor, ctx)) {
                return task;
            }
        }
        return null;
    }

    private boolean hasApplicableFulfillment(Task task, TaskActor actor, TaskContext ctx) {
        for (Fulfillment f : registry.strategiesFor(task.objective())) {
            if (f.canFulfill(task, actor, ctx)) return true;
        }
        return false;
    }
}
