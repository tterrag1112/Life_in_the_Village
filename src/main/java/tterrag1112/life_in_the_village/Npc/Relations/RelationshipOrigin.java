package tterrag1112.life_in_the_village.Npc.Relations;

import com.mojang.serialization.Codec;

/**
 * How an NPC first met another. Spec {@code 11-npc-relationship-ledger.md}
 * (line 65). Origins are seeded once at the entry's creation and never
 * change — they're a record of "where this relationship started".
 */
public enum RelationshipOrigin {
    /** Born into the same household — seeded at +40. */
    BORN_SAME_HOUSEHOLD,
    /** Grew up in the same village — seeded at +15 at adulthood. */
    BORN_SAME_VILLAGE,
    /** Assigned to the same workplace building — seeded at +5. */
    WORKPLACE_COLLEAGUE,
    /** Default for adult-era meetings via the bus. */
    MET_SOCIALLY,
    /** First interaction was a successful trade. */
    MET_THROUGH_TRADE,
    /** First interaction was negative (insult, attack, witnessed crime). */
    MET_IN_CONFLICT,
    /** Player explicitly facilitated the introduction (Phase 5 verb). */
    INTRODUCED_BY_PLAYER;

    public static final Codec<RelationshipOrigin> CODEC =
            Codec.STRING.xmap(RelationshipOrigin::valueOf, RelationshipOrigin::name);
}
