package tterrag1112.life_in_the_village.Kingdom.Laws;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Track D3.4 — debit applied to the kingdom's stability /
 * legitimacy / treasury when a law transitions to
 * {@link KingdomLawState#ACTIVE}. Repeal applies the inverse
 * (treasury refund, stability rebound) capped to avoid free
 * stability churn.
 *
 * <p>Default constant {@link #ZERO} is used for laws whose
 * activation has no immediate cost; ongoing-effect costs (e.g.
 * EDUCATION_STIPEND drains treasury per day) are encoded in the
 * effect hook, not here.
 *
 * @param treasuryBronze   one-time bronze debit on enactment.
 *                         Negative values give a refund.
 * @param stabilityDelta   stability scalar applied on enactment.
 *                         Negative = stability hit; positive =
 *                         (rare) stability bump for popular law.
 * @param legitimacyDelta  legitimacy scalar on enactment.
 * @param prestigeDelta    ruler personal-prestige scalar.
 *                         Used by Phase 5 audience-loop UI.
 */
public record EnactmentCost(
        long treasuryBronze,
        int stabilityDelta,
        int legitimacyDelta,
        int prestigeDelta
) {
    public static final EnactmentCost ZERO = new EnactmentCost(0L, 0, 0, 0);

    public static final Codec<EnactmentCost> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.LONG.optionalFieldOf("treasuryBronze", 0L).forGetter(EnactmentCost::treasuryBronze),
            Codec.INT.optionalFieldOf("stabilityDelta", 0).forGetter(EnactmentCost::stabilityDelta),
            Codec.INT.optionalFieldOf("legitimacyDelta", 0).forGetter(EnactmentCost::legitimacyDelta),
            Codec.INT.optionalFieldOf("prestigeDelta", 0).forGetter(EnactmentCost::prestigeDelta)
    ).apply(i, EnactmentCost::new));

    public boolean isFree() {
        return treasuryBronze == 0L && stabilityDelta == 0
                && legitimacyDelta == 0 && prestigeDelta == 0;
    }

    public static EnactmentCost flat(long bronze) {
        return new EnactmentCost(bronze, 0, 0, 0);
    }
}
