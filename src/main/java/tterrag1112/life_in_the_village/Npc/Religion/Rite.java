package tterrag1112.life_in_the_village.Npc.Religion;

import com.mojang.serialization.Codec;

/** Spec line 27 — 10 ritual types. */
public enum Rite {
    COMING_OF_AGE,
    MARRIAGE,
    NAMING,
    FUNERAL,
    BLESSING,
    CONFESSION,
    OFFERING,
    TITHE,
    HARVEST_THANKSGIVING,
    FEAST_DAY;

    public static final Codec<Rite> CODEC =
            Codec.STRING.xmap(Rite::valueOf, Rite::name);
}
