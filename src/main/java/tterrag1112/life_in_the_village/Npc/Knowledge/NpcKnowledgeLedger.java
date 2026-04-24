package tterrag1112.life_in_the_village.Npc.Knowledge;

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
 * Per-NPC knowledge ledger. One entry per topic; {@link #add} enforces the
 * "higher fidelity wins" upgrade rule. Overflow past {@link #MAX_ENTRIES}
 * evicts the lowest-fidelity, oldest entry (fidelity-1.0 entries are
 * protected unless every slot is 1.0 — see spec).
 *
 * <p>No decay in this ledger: knowledge does not fade with time. Fidelity
 * only drops on retelling, which is a Phase 2 concern
 * ({@link RumorMutator}, {@link #degradeForRetelling}).</p>
 *
 * <p>See {@code docs/npc_redesign/03-knowledge-system.md}.</p>
 */
public final class NpcKnowledgeLedger {

    public static final int MAX_ENTRIES = 64;
    /** Fidelity at or above which an entry is protected from eviction. */
    public static final float PINNED_FIDELITY = 1.0f;
    private static final String SAVE_KEY = "npcKnowledge";

    /** LinkedHashMap preserves insertion order for stable iteration/serialisation. */
    private final Map<String, KnowledgeEntry> entries = new LinkedHashMap<>();

    public NpcKnowledgeLedger() {}

    // ── Queries ─────────────────────────────────────────────────────────────

    public Optional<KnowledgeEntry> get(String topic) {
        return Optional.ofNullable(entries.get(topic));
    }

    public boolean knows(String topic) {
        return entries.containsKey(topic);
    }

    public boolean knowsReliably(String topic) {
        KnowledgeEntry e = entries.get(topic);
        return e != null && e.isReliable();
    }

    public List<KnowledgeEntry> byCategory(KnowledgeCategory category) {
        return entries.values().stream()
                .filter(e -> e.category() == category)
                .toList();
    }

    public List<KnowledgeEntry> bySource(KnowledgeSource source) {
        return entries.values().stream()
                .filter(e -> e.source() == source)
                .toList();
    }

    public List<KnowledgeEntry> recentlyAcquired(long sinceTick) {
        return entries.values().stream()
                .filter(e -> e.acquiredTick() >= sinceTick)
                .toList();
    }

    public List<KnowledgeEntry> all() {
        return List.copyOf(entries.values());
    }

    public int size() {
        return entries.size();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    // ── Mutations ──────────────────────────────────────────────────────────

    /**
     * Adds or upgrades an entry. Rules (per spec "Edge cases"):
     * <ul>
     *   <li>If an entry exists at the same topic with {@code >=} fidelity,
     *       the incoming entry is dropped.</li>
     *   <li>Equal fidelity: the more recent {@code acquiredTick} wins.</li>
     *   <li>If new topic and log is full: evict the lowest-fidelity
     *       non-pinned entry (oldest on tie). If every entry is fidelity
     *       1.0, fall back to evicting the oldest overall.</li>
     * </ul>
     *
     * @return {@code true} if stored, {@code false} if the incoming entry
     *         was dropped as weaker-than-existing.
     */
    public boolean add(KnowledgeEntry entry) {
        KnowledgeEntry existing = entries.get(entry.topic());
        if (existing != null) {
            if (entry.fidelity() > existing.fidelity()) {
                entries.put(entry.topic(), entry);
                return true;
            }
            if (entry.fidelity() == existing.fidelity()
                    && entry.acquiredTick() > existing.acquiredTick()) {
                entries.put(entry.topic(), entry);
                return true;
            }
            return false;
        }
        if (entries.size() >= MAX_ENTRIES) {
            if (!evictOne()) return false; // astronomically rare; drop per spec fallback
        }
        entries.put(entry.topic(), entry);
        return true;
    }

    /**
     * Spec-style {@code update}: replaces the entry at {@code entry.topic()}
     * with a higher-fidelity version. No-op if the topic is absent or the
     * incoming fidelity isn't strictly higher.
     */
    public boolean update(KnowledgeEntry entry) {
        KnowledgeEntry existing = entries.get(entry.topic());
        if (existing == null) return false;
        if (entry.fidelity() <= existing.fidelity()) return false;
        entries.put(entry.topic(), entry);
        return true;
    }

    public boolean remove(String topic) {
        return entries.remove(topic) != null;
    }

    public void clear() {
        entries.clear();
    }

    /**
     * Picks the victim for an overflow eviction and removes it. Returns
     * {@code true} on success. Fails only when the map is empty (caller
     * invariant) — the all-pinned case falls back to oldest overall.
     */
    private boolean evictOne() {
        if (entries.isEmpty()) return false;
        // Candidate set: non-pinned entries first; fall back to all entries.
        List<KnowledgeEntry> candidates = entries.values().stream()
                .filter(e -> e.fidelity() < PINNED_FIDELITY)
                .toList();
        if (candidates.isEmpty()) candidates = new ArrayList<>(entries.values());
        KnowledgeEntry victim = candidates.stream()
                .min(Comparator.comparingDouble(KnowledgeEntry::fidelity)
                        .thenComparingLong(KnowledgeEntry::acquiredTick))
                .orElse(null);
        if (victim == null) return false;
        entries.remove(victim.topic());
        return true;
    }

    /**
     * Produces the retold copy of {@code topic} — fidelity dropped by
     * {@link KnowledgeEntry#RETELLING_DECAY} (floored at
     * {@link KnowledgeEntry#FIDELITY_FLOOR}), content mutated via
     * {@link RumorMutator} using the stable seed formula, and the source
     * is promoted to {@link KnowledgeSource#RUMOR_RETOLD}. Does not mutate
     * this ledger; caller stores the result in the receiver's ledger.
     *
     * <p>Called by Phase 2 gossip exchange; unused in Phase 0.</p>
     *
     * @param topic        topic to retell
     * @param mutatorUuid  UUID of the retelling NPC (seeds the mutation)
     * @return the retold entry, or {@link Optional#empty()} if unknown
     */
    public Optional<KnowledgeEntry> degradeForRetelling(String topic, UUID mutatorUuid) {
        KnowledgeEntry source = entries.get(topic);
        if (source == null) return Optional.empty();
        float newFidelity = Math.max(KnowledgeEntry.FIDELITY_FLOOR,
                source.fidelity() - KnowledgeEntry.RETELLING_DECAY);
        long seed = RumorMutator.deriveSeed(topic, source.acquiredTick(), mutatorUuid);
        String mutated = RumorMutator.mutate(source.content(), newFidelity, seed);
        KnowledgeEntry retold = new KnowledgeEntry(
                source.topic(),
                source.category(),
                newFidelity,
                KnowledgeSource.RUMOR_RETOLD,
                source.acquiredTick(),
                mutated,
                source.sourceId(),
                source.sensitive());
        return Optional.of(retold);
    }

    // ── Persistence ─────────────────────────────────────────────────────────

    public void save(ValueOutput output) {
        output.store(SAVE_KEY, CODEC, this);
    }

    public void load(ValueInput input) {
        input.read(SAVE_KEY, CODEC).ifPresent(loaded -> {
            entries.clear();
            entries.putAll(loaded.entries);
        });
    }

    // ── Codec ──────────────────────────────────────────────────────────────

    public static final Codec<NpcKnowledgeLedger> CODEC = RecordCodecBuilder.create(i -> i.group(
            KnowledgeEntry.CODEC.listOf().optionalFieldOf("entries", List.of())
                    .forGetter(ledger -> List.copyOf(ledger.entries.values()))
    ).apply(i, NpcKnowledgeLedger::fromEntries));

    /** Defensive factory used by the codec. Drops over-cap and duplicate topics. */
    public static NpcKnowledgeLedger fromEntries(List<KnowledgeEntry> loaded) {
        NpcKnowledgeLedger ledger = new NpcKnowledgeLedger();
        for (KnowledgeEntry e : loaded) {
            if (ledger.entries.size() >= MAX_ENTRIES) break;
            // putIfAbsent — first occurrence wins on duplicate topics.
            ledger.entries.putIfAbsent(e.topic(), e);
        }
        return ledger;
    }

    /** Debug helper: entries grouped by category, each group sorted by fidelity desc. */
    public Map<KnowledgeCategory, List<KnowledgeEntry>> groupedByCategory() {
        Map<KnowledgeCategory, List<KnowledgeEntry>> out = new LinkedHashMap<>();
        for (KnowledgeCategory c : KnowledgeCategory.values()) out.put(c, new ArrayList<>());
        for (KnowledgeEntry e : entries.values()) {
            out.get(e.category()).add(e);
        }
        for (List<KnowledgeEntry> list : out.values()) {
            list.sort(Comparator.comparingDouble(KnowledgeEntry::fidelity).reversed());
        }
        return Collections.unmodifiableMap(out);
    }
}
