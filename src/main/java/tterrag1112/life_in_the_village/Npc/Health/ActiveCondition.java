package tterrag1112.life_in_the_village.Npc.Health;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;

import java.util.Optional;
import java.util.UUID;

/**
 * Spec line 41. One condition currently active on an NPC.
 *
 * @param type                  what is wrong
 * @param onsetTick             game tick the condition started
 * @param expectedDurationTicks how long it would take to resolve if
 *                              left untreated; treatment cuts this in
 *                              half (spec line 112)
 * @param severity              1..5; 1 trivial, 5 life-threatening
 * @param treated               true when a healer has applied a remedy
 *                              within this run of the condition
 * @param treatedById           healer's UUID, when treated
 */
public record ActiveCondition(
        HealthCondition type,
        long onsetTick,
        long expectedDurationTicks,
        int severity,
        boolean treated,
        Optional<UUID> treatedById
) {
    public ActiveCondition {
        if (type == null) throw new IllegalArgumentException("type required");
        severity = Math.max(1, Math.min(5, severity));
        if (expectedDurationTicks < 0L) expectedDurationTicks = 0L;
        if (treatedById == null) treatedById = Optional.empty();
    }

    /** Returns the tick at which the condition should naturally heal,
     *  taking the treated halving into account (spec line 112). */
    public long resolutionTick() {
        long span = treated
                ? Math.max(1L, expectedDurationTicks / 2L)
                : expectedDurationTicks;
        return onsetTick + span;
    }

    public boolean isResolved(long currentTick) {
        return currentTick >= resolutionTick();
    }

    public ActiveCondition markTreated(UUID healerId, long treatTick) {
        long remaining = Math.max(0L, expectedDurationTicks - (treatTick - onsetTick));
        return new ActiveCondition(type, onsetTick, remaining, severity,
                true, Optional.ofNullable(healerId));
    }

    public ActiveCondition withSeverity(int newSeverity) {
        return new ActiveCondition(type, onsetTick, expectedDurationTicks,
                newSeverity, treated, treatedById);
    }

    public static final Codec<ActiveCondition> CODEC = RecordCodecBuilder.create(i -> i.group(
            HealthCondition.CODEC.fieldOf("type").forGetter(ActiveCondition::type),
            Codec.LONG.fieldOf("onsetTick").forGetter(ActiveCondition::onsetTick),
            Codec.LONG.fieldOf("expectedDurationTicks").forGetter(ActiveCondition::expectedDurationTicks),
            Codec.INT.optionalFieldOf("severity", 1).forGetter(ActiveCondition::severity),
            Codec.BOOL.optionalFieldOf("treated", false).forGetter(ActiveCondition::treated),
            UUIDUtil.CODEC.optionalFieldOf("treatedById").forGetter(ActiveCondition::treatedById)
    ).apply(i, ActiveCondition::new));
}
