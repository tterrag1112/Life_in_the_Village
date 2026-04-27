package tterrag1112.life_in_the_village.Guilds.Common;

import com.mojang.serialization.Codec;

import java.util.Locale;

/**
 * Phase 4 doc 27 line 117. Guild level gates which capabilities are
 * available — implicit guilds (L0) track members but can't post inter-
 * village requests; established guilds (L1) have a hall and offices;
 * recognised (L2) and prominent (L3) extend to multi-village reach.
 */
public enum GuildLevel {
    /** No physical hall — emergent from a profession cluster. */
    IMPLICIT(0),
    /** Hall built, offices populated, local request posting. */
    ESTABLISHED(1),
    /** Multi-village members, cross-village request acceptance. */
    RECOGNIZED(2),
    /** Kingdom-level influence. */
    PROMINENT(3);

    private final int value;

    GuildLevel(int value) { this.value = value; }

    public int value() { return value; }

    public boolean canPostRequests()         { return value >= 1; }
    public boolean canAcceptRemoteRequests() { return value >= 2; }
    public boolean canSwayKingdomLaws()      { return value >= 3; }

    public static GuildLevel fromValue(int v) {
        for (GuildLevel l : values()) if (l.value == v) return l;
        return IMPLICIT;
    }

    public static final Codec<GuildLevel> CODEC = Codec.STRING.xmap(
            s -> GuildLevel.valueOf(s.toUpperCase(Locale.ROOT)),
            GuildLevel::name);
}
