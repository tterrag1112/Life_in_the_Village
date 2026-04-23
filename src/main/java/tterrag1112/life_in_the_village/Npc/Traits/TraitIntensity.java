package tterrag1112.life_in_the_village.Npc.Traits;

/**
 * Display-intensity bucket for a trait value. See {@link TraitVector} for
 * the numeric thresholds.
 */
public enum TraitIntensity {
    /** {@code 0.40 <= |value| < 0.85} — plain pole name displayed. */
    NORMAL,
    /** {@code |value| >= 0.85} — "Very &lt;pole&gt;" label. */
    EMPHATIC
}
