package tterrag1112.life_in_the_village.Cultures;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Track D3.6.1 — per-culture rebellion + collapse threshold tuning.
 *
 * <p>Per the user-locked design "culture-per-default (each Culture
 * overrides via CultureBundles)": defaults match the prompt's
 * recommended set (grumble &lt; 40 / threat &lt; 20 / secession &lt; 10 /
 * collapse &lt; 15 for 60 in-game days). Cultures override per
 * temperament — tribal confederations might have lower thresholds
 * (more rebellious); unitary states might have higher (more stable).
 *
 * <h3>Threshold semantics</h3>
 * Each value is the stability ceiling at which the named rebellion
 * stage is triggered. "Below threshold" means
 * {@code provinceStability &lt; threshold}, inclusive of equality
 * to {@code threshold - 1}.
 *
 * @param grumble                stability ceiling for the GRUMBLE
 *                                state (newsfeed-only).
 * @param secessionThreat        stability ceiling for the
 *                                SECESSION_THREAT state (ruler
 *                                petition flow).
 * @param secession              stability ceiling for the
 *                                SECESSION state (auto-secede).
 * @param kingdomCollapse        stability ceiling for the kingdom-
 *                                level collapse track.
 * @param collapseDurationTicks  consecutive in-game ticks the
 *                                kingdom stability must remain
 *                                below {@code kingdomCollapse}
 *                                before the kingdom collapses.
 */
public record RebellionThresholds(
        int grumble,
        int secessionThreat,
        int secession,
        int kingdomCollapse,
        long collapseDurationTicks
) {

    /** Prompt's recommended defaults: 40 / 20 / 10 / 15 / 60-day. */
    public static final RebellionThresholds DEFAULT = new RebellionThresholds(
            /* grumble */              40,
            /* secessionThreat */      20,
            /* secession */            10,
            /* kingdomCollapse */      15,
            /* collapseDurationTicks */ 60L * 24000L);

    public static final Codec<RebellionThresholds> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.optionalFieldOf("grumble",         DEFAULT.grumble)
                    .forGetter(RebellionThresholds::grumble),
            Codec.INT.optionalFieldOf("secessionThreat", DEFAULT.secessionThreat)
                    .forGetter(RebellionThresholds::secessionThreat),
            Codec.INT.optionalFieldOf("secession",       DEFAULT.secession)
                    .forGetter(RebellionThresholds::secession),
            Codec.INT.optionalFieldOf("kingdomCollapse", DEFAULT.kingdomCollapse)
                    .forGetter(RebellionThresholds::kingdomCollapse),
            Codec.LONG.optionalFieldOf("collapseDurationTicks", DEFAULT.collapseDurationTicks)
                    .forGetter(RebellionThresholds::collapseDurationTicks)
    ).apply(i, RebellionThresholds::new));

    /** Returns the stage matching the given stability, or {@code null} for stable. */
    public Stage stageFor(int stability) {
        if (stability < secession)       return Stage.SECESSION;
        if (stability < secessionThreat) return Stage.SECESSION_THREAT;
        if (stability < grumble)         return Stage.GRUMBLE;
        return null;
    }

    public enum Stage { GRUMBLE, SECESSION_THREAT, SECESSION }
}
