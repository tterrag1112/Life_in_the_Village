package tterrag1112.life_in_the_village.Npc.Relations;

import com.mojang.serialization.Codec;

/**
 * Bucketed view of a {@link NpcRelationship} score. Spec
 * {@code 11-npc-relationship-ledger.md} (line 21).
 *
 * <pre>
 *  score &gt;= +75   → CLOSE_FRIEND
 *  score in [40, 75) → FRIEND
 *  score in [10, 40) → ACQUAINTANCE
 *  score in (-10, 10) → NEUTRAL
 *  score in (-40, -10] → RIVAL
 *  score in (-75, -40] → GRUDGE
 *  score &lt;= -75   → FEUD
 * </pre>
 */
public enum RelationshipMode {
    NEUTRAL,
    ACQUAINTANCE,
    FRIEND,
    CLOSE_FRIEND,
    RIVAL,
    GRUDGE,
    FEUD;

    public static RelationshipMode fromScore(int score) {
        if (score >= 75)  return CLOSE_FRIEND;
        if (score >= 40)  return FRIEND;
        if (score >= 10)  return ACQUAINTANCE;
        if (score > -10)  return NEUTRAL;
        if (score > -40)  return RIVAL;
        if (score > -75)  return GRUDGE;
        return FEUD;
    }

    public boolean isPositive() {
        return this == ACQUAINTANCE || this == FRIEND || this == CLOSE_FRIEND;
    }

    public boolean isNegative() {
        return this == RIVAL || this == GRUDGE || this == FEUD;
    }

    public static final Codec<RelationshipMode> CODEC =
            Codec.STRING.xmap(RelationshipMode::valueOf, RelationshipMode::name);
}
