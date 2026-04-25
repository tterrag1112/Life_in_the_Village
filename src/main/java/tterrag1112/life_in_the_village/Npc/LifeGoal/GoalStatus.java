package tterrag1112.life_in_the_village.Npc.LifeGoal;

import com.mojang.serialization.Codec;

/**
 * Lifecycle state of a {@link LifeGoal}. ACTIVE goals are being pursued;
 * the three terminal states (COMPLETED / ABANDONED / FAILED) sit in the
 * NPC's history list briefly so dialogue can reference recent outcomes
 * before eviction.
 */
public enum GoalStatus {
    ACTIVE,
    COMPLETED,
    ABANDONED,
    FAILED;

    public boolean isTerminal() {
        return this != ACTIVE;
    }

    public static final Codec<GoalStatus> CODEC =
            Codec.STRING.xmap(GoalStatus::valueOf, GoalStatus::name);
}
