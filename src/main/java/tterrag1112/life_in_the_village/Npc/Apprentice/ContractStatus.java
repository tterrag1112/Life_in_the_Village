package tterrag1112.life_in_the_village.Npc.Apprentice;

import com.mojang.serialization.Codec;

/**
 * Lifecycle states for an {@link ApprenticeshipContract}. Spec
 * line 57.
 */
public enum ContractStatus {
    /** In progress — apprentice training, milestones advancing. */
    ACTIVE,
    /** Apprentice passed the masterpiece phase. Terminal. */
    COMPLETED,
    /** Master dismissed apprentice or apprentice quit. Terminal. */
    TERMINATED,
    /** Master died or apprentice abandoned mid-contract. Terminal. */
    BROKEN,
    /** Apprentice walked away on their own. Terminal. */
    ABANDONED;

    public boolean isTerminal() {
        return this == COMPLETED || this == TERMINATED
                || this == BROKEN || this == ABANDONED;
    }

    public static final Codec<ContractStatus> CODEC =
            Codec.STRING.xmap(ContractStatus::valueOf, ContractStatus::name);
}
