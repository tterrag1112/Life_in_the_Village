package tterrag1112.life_in_the_village.Kingdom.War;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;

import java.util.UUID;

/**
 * Track D3.6.4 — battle record per the user-locked design
 * "(B) Stub handoff with Battle record".
 *
 * <p>Phase 6 produces this record with all inputs ready (forces,
 * terrain, leadership, supply). A default scoring resolver in
 * {@link BattleResolver#DEFAULT} computes outcome from the
 * inputs. The warfare session later replaces only the resolver,
 * with this record's interface stable.
 *
 * <p>Stable fields are critical — the warfare session will read
 * this record from D3.6 saves and decode them. Adding a new
 * field later must use {@code optionalFieldOf} with a sane
 * default.
 *
 * @param id              stable UUID.
 * @param warId           parent war.
 * @param attackerKingdomId / defenderKingdomId — combatants.
 * @param attackerLevy / defenderLevy — abstract levy scalars
 *                        from {@link LevyComputer}.
 * @param attackerLeadership / defenderLeadership — General +
 *                        ruler competence multipliers (1.0 baseline).
 * @param terrainModifier  per-province terrain favourability
 *                         (-0.3..+0.3); positive favours defender.
 * @param supplyModifier   supply-line distance penalty
 *                         (-0.4..0.0); negative penalises attacker
 *                         operating far from home.
 * @param attackerSeed     deterministic per-battle seed mixed into
 *                         the resolver RNG.
 * @param tick             server tick when the battle resolved.
 * @param outcome          {@link Outcome} stamped by the resolver.
 * @param attackerScoreDelta / defenderScoreDelta — score
 *                         contributions added to the parent war.
 */
public record Battle(
        UUID id,
        UUID warId,
        UUID attackerKingdomId,
        UUID defenderKingdomId,
        int attackerLevy,
        int defenderLevy,
        double attackerLeadership,
        double defenderLeadership,
        double terrainModifier,
        double supplyModifier,
        long attackerSeed,
        long tick,
        Outcome outcome,
        int attackerScoreDelta,
        int defenderScoreDelta
) {

    public enum Outcome {
        /** Awaiting resolution. */
        PENDING,
        /** Attacker won; score advances. */
        ATTACKER_VICTORY,
        /** Defender won; score recedes for attacker. */
        DEFENDER_VICTORY,
        /** Pyrrhic / inconclusive; attrition only. */
        DRAW
    }

    public static final Codec<Battle> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("id").forGetter(Battle::id),
            UUIDUtil.CODEC.fieldOf("warId").forGetter(Battle::warId),
            UUIDUtil.CODEC.fieldOf("attackerKingdomId").forGetter(Battle::attackerKingdomId),
            UUIDUtil.CODEC.fieldOf("defenderKingdomId").forGetter(Battle::defenderKingdomId),
            Codec.INT.optionalFieldOf("attackerLevy", 0).forGetter(Battle::attackerLevy),
            Codec.INT.optionalFieldOf("defenderLevy", 0).forGetter(Battle::defenderLevy),
            Codec.DOUBLE.optionalFieldOf("attackerLeadership", 1.0).forGetter(Battle::attackerLeadership),
            Codec.DOUBLE.optionalFieldOf("defenderLeadership", 1.0).forGetter(Battle::defenderLeadership),
            Codec.DOUBLE.optionalFieldOf("terrainModifier", 0.0).forGetter(Battle::terrainModifier),
            Codec.DOUBLE.optionalFieldOf("supplyModifier", 0.0).forGetter(Battle::supplyModifier),
            Codec.LONG.optionalFieldOf("attackerSeed", 0L).forGetter(Battle::attackerSeed),
            Codec.LONG.fieldOf("tick").forGetter(Battle::tick),
            Codec.STRING.xmap(Outcome::valueOf, Enum::name)
                    .optionalFieldOf("outcome", Outcome.PENDING).forGetter(Battle::outcome),
            Codec.INT.optionalFieldOf("attackerScoreDelta", 0).forGetter(Battle::attackerScoreDelta),
            Codec.INT.optionalFieldOf("defenderScoreDelta", 0).forGetter(Battle::defenderScoreDelta)
    ).apply(i, Battle::new));

    public Battle withOutcome(Outcome o, int aDelta, int dDelta) {
        return new Battle(id, warId, attackerKingdomId, defenderKingdomId,
                attackerLevy, defenderLevy, attackerLeadership, defenderLeadership,
                terrainModifier, supplyModifier, attackerSeed, tick,
                o, aDelta, dDelta);
    }
}
