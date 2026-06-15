package tterrag1112.life_in_the_village.Npc.Tasks;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * The core Task object: a desired {@link Objective} owned by an
 * {@link IssuerRef}, with a {@link Priority}, an eligibility
 * {@link TaskFilter}, a mutable claim-lifecycle {@link Assignment},
 * optional dependencies, an optional deadline, and an optional
 * decomposition parent.
 *
 * <p>A class (not a record) because {@link Assignment} mutates in place
 * over the task's life. Identity is {@link #id}; equality/hashing key on
 * the id so a task stays the same entry in a {@link TaskBoard} across
 * assignment mutations.</p>
 */
public final class Task {

    private final TaskId id;
    private final IssuerRef issuer;
    private final Objective objective;
    private Priority priority;
    private final TaskFilter filter;
    private final Assignment assignment;
    private final List<TaskId> dependencies;
    /** Absolute game-tick deadline; {@code 0} = no deadline. */
    private long deadlineTick;
    /** Provenance: the task this was decomposed from, if any. */
    @Nullable private final TaskId parent;

    public Task(TaskId id, IssuerRef issuer, Objective objective, Priority priority,
                TaskFilter filter, Assignment assignment, List<TaskId> dependencies,
                long deadlineTick, @Nullable TaskId parent) {
        this.id = id;
        this.issuer = issuer;
        this.objective = objective;
        this.priority = priority;
        this.filter = filter;
        this.assignment = assignment;
        this.dependencies = new ArrayList<>(dependencies);
        this.deadlineTick = deadlineTick;
        this.parent = parent;
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public TaskId id()             { return id; }
    public IssuerRef issuer()      { return issuer; }
    public Objective objective()   { return objective; }
    public Priority priority()     { return priority; }
    public TaskFilter filter()     { return filter; }
    public Assignment assignment() { return assignment; }
    public List<TaskId> dependencies() { return Collections.unmodifiableList(dependencies); }
    public long deadlineTick()     { return deadlineTick; }
    public boolean hasDeadline()   { return deadlineTick > 0L; }
    public Optional<TaskId> parent() { return Optional.ofNullable(parent); }

    // ── Mutators (the few fields that legitimately change post-creation) ──────

    public void setPriority(Priority priority) { this.priority = priority; }
    public void setDeadlineTick(long tick)     { this.deadlineTick = tick; }

    // ── Persistence ───────────────────────────────────────────────────────────
    // 9 grouped fields — well under the 16-field RecordCodecBuilder ceiling.

    public static final Codec<Task> CODEC = RecordCodecBuilder.create(i -> i.group(
            TaskId.CODEC.fieldOf("id").forGetter(Task::id),
            IssuerRef.CODEC.fieldOf("issuer").forGetter(Task::issuer),
            Objective.CODEC.fieldOf("objective").forGetter(Task::objective),
            Priority.CODEC.fieldOf("priority").forGetter(Task::priority),
            TaskFilter.CODEC.optionalFieldOf("filter", TaskFilter.ANY).forGetter(Task::filter),
            Assignment.CODEC.optionalFieldOf("assignment", new Assignment()).forGetter(Task::assignment),
            TaskId.CODEC.listOf().optionalFieldOf("dependencies", List.of()).forGetter(Task::dependencies),
            Codec.LONG.optionalFieldOf("deadlineTick", 0L).forGetter(Task::deadlineTick),
            TaskId.CODEC.optionalFieldOf("parent").forGetter(Task::parent)
    ).apply(i, (id, issuer, objective, priority, filter, assignment, deps, deadline, parent) ->
            new Task(id, issuer, objective, priority, filter, assignment, deps, deadline, parent.orElse(null))));

    @Override
    public boolean equals(Object o) {
        return o instanceof Task t && t.id.equals(id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
