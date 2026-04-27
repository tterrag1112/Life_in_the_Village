package tterrag1112.life_in_the_village.Npc.Aging;

import com.mojang.serialization.Codec;
import tterrag1112.life_in_the_village.Npc.LifeGoal.LifeGoalType;

/**
 * Late-life "unfinished business" categories. Spec line 204.
 *
 * <p>Each maps to an existing {@link LifeGoalType} so the unfinished
 * business piggybacks on the life-goal pipeline (priority bumped at
 * the producer to give it weight). Spec line 215 says the business
 * is a "soft life-goal with elevated priority"; the proxy mapping
 * keeps the v1 surface flat.</p>
 */
public enum UnfinishedBusinessType {
    /** Repair a negative relationship before death. Spec line 205. */
    RECONCILIATION(LifeGoalType.BEFRIEND_TARGET),
    /** Visit a specific shrine/temple before death. */
    LAST_PILGRIMAGE(LifeGoalType.PILGRIMAGE),
    /** Ensure inheritance passes to a chosen heir. */
    INHERITANCE_ARRANGEMENT(LifeGoalType.SEE_CHILD_APPRENTICED),
    /** Train one more apprentice to journeyman. */
    APPRENTICE_COMPLETION(LifeGoalType.TEACH_APPRENTICE),
    /** Fulfill a long-standing PROMISED_BY memory. */
    UNKEPT_PROMISE(LifeGoalType.BEFRIEND_TARGET);

    private final LifeGoalType proxyGoal;

    UnfinishedBusinessType(LifeGoalType proxyGoal) {
        this.proxyGoal = proxyGoal;
    }

    public LifeGoalType proxyGoal() { return proxyGoal; }

    public static final Codec<UnfinishedBusinessType> CODEC =
            Codec.STRING.xmap(UnfinishedBusinessType::valueOf, UnfinishedBusinessType::name);
}
