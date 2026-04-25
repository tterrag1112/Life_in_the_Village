package tterrag1112.life_in_the_village.Npc.LifeGoal;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Per-NPC life-goal container. Cap of {@link #MAX_ACTIVE} pursued goals
 * concurrently; terminal entries (completed / abandoned / failed) sit in
 * a small {@link #MAX_HISTORY}-cap history list before eviction so
 * dialogue can reference recent outcomes.
 *
 * <p>Spec: {@code docs/npc_redesign/07-life-goals.md} ("LifeGoalSet").</p>
 */
public final class LifeGoalSet {

    public static final int MAX_ACTIVE = 3;
    public static final int MAX_HISTORY = 10;
    /** Entries older than this many days drop out of {@link #history}. */
    public static final int HISTORY_TTL_DAYS = 90;

    private static final String SAVE_KEY = "npcGoals";

    private final List<LifeGoal> active = new ArrayList<>();
    private final List<LifeGoal> history = new ArrayList<>();

    public LifeGoalSet() {}

    // ── Queries ─────────────────────────────────────────────────────────────

    public List<LifeGoal> active() { return Collections.unmodifiableList(active); }
    public List<LifeGoal> history() { return Collections.unmodifiableList(history); }

    public Optional<LifeGoal> primaryGoal() {
        return active.stream().max(Comparator.comparingInt(LifeGoal::importance));
    }

    public boolean has(LifeGoalType type) {
        for (LifeGoal g : active) if (g.type() == type) return true;
        return false;
    }

    public Optional<LifeGoal> getById(UUID goalId) {
        for (LifeGoal g : active) if (g.goalId().equals(goalId)) return Optional.of(g);
        for (LifeGoal g : history) if (g.goalId().equals(goalId)) return Optional.of(g);
        return Optional.empty();
    }

    public Optional<LifeGoal> activeByType(LifeGoalType type) {
        for (LifeGoal g : active) if (g.type() == type) return Optional.of(g);
        return Optional.empty();
    }

    public int activeCount() { return active.size(); }
    public int historyCount() { return history.size(); }
    public boolean isFull() { return active.size() >= MAX_ACTIVE; }

    // ── Mutations ──────────────────────────────────────────────────────────

    /**
     * Adds a goal to {@link #active}. Rejects (returns {@code false}) if
     * the cap is reached or the same goal id / type is already present.
     */
    public boolean add(LifeGoal goal) {
        if (goal == null || goal.status() != GoalStatus.ACTIVE) return false;
        if (active.size() >= MAX_ACTIVE) return false;
        if (active.stream().anyMatch(g -> g.goalId().equals(goal.goalId()))) return false;
        active.add(goal);
        return true;
    }

    public void replace(UUID goalId, LifeGoal updated) {
        for (int i = 0; i < active.size(); i++) {
            if (active.get(i).goalId().equals(goalId)) {
                active.set(i, updated);
                return;
            }
        }
    }

    /** Bumps {@code progressCount} by {@code delta}, clamping to targetCount. */
    public void updateProgress(UUID goalId, int delta) {
        for (int i = 0; i < active.size(); i++) {
            LifeGoal g = active.get(i);
            if (!g.goalId().equals(goalId)) continue;
            active.set(i, g.withProgress(g.progressCount() + delta));
            return;
        }
    }

    /** Sets absolute progress (used by polled goals like SAVE_AMOUNT). */
    public void setProgress(UUID goalId, int newProgress) {
        for (int i = 0; i < active.size(); i++) {
            LifeGoal g = active.get(i);
            if (!g.goalId().equals(goalId)) continue;
            active.set(i, g.withProgress(newProgress));
            return;
        }
    }

    public Optional<LifeGoal> complete(UUID goalId, long tick) {
        return moveToHistory(goalId, GoalStatus.COMPLETED);
    }

    public Optional<LifeGoal> abandon(UUID goalId, long tick) {
        return moveToHistory(goalId, GoalStatus.ABANDONED);
    }

    public Optional<LifeGoal> fail(UUID goalId, long tick) {
        return moveToHistory(goalId, GoalStatus.FAILED);
    }

    private Optional<LifeGoal> moveToHistory(UUID goalId, GoalStatus terminal) {
        for (int i = 0; i < active.size(); i++) {
            LifeGoal g = active.get(i);
            if (!g.goalId().equals(goalId)) continue;
            LifeGoal updated = g.withStatus(terminal);
            active.remove(i);
            history.add(updated);
            while (history.size() > MAX_HISTORY) history.remove(0);
            return Optional.of(updated);
        }
        return Optional.empty();
    }

    /** Drops history entries older than {@link #HISTORY_TTL_DAYS}. */
    public int evictStaleHistory(long currentTick) {
        long cutoff = currentTick - (long) HISTORY_TTL_DAYS * 24000L;
        int before = history.size();
        history.removeIf(g -> g.startTick() < cutoff);
        return before - history.size();
    }

    public void clear() {
        active.clear();
        history.clear();
    }

    // ── Persistence ─────────────────────────────────────────────────────────

    public void save(ValueOutput output) {
        output.store(SAVE_KEY, CODEC, this);
    }

    public boolean load(ValueInput input) {
        var read = input.read(SAVE_KEY, CODEC);
        if (read.isEmpty()) return false;
        LifeGoalSet loaded = read.get();
        active.clear();
        active.addAll(loaded.active);
        history.clear();
        history.addAll(loaded.history);
        return true;
    }

    // ── Codec ──────────────────────────────────────────────────────────────

    public static final Codec<LifeGoalSet> CODEC = RecordCodecBuilder.create(i -> i.group(
            LifeGoal.CODEC.listOf().optionalFieldOf("active", List.of())
                    .forGetter(s -> List.copyOf(s.active)),
            LifeGoal.CODEC.listOf().optionalFieldOf("history", List.of())
                    .forGetter(s -> List.copyOf(s.history))
    ).apply(i, LifeGoalSet::fromCodec));

    public static LifeGoalSet fromCodec(List<LifeGoal> active, List<LifeGoal> history) {
        LifeGoalSet s = new LifeGoalSet();
        for (LifeGoal g : active) {
            if (s.active.size() >= MAX_ACTIVE) break;
            // Defensive: only ACTIVE goals belong in active list.
            if (g.status() == GoalStatus.ACTIVE) s.active.add(g);
            else s.history.add(g);
        }
        for (LifeGoal g : history) {
            if (s.history.size() >= MAX_HISTORY) break;
            s.history.add(g);
        }
        return s;
    }
}
