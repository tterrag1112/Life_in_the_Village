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
    ORDINATION,
    // ── Religion Rework R3b-1 ────────────────────────────────────────────
    /** Consecrates a religious building (TEMPLE / CHAPEL / SHRINE). A notable
     *  (GRAND-tier) ceremony scheduled by {@code RiteScheduler}'s daily
     *  consecration scan for un-consecrated religious buildings; on success it
     *  blesses the village and the SUCCESSFUL rite itself stands as the
     *  persistent "consecrated" marker (the rite ledger is unpruned — no new
     *  building field), which grants the village a small ongoing blessing while
     *  the building stands. The rite's first participant is the BUILDING's id,
     *  not an NPC. */
    CONSECRATION,
    // ── Religion Rework R3b-2 ────────────────────────────────────────────
    /** Communal vigil — a solemn village-wide observance of resolve / shared
     *  mourning (the somber counterpart to a feast). Gathering-route rite
     *  (EventType.VIGIL); scheduled off the religion calendar's Vigil-named
     *  days. Village-wide effect (no participants). */
    VIGIL,
    /** Communal purification / atonement — the group form of confession:
     *  eases distress and clears MELANCHOLY for the afflicted participants
     *  WITHOUT the per-confessor knowledge ledger entry. Gathering-route rite
     *  (EventType.PURIFICATION); distress-triggered. */
    PURIFICATION;

    public static final Codec<Rite> CODEC =
            Codec.STRING.xmap(Rite::valueOf, Rite::name);
}
