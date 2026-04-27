package tterrag1112.life_in_the_village.Guilds.Common;

import com.mojang.serialization.Codec;

import java.util.Locale;

/**
 * Phase 4 doc 27 — rank ladder shared across guild types.
 *
 * <p>Distinct from the legacy {@code GuildRank} enum (BRONZE / SILVER
 * / GOLD / PLATINUM / DIAMOND), which stays in the adventurer
 * package keyed off XP thresholds. The new ladder has APPLICANT and
 * ELDER bookends per spec line 100. Each guild subtype maps its own
 * progression to these tiers.</p>
 */
public enum GuildRankTier {
    APPLICANT(0),
    BRONZE(1),
    SILVER(2),
    GOLD(3),
    PLATINUM(4),
    ELDER(5);

    private final int seniority;

    GuildRankTier(int seniority) { this.seniority = seniority; }

    public int seniority() { return seniority; }

    public boolean atLeast(GuildRankTier other) {
        return other == null || seniority >= other.seniority;
    }

    public GuildRankTier next() {
        GuildRankTier[] vals = values();
        int idx = ordinal() + 1;
        return idx < vals.length ? vals[idx] : ELDER;
    }

    public boolean isMaxRank() { return this == ELDER; }

    public String displayName() {
        return name().charAt(0) + name().substring(1).toLowerCase(Locale.ROOT);
    }

    public static final Codec<GuildRankTier> CODEC = Codec.STRING.xmap(
            s -> GuildRankTier.valueOf(s.toUpperCase(Locale.ROOT)),
            GuildRankTier::name);
}
