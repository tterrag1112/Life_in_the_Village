package tterrag1112.life_in_the_village.Npc.Tasks;

/**
 * Implemented by a level (or a level-attached system) that generates and
 * refreshes its own {@link Task}s onto its {@link TaskBoard} — e.g. a
 * business turning a stock shortfall into a {@code MaintainStock} task,
 * or a village turning a CRITICAL need into a CRITICAL task.
 *
 * <p>Interface only in T0 — no implementations ship until a level is
 * migrated onto the Task System (T1+).</p>
 */
public interface TaskSource {

    /** Generate / refresh this source's tasks using the supplied context. */
    void generate(TaskContext ctx);
}
