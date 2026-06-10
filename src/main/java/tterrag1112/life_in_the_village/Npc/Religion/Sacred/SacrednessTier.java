package tterrag1112.life_in_the_village.Npc.Religion.Sacred;

/**
 * Sacred Space S1a — a coarse classification of a position's sacredness potency, so
 * the S1b effects (favour / miracle / theophany bonuses) read a TIER, not a hard
 * number. One base rule (~1.0) reads {@link #MINOR}; two stacked rules (~2.0) read
 * {@link #MAJOR}.
 */
public enum SacrednessTier {
    NONE,
    MINOR,
    MAJOR;

    /** A single authored rule contributes ~1.0; the MAJOR threshold sits below 2.0
     *  so a stacked (biome + structure) spot reads MAJOR while a single rule or the
     *  lesser built bonus reads MINOR. */
    public static final float MAJOR_AT = 1.5f;

    /** Classifies a summed potency into a tier (≤0 → NONE). */
    public static SacrednessTier classify(float potency) {
        if (potency <= 0f) return NONE;
        return potency < MAJOR_AT ? MINOR : MAJOR;
    }
}
