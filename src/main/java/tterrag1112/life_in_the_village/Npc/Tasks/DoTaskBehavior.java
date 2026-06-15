package tterrag1112.life_in_the_village.Npc.Tasks;

import com.google.common.collect.ImmutableMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.Behavior;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Brain.Memories.NpcMemoryTypes;
import tterrag1112.life_in_the_village.Npc.Tasks.Blacksmith.BlacksmithTaskSource;

import java.util.ArrayList;
import java.util.List;

/**
 * The universal Task-System dispatcher. Wired into the WORK activity at
 * priority 1, but ONLY when {@link TaskSystemConfig#ENABLED} (see
 * {@code TownspersonMob.makeBrain}) — with the flag off it is never added
 * to the brain, so it has zero effect.
 *
 * <h3>T1 — migration gating</h3>
 * The dispatcher only acts for a profession in {@link TaskMigration}
 * (T1: {@code BLACKSMITH}). For any other profession it declines so the
 * legacy WORK@0 behavior runs unchanged. A migrated profession's legacy
 * WORK@0 behavior yields (see {@code BlacksmithProductionBehavior
 * .checkContextGate}), so the brain falls through from WORK@0 to this
 * dispatcher at WORK@1.
 *
 * <h3>T1 — execution path</h3>
 * Per evaluation it: (lazily) refreshes the acting NPC's task source onto
 * its board; gathers the eligible, ranked tasks across the NPC's boards;
 * picks the top task with an applicable {@link Fulfillment}; claims it;
 * chooses the highest-{@link Fulfillment#score} strategy; and drives that
 * strategy's {@link TaskExecutor} across the behavior's tick lifecycle,
 * advancing / completing / releasing the claim.
 */
public final class DoTaskBehavior extends Behavior<TownspersonMob> {

    /** Shared, mod-wide registry (populated at mod init). */
    private final FulfillmentRegistry registry;

    private static final int MAX_RUN = 24000;

    /** Per-NPC source-refresh throttle (ticks). One day = 24000; refresh
     *  every ~5s so a freshly-emptied board fills promptly without churn. */
    private static final long REFRESH_INTERVAL = 100L;
    private long lastRefreshTick = Long.MIN_VALUE;

    // ── Per-run state (set in checkExtraStartConditions, used in tick) ────────
    private Task activeTask;
    private TaskExecutor activeExecutor;
    private TaskActor actor;

    public DoTaskBehavior() {
        this(Fulfillments.shared());
    }

    public DoTaskBehavior(FulfillmentRegistry registry) {
        super(ImmutableMap.of(), MAX_RUN);
        this.registry = registry;
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, TownspersonMob entity) {
        // Migration gate: only migrated professions drive work via tasks.
        if (!TaskMigration.isMigrated(entity.getProfession())) {
            return false;
        }

        TaskContext ctx = new TaskContext(level, entity);
        refreshSources(level, entity, ctx);

        this.actor = new NpcActor(entity.getUUID());
        Task top = pickTop(level, actor, ctx);
        if (top == null) {
            entity.getBrain().setMemory(NpcMemoryTypes.NO_ACTIONABLE_WORK.get(), Boolean.TRUE);
            return false;
        }

        // Choose the best-scoring applicable fulfillment for the top task.
        Fulfillment best = bestFulfillment(top, actor, ctx);
        if (best == null) {
            entity.getBrain().setMemory(NpcMemoryTypes.NO_ACTIONABLE_WORK.get(), Boolean.TRUE);
            return false;
        }

        // Claim the task for this actor. If the claim is lost to a race, decline.
        if (!top.assignment().claim(entity.getUUID())) {
            return false;
        }
        top.assignment().advance(); // CLAIMED -> IN_PROGRESS
        TaskSavedData.get(level).markChanged();

        this.activeTask = top;
        this.activeExecutor = best.executor();
        entity.getBrain().eraseMemory(NpcMemoryTypes.NO_ACTIONABLE_WORK.get());
        return true;
    }

