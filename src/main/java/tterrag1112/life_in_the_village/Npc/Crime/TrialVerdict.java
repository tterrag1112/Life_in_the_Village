package tterrag1112.life_in_the_village.Npc.Crime;

import com.mojang.serialization.Codec;

/** Spec line 91. */
public enum TrialVerdict {
    PENDING,
    GUILTY,
    NOT_GUILTY,
    MISTRIAL;

    public static final Codec<TrialVerdict> CODEC =
            Codec.STRING.xmap(TrialVerdict::valueOf, TrialVerdict::name);
}
