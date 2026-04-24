package tterrag1112.life_in_the_village.Npc.Mood;

/**
 * Bucketed view of a {@link NpcMoodState#value()}. Boundaries are inclusive
 * on the lower edge.
 *
 * <pre>
 * value &gt;=  75              → ELATED
 * value in [25, 75)          → CONTENT
 * value in (-25, 25)         → NEUTRAL
 * value in (-75, -25]        → TROUBLED
 * value &lt;= -75              → DISTRESSED
 * </pre>
 *
 * Spec note: NEUTRAL is open at both ends because exactly &plusmn;25 belongs
 * to the CONTENT/TROUBLED bucket, matching the spec's
 * {@code value > -25f → NEUTRAL} test.
 */
public enum MoodCategory {
    ELATED,
    CONTENT,
    NEUTRAL,
    TROUBLED,
    DISTRESSED;

    public static MoodCategory fromValue(float value) {
        if (value >= 75f)  return ELATED;
        if (value >= 25f)  return CONTENT;
        if (value > -25f)  return NEUTRAL;
        if (value > -75f)  return TROUBLED;
        return DISTRESSED;
    }
}
