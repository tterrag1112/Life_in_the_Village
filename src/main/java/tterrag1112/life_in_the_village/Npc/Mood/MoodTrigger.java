package tterrag1112.life_in_the_village.Npc.Mood;

import com.mojang.serialization.Codec;
import tterrag1112.life_in_the_village.Npc.Traits.TraitAxis;
import tterrag1112.life_in_the_village.Npc.Traits.TraitVector;

/**
 * The named events that change mood. Each trigger carries a default
 * magnitude (the unmodulated mood delta) and a daily-stack cap.
 *
 * <p>Phase 0 ships the enum but no production code fires triggers — they
 * are reachable only via {@code /npc mood trigger} for testing. Phase 1
 * producers (gift action, family death handler, festival end goal, ...)
 * fan out from the {@code NpcLifeEventBus} and call
 * {@link NpcMoodState#apply}.</p>
 *
 * <h3>Daily stack cap</h3>
 * <p>The spec gives {@code dailyStackCap} as a float (e.g. {@code 0.4f}
 * for {@code GIFT_RECEIVED}) without specifying units. Phase 0 interprets
 * the cap as <strong>fraction of the mood scalar range</strong>, so the
 * effective cap in mood points is {@code 100f * dailyStackCap}. A
 * {@code dailyStackCap} of {@code 0.0f} disables the cap (rare/unique
 * events). See {@code 04-mood-system.md} Revision Notes for the full
 * rationale.</p>
 */
public enum MoodTrigger {
    // ── Player-facing social events ──────────────────────────────────────────
    GIFT_RECEIVED            (+8,  0.4f),
    GIFT_FAVORITE            (+20, 0.3f),
    INSULT_RECEIVED          (-12, 0.4f),
    COMPLIMENT_RECEIVED      (+5,  0.3f),

    // ── Life-altering one-shots ──────────────────────────────────────────────
    FAMILY_DEATH             (-60, 0.0f),
    CLOSE_FRIEND_DEATH       (-35, 0.0f),
    RIVAL_DEATH              (+8,  0.0f),
    BIRTH_IN_FAMILY          (+35, 0.0f),
    MARRIAGE                 (+50, 0.0f),
    RESCUED                  (+45, 0.0f),
    RESCUED_SOMEONE          (+25, 0.0f),
    PROMOTION                (+30, 0.0f),
    DEMOTION                 (-25, 0.0f),
    FIRED_FROM_JOB           (-30, 0.0f),
    HIRED                    (+15, 0.0f),
    GOAL_COMPLETED           (+25, 0.0f),
    GOAL_FAILED              (-15, 0.0f),

    // ── Repeatable / recurring ───────────────────────────────────────────────
    FESTIVAL_ATTENDED        (+15, 0.2f),
    CRIME_VICTIM             (-30, 0.3f),
    CRIME_WITNESSED          (-8,  0.2f),
    WEATHER_PLEASANT         (+2,  0.1f),
    WEATHER_HARSH            (-3,  0.1f),
    FAMINE                   (-10, 0.1f),
    SEASON_BELOVED           (+5,  0.05f),
    LETTER_RECEIVED          (+6,  0.2f),
    LETTER_FROM_FRIEND       (+12, 0.2f),
    RUMOR_POSITIVE_ABOUT_SELF(+6,  0.15f),
    RUMOR_NEGATIVE_ABOUT_SELF(-10, 0.15f);

    private final int defaultMagnitude;
    private final float dailyStackCap;

    MoodTrigger(int defaultMagnitude, float dailyStackCap) {
        this.defaultMagnitude = defaultMagnitude;
        this.dailyStackCap = dailyStackCap;
    }

    public int defaultMagnitude() { return defaultMagnitude; }
    public float dailyStackCap()  { return dailyStackCap; }

    /**
     * Returns the cap on cumulative mood points per in-game day for this
     * trigger, or {@code 0f} when the trigger isn't capped (one-shot
     * events). See class-level docs for the unit interpretation.
     */
    public float cumulativeDailyCap() {
        return dailyStackCap == 0f ? 0f : 100f * dailyStackCap;
    }

    /**
     * Trait modulation per spec section "Mood application rules". The spec
     * lists three illustrative rules ({@code INSULT_RECEIVED} ×
     * {@code TEMPERANCE}, {@code GIFT_RECEIVED} × {@code GENEROSITY},
     * {@code FAMILY_DEATH} × {@code COMPASSION}). Other obviously-aligned
     * triggers extend the same pattern; the rest fall through to a
     * {@code 1.0} multiplier. Listed explicitly so future tuning passes
     * have a single point of edit.
     *
     * @return a positive multiplier to apply to {@link #defaultMagnitude}.
     */
    public float traitMultiplier(TraitVector traits) {
        return switch (this) {
            case INSULT_RECEIVED      -> 1f - 0.5f * traits.get(TraitAxis.TEMPERANCE);
            case GIFT_RECEIVED,
                 GIFT_FAVORITE        -> 1f + 0.3f * traits.get(TraitAxis.GENEROSITY);
            case FAMILY_DEATH,
                 CLOSE_FRIEND_DEATH   -> 1f + 0.5f * traits.get(TraitAxis.COMPASSION);
            case COMPLIMENT_RECEIVED  -> 1f + 0.2f * traits.get(TraitAxis.SOCIABILITY);
            case FESTIVAL_ATTENDED    -> 1f + 0.3f * traits.get(TraitAxis.SOCIABILITY);
            case GOAL_COMPLETED,
                 GOAL_FAILED          -> 1f + 0.3f * traits.get(TraitAxis.AMBITION);
            default                   -> 1f;
        };
    }

    public static final Codec<MoodTrigger> CODEC =
            Codec.STRING.xmap(MoodTrigger::valueOf, MoodTrigger::name);
}
