package tterrag1112.life_in_the_village.Npc.Crime;

/**
 * Severity tiers for {@link CrimeType}. Drives presider selection
 * (MINOR/MODERATE → bailiff, SERIOUS/CAPITAL → leader), the
 * conviction threshold (1.5 / 2.5 / 3.5 / 4.5 evidence weight per
 * spec line 197), and {@link
 * tterrag1112.life_in_the_village.Npc.Laws.VillageLaw#DOUBLE_PUNISHMENT}'s
 * +1-tier escalation.
 */
public enum CrimeSeverity {
    MINOR,
    MODERATE,
    SERIOUS,
    CAPITAL;

    /** Conviction threshold per spec line 197. */
    public float convictionThreshold() {
        return switch (this) {
            case MINOR    -> 1.5f;
            case MODERATE -> 2.5f;
            case SERIOUS  -> 3.5f;
            case CAPITAL  -> 4.5f;
        };
    }

    /** Bumps severity by one tier (capped at CAPITAL). Used by
     *  {@code DOUBLE_PUNISHMENT}. */
    public CrimeSeverity escalated() {
        return switch (this) {
            case MINOR    -> MODERATE;
            case MODERATE -> SERIOUS;
            case SERIOUS, CAPITAL -> CAPITAL;
        };
    }
}
