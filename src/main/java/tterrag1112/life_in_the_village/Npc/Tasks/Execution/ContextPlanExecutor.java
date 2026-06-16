package tterrag1112.life_in_the_village.Npc.Tasks.Execution;

import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Homestead.ContextProductionBehavior.Plan;
import tterrag1112.life_in_the_village.Npc.Tasks.Task;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskActor;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskContext;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskExecutor;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * T1 — the generic produce executor. Drives one production cycle of the
 * shared {@link ContextProductionBehavior} state machine (via
 * {@link ContextProductionAdapter}) to completion, then reports DONE.
 *
 * <p>Construction takes a {@link PlanFn} that, given the live world +
 * acting NPC + the task being run, builds the {@link Plan} (recipe,
 * workstation, batch, fuel, XP, skill). A fulfillment supplies this; the
 * executor owns only
 * the tick-to-tick driving and the result mapping:
 * <ul>
 *   <li>plan can't be built (inputs gone, no workstation) → FAILED;</li>
 *   <li>cycle still running → RUNNING;</li>
 *   <li>cycle reached its terminal phase → DONE.</li>
 * </ul></p>
 *
 * <p>Profession-agnostic by design: a T2 profession reuses this verbatim,
 * passing its own {@code planFn}. The Plan, not this class, carries all
 * profession specifics.</p>
 *
 * <p>G5a — an optional {@link AdapterFactory} overload lets callers supply
 * a custom {@link ContextProductionAdapter} subclass (e.g. the monk's
 * scripture-override adapter) while still driving the same execution loop.</p>
 */
public final class ContextPlanExecutor implements TaskExecutor {

    /** Resolves the {@link Plan} to run from the live world, the acting NPC,
     *  and the task being executed. */
    @FunctionalInterface
    public interface PlanFn {
        Optional<Plan> build(ServerLevel level, TownspersonMob npc, Task task);
    }

    /**
     * G5a — factory for a custom {@link ContextProductionAdapter} whose
     * {@code producedStack} override requires access to live world + NPC.
     * The supplier argument returns the adapter to drive; the adapter's
     * {@code selectPlan} must delegate back to the caller's plan selection.
     */
    @FunctionalInterface
    public interface AdapterFactory {
        ContextProductionAdapter create(Supplier<Optional<Plan>> planSupplier,
                                        ServerLevel level, TownspersonMob npc);
    }

    private final PlanFn         planFn;
    private final AdapterFactory adapterFactory;

    private ContextProductionAdapter adapter;
    private boolean started;

    /** Standard constructor — uses a plain {@link ContextProductionAdapter}. */
    public ContextPlanExecutor(PlanFn planFn) {
        this(planFn, (supplier, level, npc) -> new ContextProductionAdapter(supplier));
    }

    /**
     * G5a — custom-adapter constructor. Callers supply an
     * {@link AdapterFactory} to inject a {@link ContextProductionAdapter}
     * subclass (e.g. one that overrides {@code producedStack} for scripture).
     */
    public ContextPlanExecutor(PlanFn planFn, AdapterFactory adapterFactory) {
        this.planFn         = planFn;
        this.adapterFactory = adapterFactory;
    }

    @Override
    public Result tick(Task task, TaskActor actor, TaskContext ctx) {
        ServerLevel level = ctx.level();
        TownspersonMob npc = ctx.npc().orElse(null);
        if (npc == null) return Result.FAILED;

        long gameTime = level.getGameTime();

        if (!started) {
            // Build the adapter with a one-shot plan supplier that resolves
            // the recipe against current world state at start.
            adapter = adapterFactory.create(
                    () -> planFn.build(level, npc, task), level, npc);
            if (!adapter.runCheckStart(level, npc)) {
                // selectPlan was empty (inputs vanished / no workstation) — the
                // cycle can't begin. FAILED releases the claim so the task can
                // be re-attempted (or its dependency spawned) next pass.
                return Result.FAILED;
            }
            adapter.runStart(level, npc, gameTime);
            started = true;
            return Result.RUNNING;
        }

        if (!adapter.runCanStillUse(level, npc, gameTime)) {
            // Reached DONE phase (or timed out). Stop cleans up walk target /
            // activity label, exactly as the brain would on behavior stop.
            adapter.runStop(level, npc, gameTime);
            return Result.DONE;
        }

        adapter.runTick(level, npc, gameTime);
        return Result.RUNNING;
    }
}
