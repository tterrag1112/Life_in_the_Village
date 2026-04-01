package tterrag1112.life_in_the_village.Kingdom.Castle;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Defines the population of TownspersonMob residents in a castle.
 *
 * Each field is a target count. The spawner (added in Phase 3) reads this
 * record and places entities in the appropriate subbuilding zones.
 *
 * Kept separate from FunctionConfig so it can be serialised independently
 * and referenced by the capital system without loading the full style.
 */
public record CastleRoster(
        int kingCount,
        int queenCount,
        int lordCount,
        int ladyCount,
        int scholarCount,
        int guardCount,
        int knightCount,
        int servantCount,
        int cookCount,
        int smithCount,
        int stablehandCount,
        int scribeCount,
        int alchemistCount,
        int priestCount
) {

    public static final Codec<CastleRoster> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.INT.optionalFieldOf("king",        1).forGetter(CastleRoster::kingCount),
                    Codec.INT.optionalFieldOf("queen",       0).forGetter(CastleRoster::queenCount),
                    Codec.INT.optionalFieldOf("lord",        2).forGetter(CastleRoster::lordCount),
                    Codec.INT.optionalFieldOf("lady",        1).forGetter(CastleRoster::ladyCount),
                    Codec.INT.optionalFieldOf("scholar",     2).forGetter(CastleRoster::scholarCount),
                    Codec.INT.optionalFieldOf("guard",       8).forGetter(CastleRoster::guardCount),
                    Codec.INT.optionalFieldOf("knight",      3).forGetter(CastleRoster::knightCount),
                    Codec.INT.optionalFieldOf("servant",     4).forGetter(CastleRoster::servantCount),
                    Codec.INT.optionalFieldOf("cook",        1).forGetter(CastleRoster::cookCount),
                    Codec.INT.optionalFieldOf("smith",       1).forGetter(CastleRoster::smithCount),
                    Codec.INT.optionalFieldOf("stablehand",  1).forGetter(CastleRoster::stablehandCount),
                    Codec.INT.optionalFieldOf("scribe",      1).forGetter(CastleRoster::scribeCount),
                    Codec.INT.optionalFieldOf("alchemist",   0).forGetter(CastleRoster::alchemistCount),
                    Codec.INT.optionalFieldOf("priest",      1).forGetter(CastleRoster::priestCount)
            ).apply(instance, CastleRoster::new)
    );

    // ---- Preset rosters ----

    public static final CastleRoster STANDARD = new CastleRoster(
            1, 0, 2, 1, 2, 8, 3, 4, 1, 1, 1, 1, 0, 1);

    public static final CastleRoster MILITARY = new CastleRoster(
            1, 0, 1, 0, 0, 16, 6, 2, 2, 2, 2, 0, 0, 0);

    public static final CastleRoster SCHOLARLY = new CastleRoster(
            1, 1, 1, 1, 6, 4, 1, 4, 1, 0, 0, 4, 2, 1);

    public static final CastleRoster FANTASY = new CastleRoster(
            1, 1, 3, 2, 4, 6, 4, 4, 1, 1, 0, 2, 2, 1);

    /** Total residents — used to size spawn allocations. */
    public int totalCount() {
        return kingCount + queenCount + lordCount + ladyCount
                + scholarCount + guardCount + knightCount + servantCount
                + cookCount + smithCount + stablehandCount + scribeCount
                + alchemistCount + priestCount;
    }
}