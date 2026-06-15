package tterrag1112.life_in_the_village.Npc.Tasks;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The set of {@link Task}s belonging to one level entity (one
 * {@link IssuerRef}). NPCs pull a filtered, ranked slice via
 * {@link #rankedEligibleFor}.
 *
 * <p>The board's owner is the {@code IssuerRef} it is keyed under in
 * {@link TaskSavedData}; the board itself stores only its tasks (keyed
 * by {@link TaskId}). On load the owning ref is re-supplied by the
 * SavedData, so it isn't persisted redundantly here.</p>
 */
public final class TaskBoard {

    private final Map<TaskId, Task> tasks = new LinkedHashMap<>();

    public TaskBoard() {}

    private TaskBoard(List<Task> initial) {
        for (Task t : initial) tasks.put(t.id(), t);
    }

    // ── CRUD ─────────────────────────────────────────────────────────────────

    public void add(Task task) {
        tasks.put(task.id(), task);
    }

    public boolean remove(TaskId id) {
        return tasks.remove(id) != null;
    }

    public Optional<Task> get(TaskId id) {
        return Optional.ofNullable(tasks.get(id));
    }

    public List<Task> all() {
        return List.copyOf(tasks.values());
    }

    public boolean isEmpty() {
        return tasks.isEmpty();
    }

    // ── Ranked view ────────────────────────────────────────────────────────────

    /**
     * Tasks on this board that {@code actor} is eligible for and that are
     * still claimable, ordered by tier (high first), then urgency (high
     * first), then deadline (sooner first; deadline {@code 0} = never,
     * sorted last).
     */
    public List<Task> rankedEligibleFor(TaskActor actor, TaskContext ctx) {
        List<Task> out = new ArrayList<>();
        for (Task t : tasks.values()) {
            if (!t.assignment().isClaimable()) continue;
            if (!t.filter().eligible(actor, ctx)) continue;
            out.add(t);
        }
        out.sort(RANKING);
        return out;
    }

    /** Tier desc, urgency desc, deadline asc (0 = no deadline sorts last). */
    public static final Comparator<Task> RANKING =
            Comparator.comparingInt((Task t) -> t.priority().tier().weight()).reversed()
                    .thenComparing(Comparator.comparingDouble((Task t) -> t.priority().urgency()).reversed())
                    .thenComparingLong(t -> t.deadlineTick() == 0L ? Long.MAX_VALUE : t.deadlineTick());

    // ── Persistence ────────────────────────────────────────────────────────────

    public static final Codec<TaskBoard> CODEC = RecordCodecBuilder.create(i -> i.group(
            Task.CODEC.listOf().optionalFieldOf("tasks", List.of())
                    .forGetter(b -> new ArrayList<>(b.tasks.values()))
    ).apply(i, TaskBoard::new));
}
