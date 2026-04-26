package tterrag1112.life_in_the_village.Npc.Health;

import com.mojang.serialization.Codec;

import java.util.Locale;

/**
 * Spec line 17. Twelve health conditions split across four buckets.
 * Conditions are layered on top of vanilla HP — they affect work
 * efficiency, mood, and travel, never the {@code health} attribute.
 *
 * <p>{@link #isContagious} drives the daily proximity-spread scan
 * (spec line 85); {@link #requiresMedicine} drives the healer queue
 * (only conditions that need a remedy go through the work goal).</p>
 */
public enum HealthCondition {
    // Injuries
    MINOR_WOUND,
    SERIOUS_WOUND,
    BROKEN_BONE,
    BURN,
    EXHAUSTION,

    // Illnesses
    FEVER,
    STOMACH_AILMENT,
    RESPIRATORY_ILLNESS,
    PLAGUE_CARRIER,

    // Age-related
    FRAILTY,

    // Mental (soft)
    MELANCHOLY,
    NERVOUS_BREAKDOWN;

    public boolean isContagious() {
        return this == RESPIRATORY_ILLNESS
                || this == PLAGUE_CARRIER
                || this == STOMACH_AILMENT;
    }

    /** False for self-resolving conditions: rest cures EXHAUSTION,
     *  mood-improvement cures MELANCHOLY. Spec line 36. */
    public boolean requiresMedicine() {
        return this != EXHAUSTION && this != MELANCHOLY;
    }

    /** True for the four mental / chronic conditions whose mortality
     *  is zero regardless of severity. Used by recovery to short-
     *  circuit mortality rolls. */
    public boolean isFatal() {
        return switch (this) {
            case PLAGUE_CARRIER, SERIOUS_WOUND, FRAILTY -> true;
            default -> false;
        };
    }

    public static final Codec<HealthCondition> CODEC = Codec.STRING.xmap(
            s -> HealthCondition.valueOf(s.toUpperCase(Locale.ROOT)),
            HealthCondition::name);
}
