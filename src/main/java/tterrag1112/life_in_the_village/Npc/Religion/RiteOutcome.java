package tterrag1112.life_in_the_village.Npc.Religion;

import com.mojang.serialization.Codec;

/** Spec line 110. */
public enum RiteOutcome {
    PENDING,
    SUCCESSFUL,
    DISRUPTED,
    SKIPPED;

    public static final Codec<RiteOutcome> CODEC =
            Codec.STRING.xmap(RiteOutcome::valueOf, RiteOutcome::name);
}
