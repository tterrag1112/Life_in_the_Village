package tterrag1112.life_in_the_village.Npc.Tasks;

/**
 * A pluggable strategy for satisfying an {@link Objective}. The
 * dispatcher picks, among the strategies registered for an objective
 * variant, the highest-{@link #score} strategy that {@link #canFulfill}
 * the task for the given actor, then runs its {@link #executor()}.
 *
 * <p>{@link #score} is the skills / finances / input-availability scorer:
 * higher = more preferred (e.g. a high-skill crafter scores its craft
 * strategy above a low-skill one; a buy strategy scores higher when the
 * actor is flush and inputs are unavailable locally).</p>
 *
 * <p>Interface only in T0 — {@link FulfillmentRegistry} is empty and no
 * implementations ship until T1+.</p>
 */
public interface Fulfillment {

    /** Whether this strategy can satisfy {@code task} for {@code actor} right now. */
    boolean canFulfill(Task task, TaskActor actor, TaskContext ctx);

    /** Preference score; higher wins. Only consulted when {@link #canFulfill} is true. */
    double score(Task task, TaskActor actor, TaskContext ctx);

    /** The executor that performs this strategy over subsequent ticks. */
    TaskExecutor executor();
}
