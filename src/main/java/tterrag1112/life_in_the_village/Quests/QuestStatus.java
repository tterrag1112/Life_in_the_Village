package tterrag1112.life_in_the_village.Quests;

import com.mojang.serialization.Codec;

import java.util.Locale;

/**
 * F2a-1 — a quest's lifecycle state. {@code OFFERED} → {@code ACTIVE} →
 * {@code COMPLETED} / {@code FAILED} / {@code ABANDONED}.
 */
public enum QuestStatus {
    OFFERED, ACTIVE, COMPLETED, FAILED, ABANDONED;

    public static final Codec<QuestStatus> CODEC = Codec.STRING.xmap(
            s -> QuestStatus.valueOf(s.toUpperCase(Locale.ROOT)), QuestStatus::name);

    /** True for a terminal state (no further progress). */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == ABANDONED;
    }
}
