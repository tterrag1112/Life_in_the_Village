package tterrag1112.life_in_the_village.Npc.LifeGoal;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;

import java.util.UUID;

/**
 * One concrete life goal carried by an NPC. Spec
 * {@code docs/npc_redesign/07-life-goals.md} (section "LifeGoal record").
 *
 * <p>{@link #targetParam} is type-specific — the slug of an item, the
 * stringified UUID of a target NPC, the office id, etc. Goals that count
 * toward {@link #targetCount} (SAVE_AMOUNT, BEFRIEND_COUNT,
 * VISIT_VILLAGES, ...) tick {@link #progressCount} via subsystem hooks.
 * Goals that complete on a binary world-state condition leave
 * {@code targetCount = 1} and flip {@code progressCount} to 1 on
 * detection.</p>
 */
public record LifeGoal(
        UUID goalId,
        LifeGoalType type,
        long startTick,
        long targetTick,
        String targetParam,
        int targetCount,
        int progressCount,
        GoalStatus status,
        int importance,
        String narrative
) {

    public static LifeGoal newActive(LifeGoalType type, long startTick,
                                     String targetParam, int targetCount,
                                     int importance, String narrative) {
        return new LifeGoal(UUID.randomUUID(), type, startTick, 0L,
                targetParam == null ? "" : targetParam,
                Math.max(1, targetCount),
                0,
                GoalStatus.ACTIVE,
                Math.max(1, Math.min(10, importance)),
                narrative == null ? "" : narrative);
    }

    public float progressFraction() {
        if (status == GoalStatus.COMPLETED) return 1f;
        if (targetCount <= 0) return 0f;
        return Math.min(1f, (float) progressCount / (float) targetCount);
    }

    public boolean isExpired(long now) {
        return targetTick > 0L && now > targetTick;
    }

    public LifeGoal withProgress(int newProgress) {
        int clamped = Math.max(0, Math.min(targetCount, newProgress));
        return new LifeGoal(goalId, type, startTick, targetTick, targetParam,
                targetCount, clamped, status, importance, narrative);
    }

    public LifeGoal withStatus(GoalStatus newStatus) {
        return new LifeGoal(goalId, type, startTick, targetTick, targetParam,
                targetCount, progressCount, newStatus, importance, narrative);
    }

    public static final Codec<LifeGoal> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("goalId").forGetter(LifeGoal::goalId),
            LifeGoalType.CODEC.fieldOf("type").forGetter(LifeGoal::type),
            Codec.LONG.fieldOf("startTick").forGetter(LifeGoal::startTick),
            Codec.LONG.optionalFieldOf("targetTick", 0L).forGetter(LifeGoal::targetTick),
            Codec.STRING.optionalFieldOf("targetParam", "").forGetter(LifeGoal::targetParam),
            Codec.INT.optionalFieldOf("targetCount", 1).forGetter(LifeGoal::targetCount),
            Codec.INT.optionalFieldOf("progressCount", 0).forGetter(LifeGoal::progressCount),
            GoalStatus.CODEC.optionalFieldOf("status", GoalStatus.ACTIVE).forGetter(LifeGoal::status),
            Codec.INT.optionalFieldOf("importance", 5).forGetter(LifeGoal::importance),
            Codec.STRING.optionalFieldOf("narrative", "").forGetter(LifeGoal::narrative)
    ).apply(i, LifeGoal::new));
}
