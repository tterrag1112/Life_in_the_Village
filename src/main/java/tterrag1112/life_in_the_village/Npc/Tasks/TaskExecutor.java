package tterrag1112.life_in_the_village.Npc.Tasks;

/**
 * Performs a chosen {@link Fulfillment} within the behavior tick. The
 * executor owns the per-tick step machine for actually doing the work
 * (walk, gather, craft, deliver, ...) and reports progress back to the
 * dispatcher each tick.
 *
 * <p>Interface only in T0 — no implementations ship until a profession
 * is migrated onto the Task System (T1+).</p>
 */
public interface TaskExecutor {

    enum Result { RUNNING, DONE, FAILED }

    /** Advance the execution one tick and report the outcome. */
    Result tick(Task task, TaskActor actor, TaskContext ctx);
}
