package tterrag1112.life_in_the_village.Npc.Knowledge;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;

import java.util.Optional;
import java.util.UUID;

/**
 * One fact an NPC knows. Keyed by {@link #topic} — the ledger stores at
 * most one entry per topic, preferring higher-fidelity copies.
 *
 * <p>Fidelity semantics: 1.0 is witnessed / native knowledge, 0.0 is
 * absent / unreadable; rumor retelling drops by 0.2 per hop down to a
 * 0.1 floor. See {@code docs/npc_redesign/03-knowledge-system.md} for
 * the full table.</p>
 *
 * <p>The {@code sensitive} flag is reserved for Phase 2 gossip gating —
 * persisted now (default {@code false}) so producers don't trigger a
 * schema migration when they land.</p>
 */
public record KnowledgeEntry(
        String topic,
        KnowledgeCategory category,
        float fidelity,
        KnowledgeSource source,
        long acquiredTick,
        String content,
        Optional<UUID> sourceId,
        boolean sensitive
) {
    /** Maximum stored length of the {@link #topic} string (spec §topic). */
    public static final int MAX_TOPIC_LENGTH = 100;

    public static final float RELIABLE_THRESHOLD = 0.7f;
    public static final float VAGUE_THRESHOLD    = 0.5f;
    public static final float FIDELITY_FLOOR     = 0.1f;
    /** Drop per retelling hop. */
    public static final float RETELLING_DECAY    = 0.20f;

    /**
     * Canonical factory. Clamps fidelity to {@code [0, 1]} and truncates
     * the topic to {@link #MAX_TOPIC_LENGTH} chars — spec's "too long"
     * edge case.
     */
    public static KnowledgeEntry create(String topic,
                                        KnowledgeCategory category,
                                        float fidelity,
                                        KnowledgeSource source,
                                        long acquiredTick,
                                        String content,
                                        UUID sourceId) {
        return create(topic, category, fidelity, source, acquiredTick, content, sourceId, false);
    }

    public static KnowledgeEntry create(String topic,
                                        KnowledgeCategory category,
                                        float fidelity,
                                        KnowledgeSource source,
                                        long acquiredTick,
                                        String content,
                                        UUID sourceId,
                                        boolean sensitive) {
        String clippedTopic = topic == null ? ""
                : (topic.length() > MAX_TOPIC_LENGTH ? topic.substring(0, MAX_TOPIC_LENGTH) : topic);
        float clampedFidelity = Math.max(0f, Math.min(1f, fidelity));
        return new KnowledgeEntry(
                clippedTopic,
                category,
                clampedFidelity,
                source,
                acquiredTick,
                content == null ? "" : content,
                Optional.ofNullable(sourceId),
                sensitive);
    }

    public boolean isReliable() { return fidelity >= RELIABLE_THRESHOLD; }
    public boolean isVague()    { return fidelity <  VAGUE_THRESHOLD; }

    /** Replace content while preserving everything else (used by mutators). */
    public KnowledgeEntry withContent(String newContent) {
        return new KnowledgeEntry(topic, category, fidelity, source, acquiredTick,
                newContent == null ? "" : newContent, sourceId, sensitive);
    }

    /** Replace fidelity, clamped to {@code [0, 1]}. */
    public KnowledgeEntry withFidelity(float newFidelity) {
        float clamped = Math.max(0f, Math.min(1f, newFidelity));
        return new KnowledgeEntry(topic, category, clamped, source, acquiredTick,
                content, sourceId, sensitive);
    }

    public static final Codec<KnowledgeEntry> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("topic")
                    .forGetter(KnowledgeEntry::topic),
            KnowledgeCategory.CODEC.fieldOf("category")
                    .forGetter(KnowledgeEntry::category),
            Codec.FLOAT.fieldOf("fidelity")
                    .forGetter(KnowledgeEntry::fidelity),
            KnowledgeSource.CODEC.fieldOf("source")
                    .forGetter(KnowledgeEntry::source),
            Codec.LONG.fieldOf("acquiredTick")
                    .forGetter(KnowledgeEntry::acquiredTick),
            Codec.STRING.optionalFieldOf("content", "")
                    .forGetter(KnowledgeEntry::content),
            UUIDUtil.CODEC.optionalFieldOf("sourceId")
                    .forGetter(KnowledgeEntry::sourceId),
            Codec.BOOL.optionalFieldOf("sensitive", false)
                    .forGetter(KnowledgeEntry::sensitive)
    ).apply(i, KnowledgeEntry::new));
}
