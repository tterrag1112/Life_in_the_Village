package tterrag1112.life_in_the_village.Npc.Tasks;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Dedicated world-level store for all {@link TaskBoard}s, one per
 * {@link IssuerRef}. Standalone {@code SavedData} (NOT an extension of
 * {@code VillageSavedData}) so the Task System owns its own persistence
 * file and lifecycle.
 *
 * <p>Boards are keyed by {@link IssuerRef#key()} for stable string map
 * storage. Mutating a board through {@link #board(IssuerRef)} does not
 * auto-mark dirty (the board is mutated in place); callers that change
 * board contents must call {@link #markChanged()} — or use
 * {@link #addTask}/{@link #removeTask} which mark for you.</p>
 */
public final class TaskSavedData extends SavedData {

    public static final SavedDataType<TaskSavedData> TYPE = new SavedDataType<>(
            "litv_tasks",
            TaskSavedData::new,
            RecordCodecBuilder.create(i -> i.group(
                    Codec.unboundedMap(Codec.STRING, TaskBoard.CODEC)
                            .optionalFieldOf("boards", Map.of())
                            .forGetter(d -> d.boards)
            ).apply(i, TaskSavedData::fromCodec))
    );

    private static TaskSavedData fromCodec(Map<String, TaskBoard> loaded) {
        TaskSavedData d = new TaskSavedData();
        d.boards.putAll(loaded);
        return d;
    }

    private final Map<String, TaskBoard> boards = new LinkedHashMap<>();

    public TaskSavedData() {}

    public static TaskSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    // ── Board access ───────────────────────────────────────────────────────────

    /** The board for {@code ref}, creating an empty one if absent. */
    public TaskBoard board(IssuerRef ref) {
        return boards.computeIfAbsent(ref.key(), k -> new TaskBoard());
    }

    /** The board for {@code ref} if it already exists; empty otherwise. */
    public Optional<TaskBoard> boardIfPresent(IssuerRef ref) {
        return Optional.ofNullable(boards.get(ref.key()));
    }

    /** Snapshot of the issuer refs that currently have a board. */
    public List<IssuerRef> issuers() {
        return boards.keySet().stream().map(IssuerRef::parseKey).toList();
    }

    // ── Convenience mutators (mark dirty for you) ────────────────────────────────

    public void addTask(IssuerRef ref, Task task) {
        board(ref).add(task);
        setDirty();
    }

    public boolean removeTask(IssuerRef ref, TaskId id) {
        boolean removed = boardIfPresent(ref).map(b -> b.remove(id)).orElse(false);
        if (removed) setDirty();
        return removed;
    }

    /** Explicit dirty mark for callers that mutate a board in place. */
    public void markChanged() {
        setDirty();
    }
}
