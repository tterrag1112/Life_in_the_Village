package tterrag1112.life_in_the_village.Npc.Relations;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Per-NPC ledger of relationships toward other NPCs (NPC↔NPC, distinct
 * from the Phase 0 player→NPC {@code NpcRelationshipComponent}).
 *
 * <p>Spec: {@code docs/npc_redesign/11-npc-relationship-ledger.md}.
 * Capped at {@link #MAX_ENTRIES}. On overflow, evict the entry with
 * smallest {@code |score|} (closest to neutral); ties broken by
 * oldest {@code lastInteractionTick}.</p>
 *
 * <p>Decay: idle relationships (last interaction &gt; 7 days ago) drift
 * toward neutral at 0.1/day, ramping linearly with idle duration up to
 * a cap of 1.0/day after 37 days. Close friends (|score| ≥ 75) decay at
 * half rate.</p>
 */
public final class NpcRelationshipLedger {

    public static final int MAX_ENTRIES = 15;
    /** Threshold above which proximity-source increments are blocked. */
    public static final int PROXIMITY_CEILING = 20;
    /** Days of idle interaction before decay starts (spec line 173). */
    public static final int DECAY_GRACE_DAYS = 7;
    /** Linear ramp length (days) for full decay rate. */
    public static final int DECAY_RAMP_DAYS = 30;
    /** Spec line 187: close friends decay at half rate. */
    public static final int STICKY_THRESHOLD = 75;

    private static final String SAVE_KEY = "npcRelationships";

    private final Map<UUID, NpcRelationship> entries = new LinkedHashMap<>();

    public NpcRelationshipLedger() {}

    // ── Queries ────────────────────────────────────────────────────────────

    public Optional<NpcRelationship> get(UUID otherId) {
        return Optional.ofNullable(entries.get(otherId));
    }

    public int getScore(UUID otherId) {
        NpcRelationship r = entries.get(otherId);
        return r == null ? 0 : r.score();
    }

    public RelationshipMode getMode(UUID otherId) {
        NpcRelationship r = entries.get(otherId);
        return r == null ? RelationshipMode.NEUTRAL : r.mode();
    }

    public List<NpcRelationship> all() {
        return List.copyOf(entries.values());
    }

    public List<NpcRelationship> friendsAndCloser() {
        return entries.values().stream()
                .filter(r -> r.score() >= 40)
                .toList();
    }

    public List<NpcRelationship> rivalsAndWorse() {
        return entries.values().stream()
                .filter(r -> r.score() <= -10)
                .toList();
    }

    /** Top {@code n} entries by absolute score; used by NpcProfileSnapshot. */
    public List<NpcRelationship> topByMagnitude(int n) {
        return entries.values().stream()
                .sorted(Comparator.comparingInt((NpcRelationship r) -> Math.abs(r.score())).reversed()
                        .thenComparingLong(NpcRelationship::lastInteractionTick).reversed())
                .limit(Math.max(0, n))
                .toList();
    }

    public int size()       { return entries.size(); }
    public boolean isEmpty(){ return entries.isEmpty(); }

    // ── Mutations ─────────────────────────────────────────────────────────

    /**
     * Adjusts the relationship with {@code otherId} by {@code delta}.
     * Creates a new entry if absent, evicting the closest-to-neutral
     * entry on overflow per spec line 102.
     *
     * <p>{@code origin} is honoured only on creation; existing entries
     * keep their original origin (the spec's "where the relationship
     * started" semantic).</p>
     *
     * @return the resulting entry, or {@code null} if the eviction
     *         policy refused to create a new one.
     */
    public NpcRelationship adjust(UUID otherId, int delta, long tick, RelationshipOrigin origin) {
        if (otherId == null) return null;
        NpcRelationship existing = entries.get(otherId);
        if (existing != null) {
            int newScore = clamp(existing.score() + delta);
            NpcRelationship updated = existing.withScoreAndInteraction(newScore, tick);
            entries.put(otherId, updated);
            return updated;
        }
        if (entries.size() >= MAX_ENTRIES) {
            if (!evictOne()) return null;
        }
        NpcRelationship fresh = NpcRelationship.newAt(otherId, clamp(delta), tick,
                origin == null ? RelationshipOrigin.MET_SOCIALLY : origin);
        entries.put(otherId, fresh);
        return fresh;
    }

    /**
     * Proximity-only adjust: same as {@link #adjust} but a no-op when
     * the existing score has already reached
     * {@link #PROXIMITY_CEILING}. Spec line 119: proximity caps at +20.
     */
    public NpcRelationship adjustFromProximity(UUID otherId, int delta, long tick) {
        NpcRelationship existing = entries.get(otherId);
        if (existing != null && existing.score() >= PROXIMITY_CEILING && delta > 0) {
            return existing;
        }
        return adjust(otherId, delta, tick, RelationshipOrigin.MET_SOCIALLY);
    }

    /** Direct write; replaces any existing entry. Used by debug commands and seeding. */
    public NpcRelationship set(UUID otherId, NpcRelationship rel) {
        if (otherId == null || rel == null) return null;
        if (entries.containsKey(otherId)) {
            entries.put(otherId, rel);
            return rel;
        }
        if (entries.size() >= MAX_ENTRIES && !evictOne()) return null;
        entries.put(otherId, rel);
        return rel;
    }

    public boolean remove(UUID otherId) { return entries.remove(otherId) != null; }
    public void clear() { entries.clear(); }

    /**
     * Daily decay per spec line 169. Returns the list of pairs whose
     * {@link RelationshipMode} bucket changed as a result, for
     * downstream boundary-event firing.
     */
    public List<ModeChange> decayAll(float daysElapsed, long currentTick) {
        if (daysElapsed <= 0f) return List.of();
        List<ModeChange> changes = new ArrayList<>();
        for (Map.Entry<UUID, NpcRelationship> e : new ArrayList<>(entries.entrySet())) {
            NpcRelationship r = e.getValue();
            long daysSince = Math.max(0L,
                    Math.floorDiv(currentTick - r.lastInteractionTick(), 24000L));
            if (daysSince <= DECAY_GRACE_DAYS) continue;
            float ramp = Math.min(1f,
                    (daysSince - DECAY_GRACE_DAYS) / (float) DECAY_RAMP_DAYS);
            float ratePerDay = 0.1f * ramp;
            if (Math.abs(r.score()) >= STICKY_THRESHOLD) ratePerDay *= 0.5f;
            float drift = ratePerDay * daysElapsed;
            int oldScore = r.score();
            int newScore = oldScore;
            if (oldScore > 0) {
                newScore = Math.max(0, oldScore - Math.round(drift));
            } else if (oldScore < 0) {
                newScore = Math.min(0, oldScore + Math.round(drift));
            }
            if (newScore != oldScore) {
                NpcRelationship updated = r.withScore(newScore);
                entries.put(e.getKey(), updated);
                RelationshipMode oldMode = RelationshipMode.fromScore(oldScore);
                RelationshipMode newMode = updated.mode();
                if (oldMode != newMode) {
                    changes.add(new ModeChange(e.getKey(), oldMode, newMode));
                }
            }
        }
        return changes;
    }

    /**
     * Eviction picks the entry with the smallest absolute score; ties
     * broken by oldest {@code lastInteractionTick}.
     */
    private boolean evictOne() {
        if (entries.isEmpty()) return false;
        NpcRelationship victim = entries.values().stream()
                .min(Comparator.comparingInt((NpcRelationship r) -> Math.abs(r.score()))
                        .thenComparingLong(NpcRelationship::lastInteractionTick))
                .orElse(null);
        if (victim == null) return false;
        entries.remove(victim.otherId());
        return true;
    }

    private static int clamp(int s) {
        return Math.max(NpcRelationship.MIN_SCORE,
                Math.min(NpcRelationship.MAX_SCORE, s));
    }

    /** Mode-transition signal returned by decay / adjust paths. */
    public record ModeChange(UUID otherId, RelationshipMode from, RelationshipMode to) {}

    // ── Persistence ────────────────────────────────────────────────────────

    public void save(ValueOutput output) {
        output.store(SAVE_KEY, CODEC, this);
    }

    public boolean load(ValueInput input) {
        var read = input.read(SAVE_KEY, CODEC);
        if (read.isEmpty()) return false;
        NpcRelationshipLedger loaded = read.get();
        entries.clear();
        entries.putAll(loaded.entries);
        return true;
    }

    // ── Codec ──────────────────────────────────────────────────────────────

    public static final Codec<NpcRelationshipLedger> CODEC = RecordCodecBuilder.create(i -> i.group(
            NpcRelationship.CODEC.listOf().optionalFieldOf("entries", List.of())
                    .forGetter(l -> List.copyOf(l.entries.values()))
    ).apply(i, NpcRelationshipLedger::fromCodec));

    public static NpcRelationshipLedger fromCodec(List<NpcRelationship> loaded) {
        NpcRelationshipLedger l = new NpcRelationshipLedger();
        for (NpcRelationship r : loaded) {
            if (l.entries.size() >= MAX_ENTRIES) break;
            l.entries.put(r.otherId(), r);
        }
        return l;
    }

    /** Read-only snapshot for tests / debug. */
    public Map<UUID, NpcRelationship> snapshot() {
        return Collections.unmodifiableMap(entries);
    }
}
