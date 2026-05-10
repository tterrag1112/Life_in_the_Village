package tterrag1112.life_in_the_village.Kingdom.War;

import java.util.Random;

/**
 * Track D3.6.4 — battle resolver interface per the user-locked
 * "(B) Stub handoff" design.
 *
 * <p>Phase 6 ships {@link #DEFAULT} — a pure-scoring resolver that
 * reads {@link Battle}'s inputs and produces an {@link Battle.Outcome}
 * + score deltas without any in-world combat. The warfare session
 * later replaces this implementation by swapping
 * {@link WarEngine#setResolver}; the {@link Battle} record's
 * interface remains stable so the warfare session reads the same
 * input fields.
 */
public interface BattleResolver {

    /**
     * Resolves the given pending battle. Implementations must:
     * <ul>
     *   <li>Read every input field from the battle record.</li>
     *   <li>Return a new {@code Battle} via
     *       {@link Battle#withOutcome} with a non-PENDING outcome
     *       and the score deltas the parent war should advance.</li>
     *   <li>Be deterministic — seed all RNG from
     *       {@link Battle#attackerSeed}.</li>
     * </ul>
     */
    Battle resolve(Battle pending);

    /**
     * Default scoring resolver. Combines levy ratio, leadership,
     * terrain, and supply into an attacker-advantage scalar in
     * roughly [-1.0, +1.0]; samples one outcome from a Gaussian
     * distribution centred on the advantage.
     */
    BattleResolver DEFAULT = pending -> {
        Random rng = new Random(pending.attackerSeed());
        double levyRatio = ratio(pending.attackerLevy(), pending.defenderLevy());
        double leadership = pending.attackerLeadership() - pending.defenderLeadership();
        // Score is biased advantage; terrain is positive-for-defender,
        // so we subtract; supply penalises the attacker (already negative).
        double advantage = 0.5 * (levyRatio - 1.0)
                + 0.25 * leadership
                - pending.terrainModifier()
                + pending.supplyModifier();
        // Gaussian noise (chance for upsets); std-dev 0.35.
        double roll = advantage + rng.nextGaussian() * 0.35;
        Battle.Outcome outcome;
        int aDelta, dDelta;
        if (roll > 0.35) {
            outcome = Battle.Outcome.ATTACKER_VICTORY;
            aDelta = (int) Math.round(8.0 + roll * 4.0);
            dDelta = -(int) Math.round(4.0 + roll * 2.0);
        } else if (roll < -0.35) {
            outcome = Battle.Outcome.DEFENDER_VICTORY;
            dDelta = (int) Math.round(8.0 + (-roll) * 4.0);
            aDelta = -(int) Math.round(4.0 + (-roll) * 2.0);
        } else {
            outcome = Battle.Outcome.DRAW;
            aDelta = -1;
            dDelta = -1;
        }
        return pending.withOutcome(outcome, aDelta, dDelta);
    };

    /** Safe ratio with min 1 floor on the divisor. */
    private static double ratio(int a, int d) {
        return (double) Math.max(1, a) / Math.max(1, d);
    }
}
