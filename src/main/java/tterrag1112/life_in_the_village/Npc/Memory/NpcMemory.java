package tterrag1112.life_in_the_village.Npc.Memory;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;

import java.util.List;
import java.util.UUID;

/**
 * One immutable memory entry in an NPC's {@link NpcMemoryLog}. Records an
 * event with its participants, timing, importance, and a summary string;
 * the log handles decay/refresh/eviction around it.
 *
 * <p>All mutation (decay, refresh) produces a new record via
 * {@link #withCurrentValue} — the log replaces the old entry in its list.
 * See {@code docs/npc_redesign/02-memory-system.md} for field semantics
 * and initial-value defaults per {@link MemoryType}.</p>
 */
public record NpcMemory(
        UUID memoryId,
        MemoryType type,
        List<UUID> participantIds,
        long tick,
        int initialValue,
        float currentValue,
        boolean pinned,
        String summary
) {
    /** Initial value at or above which a memory is auto-pinned from eviction. */
    public static final int PINNING_THRESHOLD = 90;
    /** A memory is evictable once it decays below this {@code currentValue}. */
    public static final float EVICT_THRESHOLD = 5f;

    /**
     * Canonical factory used by producers. Derives {@code pinned} from
     * {@code initialValue} per spec and seeds {@code currentValue} to the
     * initial. Generates a fresh {@code memoryId}.
     */
    public static NpcMemory create(MemoryType type,
                                   List<UUID> participantIds,
                                   long tick,
                                   int initialValue,
                                   String summary) {
        int clamped = Math.max(1, Math.min(100, initialValue));
        boolean autoPinned = clamped >= PINNING_THRESHOLD;
        return new NpcMemory(
                UUID.randomUUID(),
                type,
                List.copyOf(participantIds),
                tick,
                clamped,
                clamped,
                autoPinned,
                summary == null ? "" : summary);
    }

    /** True when the memory has decayed below the eviction threshold and isn't pinned. */
    public boolean evictable() {
        return !pinned && currentValue < EVICT_THRESHOLD;
    }

    /** Non-negative polarity (see {@link MemoryType#polarity}). */
    public boolean isPositive() {
        return type.polarity() >= 0;
    }

    /** Returns a copy with the given currentValue; clamped to [0, initialValue]. */
    public NpcMemory withCurrentValue(float newValue) {
        float clamped = Math.max(0f, Math.min(initialValue, newValue));
        return new NpcMemory(memoryId, type, participantIds, tick,
                initialValue, clamped, pinned, summary);
    }

    public static final Codec<NpcMemory> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("id")
                    .forGetter(NpcMemory::memoryId),
            MemoryType.CODEC.fieldOf("type")
                    .forGetter(NpcMemory::type),
            UUIDUtil.CODEC.listOf().optionalFieldOf("participants", List.of())
                    .forGetter(NpcMemory::participantIds),
            Codec.LONG.fieldOf("tick")
                    .forGetter(NpcMemory::tick),
            Codec.INT.fieldOf("initialValue")
                    .forGetter(NpcMemory::initialValue),
            Codec.FLOAT.fieldOf("currentValue")
                    .forGetter(NpcMemory::currentValue),
            Codec.BOOL.optionalFieldOf("pinned", false)
                    .forGetter(NpcMemory::pinned),
            Codec.STRING.optionalFieldOf("summary", "")
                    .forGetter(NpcMemory::summary)
    ).apply(i, NpcMemory::new));
}
