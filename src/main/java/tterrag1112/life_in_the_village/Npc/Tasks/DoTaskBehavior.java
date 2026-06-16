package tterrag1112.life_in_the_village.Npc.Tasks;

import com.google.common.collect.ImmutableMap;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.Behavior;
import tterrag1112.life_in_the_village.Entities.ActivityState;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Brain.Memories.NpcMemoryTypes;
import tterrag1112.life_in_the_village.Npc.Tasks.Producer.ProducerSpecs;
import tterrag1112.life_in_the_village.Npc.Tasks.Household.HouseholdTaskSource;
import tterrag1112.life_in_the_village.Npc.Tasks.Scribe.ScribeCommissionTaskSource;
import tterrag1112.life_in_the_village.Npc.Tasks.Farm.FarmTaskSource;
import tterrag1112.life_in_the_village.Npc.Tasks.Farm.AnimalTaskSource;
import tterrag1112.life_in_the_village.Npc.Tasks.Priest.PriestTaskSource;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * The universal Task-System dispatcher. Wired unconditionally into the WORK
 * activity at priority 1 (see {@code TownspersonMob.makeBrain}). The runtime
 * gate is {@link #scopeGateOpen}: for WORK_PROFESSION it checks
 * {@link TaskMigration#isMigrated}; for HOUSEHOLD it delegates to
 * {@link TaskMigration#ownsHousehold}. No static flag involved.
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

    /** Which scope this dispatcher serves (T2). Controls the eligibility
     *  gate, the source refreshed, and which boards are scanned. */
    private final TaskScope scope;

    private static final int MAX_RUN = 24000;

    /** Per-NPC source-refresh throttle (ticks). One day = 24000; refresh
     *  every ~5s so a freshly-emptied board fills promptly without churn. */
    private static final long REFRESH_INTERVAL = 100L;
    private long lastRefreshTick = Long.MIN_VALUE;

    // ── Per-run state (set in checkExtraStartConditions, used in tick) ────────
    private Task activeTask;
    private TaskExecutor activeExecutor;
    private TaskActor actor;

    /** Default WORK-profession dispatcher over the shared registry —
     *  byte-identical to the pre-T2 no-arg behaviour. */
    public DoTaskBehavior() {
        this(Fulfillments.shared(), TaskScope.WORK_PROFESSION);
    }

    /** Scoped dispatcher over the shared registry (T2: a second instance is
     *  registered in IDLE with {@link TaskScope#HOUSEHOLD}). */
    public DoTaskBehavior(TaskScope scope) {
        this(Fulfillments.shared(), scope);
    }

    public DoTaskBehavior(FulfillmentRegistry registry, TaskScope scope) {
        super(ImmutableMap.of(), MAX_RUN);
        this.registry = registry;
        this.scope = scope;
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, TownspersonMob entity) {
        // Migration gate (scope-specific):
        //  - WORK_PROFESSION: only migrated professions drive work via tasks.
        //  - HOUSEHOLD: every household is task-migrated when the flag is on.
        if (!scopeGateOpen(entity)) {
            return false;
        }

        // Dusk-yield gate (WORK_PROFESSION only): don't start a new profession
        // task when work-time has ended. The HOUSEHOLD scope runs in IDLE and
        // must NOT be gated (household chores happen off-hours).
        if (scope == TaskScope.WORK_PROFESSION && !entity.isWorkTime()) {
            signalNoActionableWork(entity, true);
            return false;
        }

        TaskContext ctx = new TaskContext(level, entity);
        refreshSources(level, entity, ctx);

        this.actor = new NpcActor(entity.getUUID());
        Task top = pickTop(level, actor, ctx);
        if (top == null) {
            signalNoActionableWork(entity, true);
            return false;
        }

        // Choose the best-scoring applicable fulfillment for the top task.
        Fulfillment best = bestFulfillment(top, actor, ctx);
        if (best == null) {
            signalNoActionableWork(entity, true);
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
        signalNoActionableWork(entity, false);
        // Brain-visibility label: shown on the nameplate and in /litv npc brain.
        // Only set here (once per run-start), not per-tick. Cleared in stop().
        entity.setActivityState(ActivityState.of("Task",
                tterrag1112.life_in_the_village.Commands.TaskDebugCommand
                        .objectiveSummary(top.objective())
                        + " → " + best.executor().getClass().getSimpleName()));
        return true;
    }

    @Override
    protected boolean canStillUse(ServerLevel level, TownspersonMob entity, long gameTime) {
        if (activeExecutor == null || activeTask == null) return false;
        // Dusk-yield: abandon the in-flight WORK_PROFESSION task when work-time
        // ends so the brain can flip to REST and ReturnHomeBehavior can start.
        // The brain will call stop(), which releases the claim gracefully and
        // erases WALK_TARGET so ReturnHomeBehavior's VALUE_ABSENT gate opens.
        if (scope == TaskScope.WORK_PROFESSION && !entity.isWorkTime()) return false;
        return true;
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
            if (scope == TaskScope.WORK_PROFESSION && !entity.isWorkTime()) {
                // Dusk-yield: graceful release returns the task to OPEN so the
                // same task can be re-claimed when work resumes next morning.
                // This is NOT a failure — the executor simply didn't finish today.
                activeTask.assignment().releaseGracefully(entity.getUUID());
            } else {
                // Pre-empted mid-day or mid-household: treat as a real abandon.
                // FAILED tasks are re-emitted by the source on the next refresh.
                activeTask.assignment().release(entity.getUUID());
            }
            TaskSavedData.get(level).markChanged();
        }
        // Erase WALK_TARGET on ANY stop so ReturnHomeBehavior's VALUE_ABSENT
        // start-gate opens. Only erases when we actually held an active task
        // (i.e. we ran) to avoid stomping a legitimate WALK_TARGET set by
        // another behavior in a different activity. The HOUSEHOLD scope is also
        // guarded: household tasks don't walk to the field, so this is a no-op
        // in practice, but is correct in either scope.
        if (activeTask != null) {
            entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            entity.setActivityState(ActivityState.IDLE);
        }
        finishRun();
    }

    // ── Debug-only read-only accessors (used by TaskDebugCommand) ──────────────

    /**
     * The task currently claimed by this behavior, or {@code null} when idle.
     * Read-only: callers must not mutate the returned task.
     */
    @Nullable
    public Task activeTask() {
        return activeTask;
    }

    /**
     * Simple class name of the currently active executor strategy, or
     * {@code "(none)"} when idle. Used by the inspect command to show the
     * chosen fulfillment without exposing the executor itself.
     */
    public String activeExecutorName() {
        return activeExecutor == null ? "(none)" : activeExecutor.getClass().getSimpleName();
    }

    private void finishRun() {
        activeTask = null;
        activeExecutor = null;
    }

    /**
     * Write/clear the WORK-channel {@code NO_ACTIONABLE_WORK} work-satisfied
     * signal — but ONLY for the WORK_PROFESSION scope. The HOUSEHOLD-scope
     * dispatcher runs in IDLE and must not touch this WORK-channel memory
     * (doing so would spuriously gate the WORK idle director / hobby), so it
     * is a no-op there.
     */
    private void signalNoActionableWork(TownspersonMob entity, boolean present) {
        if (scope != TaskScope.WORK_PROFESSION) return;
        if (present) {
            entity.getBrain().setMemory(NpcMemoryTypes.NO_ACTIONABLE_WORK.get(), Boolean.TRUE);
        } else {
            entity.getBrain().eraseMemory(NpcMemoryTypes.NO_ACTIONABLE_WORK.get());
        }
    }

    /** The scope's eligibility gate (see {@link TaskScope}). */
    private boolean scopeGateOpen(TownspersonMob entity) {
        return switch (scope) {
            case WORK_PROFESSION -> TaskMigration.isMigrated(entity.getProfession());
            case HOUSEHOLD       -> TaskMigration.ownsHousehold();
        };
    }

    // ── Source refresh (spec-driven; this phase: blacksmith) ─────────────────

    private void refreshSources(ServerLevel level, TownspersonMob entity, TaskContext ctx) {
        long now = level.getGameTime();
        if (now - lastRefreshTick < REFRESH_INTERVAL) return;
        lastRefreshTick = now;
        switch (scope) {
            case WORK_PROFESSION -> {
                // Each WORK-scope source self-filters to its own profession/
                // building, so only the matching one emits for this NPC.
                ProducerSpecs.generateAll(level, entity, ctx);
                ScribeCommissionTaskSource.generateFor(level, entity, ctx);
                FarmTaskSource.generateFor(level, entity, ctx);
                AnimalTaskSource.generateFor(level, entity, ctx);
                PriestTaskSource.generateFor(level, entity, ctx);
            }
            case HOUSEHOLD       -> HouseholdTaskSource.generateFor(level, entity, ctx);
        }
    }

    // ── Ranking + selection ──────────────────────────────────────────────────

    private Task pickTop(ServerLevel level, TaskActor actor, TaskContext ctx) {
        TaskSavedData data = TaskSavedData.get(level);
        List<Task> candidates = new ArrayList<>();
        for (IssuerRef ref : ctx.memberships()) {
            if (!scope.includes(ref)) continue; // scope isolation
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
        return checkDependenciesSatisfied(level, ctx, scope, task);
    }

    /**
     * Read-only, static variant of the dependency check — exposed for the
     * {@code /litv tasks why} diagnostic command. Zero behavior change:
     * identical logic to the private instance method, parameterised on
     * {@code scope} instead of {@code this.scope}.
     */
    public static boolean checkDependenciesSatisfied(ServerLevel level, TaskContext ctx,
                                              TaskScope scope, Task task) {
        if (task.dependencies().isEmpty()) return true;
        TaskSavedData data = TaskSavedData.get(level);
        for (IssuerRef ref : ctx.memberships()) {
            if (!scope.includes(ref)) continue;
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
