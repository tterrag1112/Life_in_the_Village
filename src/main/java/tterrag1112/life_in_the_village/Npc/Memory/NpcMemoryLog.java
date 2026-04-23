package tterrag1112.life_in_the_village.Npc.Memory;

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
import java.util.function.Predicate;

/**
 * Per-NPC rolling log of situational memories. Capped at {@link #MAX_ENTRIES}
 * entries; on overflow the lowest-{@code currentValue} non-pinned entry is
 * evicted. Pinned memories ({@code initialValue >= 90}) still decay
 * cosmetically but are never evicted.
 *
 * <p>See {@code docs/npc_redesign/02-memory-system.md} for the decay /
 * refresh math, eviction rules, and producer/consumer surfaces planned for
 * later phases.</p>
 */
public final class NpcMemoryLog {

    /** Hard cap on stored entries per NPC (per spec save-size budget). */
    public static final int MAX_ENTRIES = 32;

    /** Root NBT key under which the log serialises on the entity tag. */
    private static final String SAVE_KEY = "npcMemory";

    private final List<NpcMemory> entries = new ArrayList<>();

    public NpcMemoryLog() {}

    // ── Queries ─────────────────────────────────────────────────────────────

    /** Read-only view of all entries. Order is insertion order. */
    public List<NpcMemory> all() {
        return Collections.unmodifiableList(entries);
    }

    public List<NpcMemory> involving(UUID participantId) {
        return entries.stream()
                .filter(m -> m.participantIds().contains(participantId))
                .toList();
    }

    public List<NpcMemory> ofType(MemoryType type) {
        return entries.stream()
                .filter(m -> m.type() == type)
                .toList();
    }

    public List<NpcMemory> matching(Predicate<NpcMemory> pred) {
        return entries.stream().filter(pred).toList();
    }

    public Optional<NpcMemory> findById(UUID memoryId) {
        return entries.stream().filter(m -> m.memoryId().equals(memoryId)).findFirst();
    }

    public boolean hasMemoryOf(MemoryType type, UUID participantId) {
        return entries.stream().anyMatch(m ->
                m.type() == type && m.participantIds().contains(participantId));
    }

    public int size() {
        return entries.size();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    // ── Mutations ──────────────────────────────────────────────────────────

    /**
     * Adds a memory. On overflow, evicts the lowest-{@code currentValue}
     * non-pinned entry per spec section "Eviction on overflow". If all
     * {@link #MAX_ENTRIES} entries are pinned (astronomically rare in
     * practice), the incoming memory is dropped — the spec explicitly
     * sanctions that as the v1 behaviour.
     *
     * @return {@code true} if stored, {@code false} if dropped.
     */
    public boolean add(NpcMemory memory) {
        if (entries.size() >= MAX_ENTRIES) {
            int victim = -1;
            float lowest = Float.POSITIVE_INFINITY;
            for (int i = 0; i < entries.size(); i++) {
                NpcMemory e = entries.get(i);
                if (e.pinned()) continue;
                if (e.currentValue() < lowest) {
                    lowest = e.currentValue();
                    victim = i;
                }
            }
            if (victim < 0) return false; // all pinned — drop new memory
            entries.remove(victim);
        }
        entries.add(memory);
        return true;
    }

    /** Removes the entry with the given id, if any. */
    public boolean remove(UUID memoryId) {
        return entries.removeIf(m -> m.memoryId().equals(memoryId));
    }

    /**
     * Applies a reminder boost to the memory's {@code currentValue}, capped
     * at its {@code initialValue}. Callers pass a raw boost — use
     * {@link #standardReminderBoost} for the spec's default formula, or a
     * flat value (e.g. +3 for a passing encounter). No-op if the memory is
     * absent. See spec section "Refresh on reminder".
     */
    public void refresh(UUID memoryId, float boost) {
        for (int i = 0; i < entries.size(); i++) {
            NpcMemory m = entries.get(i);
            if (!m.memoryId().equals(memoryId)) continue;
            entries.set(i, m.withCurrentValue(m.currentValue() + boost));
            return;
        }
    }

    /**
     * Spec's canonical refresh formula:
     * {@code 10 + 0.2 * (initialValue - currentValue)}. Higher the decay,
     * larger the rebound. Producers compute this and pass it to
     * {@link #refresh}.
     */
    public static float standardReminderBoost(NpcMemory memory) {
        return 10f + 0.2f * (memory.initialValue() - memory.currentValue());
    }

    /**
     * Advances decay by {@code daysElapsed} in-game days. Per spec:
     * {@code decayPerDay = 0.02 * (100 - currentValue) / 100}. Pinned
     * entries decay too (cosmetic drift), they're just immune from
     * eviction elsewhere.
     */
    public void decayAll(float daysElapsed) {
        if (daysElapsed <= 0f) return;
        for (int i = 0; i < entries.size(); i++) {
            NpcMemory m = entries.get(i);
            float decayed = m.currentValue();
            // Integrate day-by-day when the step is large; one step is fine
            // for the common case of 1 day.
            for (int d = 0; d < (int) Math.ceil(daysElapsed); d++) {
                float step = Math.min(1f, daysElapsed - d);
                decayed -= 0.02f * (100f - decayed) / 100f * step;
                if (decayed <= 0f) { decayed = 0f; break; }
            }
            entries.set(i, m.withCurrentValue(decayed));
        }
    }

    /**
     * Drops every entry that is now {@link NpcMemory#evictable()}. Called
     * after {@link #decayAll} on the daily tick.
     */
    public int removeExpired() {
        int before = entries.size();
        entries.removeIf(NpcMemory::evictable);
        return before - entries.size();
    }

    /** Clears all entries. For debug use only. */
    public void clear() {
        entries.clear();
    }

    // ── Persistence ─────────────────────────────────────────────────────────

    public void save(ValueOutput output) {
        output.store(SAVE_KEY, CODEC, this);
    }

    /**
     * Reads an {@code npcMemory} subtree into this log, replacing existing
     * entries. Missing subtree is fine — logs default to empty.
     */
    public void load(ValueInput input) {
        input.read(SAVE_KEY, CODEC).ifPresent(loaded -> {
            entries.clear();
            entries.addAll(loaded.entries);
        });
    }

    // ── Codec (also usable from saved-data contexts) ────────────────────────

    public static final Codec<NpcMemoryLog> CODEC = RecordCodecBuilder.create(i -> i.group(
            NpcMemory.CODEC.listOf().optionalFieldOf("entries", List.of())
                    .forGetter(log -> log.entries)
    ).apply(i, NpcMemoryLog::fromEntries));

    /** Codec reconstruction factory. Trims over-cap lists defensively. */
    public static NpcMemoryLog fromEntries(List<NpcMemory> loaded) {
        NpcMemoryLog log = new NpcMemoryLog();
        for (NpcMemory m : loaded) {
            if (log.entries.size() >= MAX_ENTRIES) break;
            log.entries.add(m);
        }
        return log;
    }

    // ── Debug helpers ──────────────────────────────────────────────────────

    /** Returns entries sorted by currentValue descending — handy for display. */
    public List<NpcMemory> byCurrentValueDesc() {
        return entries.stream()
                .sorted(Comparator.comparingDouble(NpcMemory::currentValue).reversed())
                .toList();
    }
}
