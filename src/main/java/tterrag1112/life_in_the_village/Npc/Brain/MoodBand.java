package tterrag1112.life_in_the_village.Npc.Brain;

/**
 * Bucketed mood for use by {@link IdleGesturePalette} weighting.
 * Replaces the Phase 6.0 single-int bias with a five-band scheme so
 * "very expressive" and "very subdued" gestures can be selectively
 * unlocked / suppressed.
 *
 * <p>Cut points match Phase 6.1.a spec: ≥50 VERY_HIGH, ≥25 HIGH,
 * [-25,25) NEUTRAL, ≤-25 LOW, ≤-50 VERY_LOW. (Boundary at exactly +25
 * lands in HIGH; -25 lands in LOW.)
 */
public enum MoodBand {
    VERY_LOW,
    LOW,
    NEUTRAL,
    HIGH,
    VERY_HIGH;

    public static MoodBand fromSnapshot(int snapshot) {
        if (snapshot >= 50)  return VERY_HIGH;
        if (snapshot >= 25)  return HIGH;
        if (snapshot <= -50) return VERY_LOW;
        if (snapshot <= -25) return LOW;
        return NEUTRAL;
    }
}
