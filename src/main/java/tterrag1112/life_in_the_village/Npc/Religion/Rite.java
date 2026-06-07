package tterrag1112.life_in_the_village.Npc.Religion;

import com.mojang.serialization.Codec;

/** Spec line 27 — ritual types. ORDINATION (R1c) appended for save
 *  stability; {@link #CODEC} round-trips by {@code name()}, so order is
 *  immaterial to persistence and pre-R1c saves simply never reference it. */
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
    FEAST_DAY,
    // ── Religion Rework R1c ──────────────────────────────────────────────
    /** Officiated ceremony by which a PRIEST-profession NPC formally
     *  becomes clergy (assigns the locked clergy specialization). Profession-
     *  driven, not a life-stage event — scheduled by {@code RiteScheduler}'s
     *  daily ordination pass, not by a life-event gathering. */
    ORDINATION;

    public static final Codec<Rite> CODEC =
            Codec.STRING.xmap(Rite::valueOf, Rite::name);
}
