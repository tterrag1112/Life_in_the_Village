package tterrag1112.life_in_the_village.Village.History;

import com.mojang.serialization.Codec;

import java.util.Locale;

/**
 * Phase 4 doc 30 lines 100-105 — four-tier importance ladder used
 * for {@link HistoryEntry#importance} and the prune retention rules.
 *
 * <p>Retention floors per spec lines 138-145:</p>
 * <ul>
 *   <li>{@link #LEGENDARY} — never pruned.</li>
 *   <li>{@link #MAJOR} — never pruned.</li>
 *   <li>{@link #NOTABLE} — kept for 2 in-game years.</li>
 *   <li>{@link #MINOR} — kept for 1 in-game year, capped at 200
 *   entries by the cap policy.</li>
 * </ul>
 */
public enum HistoryImportance {
    MINOR(0,    365L * 24000L),
    NOTABLE(1,  2L * 365L * 24000L),
    MAJOR(2,    Long.MAX_VALUE),
    LEGENDARY(3, Long.MAX_VALUE);

    private final int rank;
    private final long retentionTicks;

    HistoryImportance(int rank, long retentionTicks) {
        this.rank = rank;
        this.retentionTicks = retentionTicks;
    }

    public int rank() { return rank; }
    public long retentionTicks() { return retentionTicks; }

    public boolean isPrunable() { return retentionTicks < Long.MAX_VALUE; }

    public boolean atLeast(HistoryImportance other) {
        return other == null || rank >= other.rank;
    }

    public static final Codec<HistoryImportance> CODEC = Codec.STRING.xmap(
            s -> HistoryImportance.valueOf(s.toUpperCase(Locale.ROOT)),
            HistoryImportance::name);
}