    @Override
    protected boolean canStillUse(ServerLevel level, TownspersonMob entity, long gameTime) {
        return activeExecutor != null && activeTask != null;
    }

    @Override
    protected void tick(ServerLevel level, TownspersonMob entity, long gameTime) {
        if (activeExecutor == null || activeTask == null) return;
        TaskContext ctx = new TaskContext(level, entity);
        TaskExecutor.Result result = activeExecutor.tick(activeTask, actor, ctx);
        switch (result) {
            case RUNNING -> { /* keep going next tick */ }
            case DONE -> {
                activeTask.assignment().release(entity.getUUID());
                activeTask.assignment().complete();
                TaskSavedData.get(level).markChanged();
                finishRun();
            }
            case FAILED -> {
                // Release the claim; release() returns an IN_PROGRESS task with
                // no claimants to FAILED, so the source refresh can recreate it.
                activeTask.assignment().release(entity.getUUID());
                TaskSavedData.get(level).markChanged();
                finishRun();
            }
        }
    }

    @Override
    protected void stop(ServerLevel level, TownspersonMob entity, long gameTime) {
        // If the brain stops us mid-run (pre-empted), release the claim so the
        // task isn't stranded IN_PROGRESS with a claimant that walked away.
        if (activeTask != null && activeTask.assignment().isClaimedBy(entity.getUUID())) {
            activeTask.assignment().release(entity.getUUID());
            TaskSavedData.get(level).markChanged();
        }
        finishRun();
    }

    private void finishRun() {
        activeTask = null;
        activeExecutor = null;
    }

    // ── Source refresh (T1: blacksmith only) ─────────────────────────────────

    private void refreshSources(ServerLevel level, TownspersonMob entity, TaskContext ctx) {
        long now = level.getGameTime();
        if (now - lastRefreshTick < REFRESH_INTERVAL) return;
        lastRefreshTick = now;
        BlacksmithTaskSource.forNpc(level, entity).ifPresent(src -> src.generate(ctx));
    }

    // ── Ranking + selection ──────────────────────────────────────────────────

    private Task pickTop(ServerLevel level, TaskActor actor, TaskContext ctx) {
        TaskSavedData data = TaskSavedData.get(level);
        List<Task> candidates = new ArrayList<>();
        for (IssuerRef ref : ctx.memberships()) {
            data.boardIfPresent(ref).ifPresent(board ->
                    candidates.addAll(board.rankedEligibleFor(actor, ctx)));
        }
        if (candidates.isEmpty()) return null;
        candidates.sort(TaskBoard.RANKING);
        for (Task task : candidates) {
            if (!dependenciesSatisfied(level, ctx, task)) continue;
            if (bestFulfillment(task, actor, ctx) != null) return task;
        }
        return null;
    }

    /** A task whose dependencies aren't all DONE is not runnable yet. */
    private boolean dependenciesSatisfied(ServerLevel level, TaskContext ctx, Task task) {
        if (task.dependencies().isEmpty()) return true;
        TaskSavedData data = TaskSavedData.get(level);
        for (IssuerRef ref : ctx.memberships()) {
            var board = data.boardIfPresent(ref).orElse(null);
            if (board == null) continue;
            for (TaskId dep : task.dependencies()) {
                var dt = board.get(dep).orElse(null);
                if (dt != null && dt.assignment().status() != Assignment.Status.DONE) {
                    return false;
                }
            }
        }
        return true;
    }

    private Fulfillment bestFulfillment(Task task, TaskActor actor, TaskContext ctx) {
        Fulfillment best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (Fulfillment f : registry.strategiesFor(task.objective())) {
            if (!f.canFulfill(task, actor, ctx)) continue;
            double s = f.score(task, actor, ctx);
            if (s > bestScore) { bestScore = s; best = f; }
        }
        return best;
    }
}
