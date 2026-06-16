package tterrag1112.life_in_the_village.Npc.Tasks.Execution;

import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Homestead.ContextProductionBehavior;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * T1 — a thin {@link ContextProductionBehavior} whose {@link #selectPlan}
 * returns a caller-supplied, already-chosen {@link Plan}. It exists so a
 * {@code TaskExecutor} can drive the EXISTING production state machine
 * (walk → produce → consume inputs + fuel → deposit output → award XP)
 * for a recipe the Task System's fulfillment already picked — without
 * re-implementing any of that machinery, and without going through a
 * profession context's own {@code selectPlan} (which would re-run the
 * legacy selection heuristics).
 *
 * <p>The adapter overrides only what is needed to be driven externally:
 * <ul>
 *   <li>{@link #checkContextGate} returns true — the executor has already
 *       gated (profession migrated, work-time handled by the dispatcher's
 *       WORK-activity membership).</li>
 *   <li>{@link #selectPlan} returns the fixed plan when set, else empty.</li>
 * </ul>
 * Buy / vending hooks stay at their no-op defaults: procurement and
 * selling are modeled as separate Task-System fulfillments (Smelt / Buy /
 * the legacy vending path), not folded into the produce executor. This
 * keeps the adapter generic enough for any T2 profession to reuse: hand
 * it a {@link Plan} and tick it.</p>
 *
 * <p><b>Not a brain behavior.</b> This subclass is never added to a Brain;
 * it is instantiated by a {@code TaskExecutor} and its lifecycle methods
 * are called directly. {@code Behavior}'s lifecycle methods are
 * {@code protected}, so this adapter exposes package-visible run hooks
 * (used by {@link ContextPlanExecutor} in the same package).</p>
 */
public class ContextProductionAdapter extends ContextProductionBehavior {

    private final Supplier<Optional<Plan>> planSupplier;

    public ContextProductionAdapter(Supplier<Optional<Plan>> planSupplier) {
        this.planSupplier = planSupplier;
    }

    @Override
    protected Optional<Plan> selectPlan(ServerLevel level, TownspersonMob entity) {
        return planSupplier.get();
    }

    @Override
    protected boolean checkContextGate(ServerLevel level, TownspersonMob entity) {
        // The Task-System dispatcher already established eligibility and the
        // WORK activity owns the schedule gate; no extra gate here.
        return true;
    }

    // ── Package-visible bridges to Behavior's protected lifecycle ────────────
    // (ContextPlanExecutor lives in this package and calls these.)

    boolean runCheckStart(ServerLevel level, TownspersonMob entity) {
        return checkExtraStartConditions(level, entity);
    }

    void runStart(ServerLevel level, TownspersonMob entity, long gameTime) {
        start(level, entity, gameTime);
    }

    void runTick(ServerLevel level, TownspersonMob entity, long gameTime) {
        tick(level, entity, gameTime);
    }

    boolean runCanStillUse(ServerLevel level, TownspersonMob entity, long gameTime) {
        return canStillUse(level, entity, gameTime);
    }

    void runStop(ServerLevel level, TownspersonMob entity, long gameTime) {
        stop(level, entity, gameTime);
    }
}
