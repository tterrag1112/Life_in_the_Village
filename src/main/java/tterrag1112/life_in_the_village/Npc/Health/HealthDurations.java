package tterrag1112.life_in_the_village.Npc.Health;

/**
 * Untreated condition durations + complication / mortality rates.
 * Spec lines 100-114. One day = 24 000 ticks.
 *
 * <p>Treated conditions resolve in half the duration (spec line 112)
 * and skip the complication / mortality roll.</p>
 */
public final class HealthDurations {

    public static final long DAY = 24000L;

    private HealthDurations() {}

    /** Untreated expected duration. */
    public static long durationFor(HealthCondition c, int severity) {
        long base = switch (c) {
            case MINOR_WOUND         ->  3 * DAY;
            case SERIOUS_WOUND       -> 14 * DAY;
            case BROKEN_BONE         -> 21 * DAY;
            case BURN                ->  6 * DAY;
            case EXHAUSTION          ->  2 * DAY;
            case FEVER               ->  7 * DAY;
            case STOMACH_AILMENT     ->  5 * DAY;
            case RESPIRATORY_ILLNESS -> 10 * DAY;
            case PLAGUE_CARRIER      -> 14 * DAY;
            case FRAILTY             -> 0L;          // permanent until death
            case MELANCHOLY          -> 60 * DAY;    // mood-driven; only resolves on mood lift
            case NERVOUS_BREAKDOWN   -> 30 * DAY;
        };
        // Severity 3 is baseline; 1 is half, 5 is double.
        float scale = 0.5f + 0.25f * (severity - 1);
        return Math.max(DAY, (long) (base * scale));
    }

    /** Per-spec complication probability if left untreated, expressed
     *  as a 0..1 chance once at end of duration. */
    public static float untreatedComplicationChance(HealthCondition c) {
        return switch (c) {
            case SERIOUS_WOUND        -> 0.10f;
            case BROKEN_BONE          -> 0.05f;
            case PLAGUE_CARRIER       -> 0.20f;
            case RESPIRATORY_ILLNESS  -> 0.05f;
            default                   -> 0.0f;
        };
    }

    /** Untreated mortality. Treated conditions return 0 — spec line
     *  114 (mortality eliminated for treatable conditions). */
    public static float untreatedMortalityChance(HealthCondition c, int severity) {
        float base = switch (c) {
            case PLAGUE_CARRIER  -> 0.20f;
            case SERIOUS_WOUND   -> 0.05f;
            case FRAILTY         -> 0.04f;
            default              -> 0f;
        };
        return base + 0.02f * Math.max(0, severity - 3);
    }
}
