package tterrag1112.life_in_the_village.Npc.Religion;

/**
 * Religion Rework — Phase R1a. Rite-complexity tiers: the capability
 * axis a {@link Rite} is gated against. <em>Derived</em> from the rite
 * type via {@link #tierOf(Rite)} — there is no persisted tier field on
 * {@link RiteExecution}, so this adds no codec/save surface.
 *
 * <p>Ordering matters: a higher ordinal is a strictly harder rite, so
 * the capability gate ({@link RiteCapability}) compares ordinals — an
 * officiant whose cap is tier T can perform any rite of tier ≤ T.</p>
 *
 * <ul>
 *   <li>{@link #MINOR} — routine devotions any practising priest can
 *       do: BLESSING, OFFERING, TITHE, CONFESSION.</li>
 *   <li>{@link #STANDARD} — the life-event rites a seated village
 *       priest handles: NAMING, MARRIAGE, FUNERAL, COMING_OF_AGE.</li>
 *   <li>{@link #GRAND} — village-wide ceremonies: FEAST_DAY,
 *       HARVEST_THANKSGIVING.</li>
 * </ul>
 *
 * <p><b>No KINGDOM tier this phase.</b> The prompt reserves a KINGDOM
 * tier for future kingdom-wide rites (e.g. a sanctification rite) that
 * do not exist yet. Per the project's "no speculative enums" rule it is
 * omitted until a concrete rite maps to it; the kingdom-rites phase adds
 * the value <em>and</em> the TEMPLE_HIGH_PRIEST → KINGDOM office raise in
 * {@link RiteCapability} together.</p>
 */
public enum RiteTier {
    // Ordinals are the capability ladder — do not reorder.
    MINOR(5f),
    STANDARD(10f),
    GRAND(15f);

    private final float socialXp;

    RiteTier(float socialXp) {
        this.socialXp = socialXp;
    }

    /** SOCIAL XP an officiant earns for performing a rite of this tier.
     *  Scales with tier so progression is earned by doing harder rites
     *  once qualified (MINOR keeps the pre-R1a flat +5 award). */
    public float socialXp() {
        return socialXp;
    }

    /**
     * The (derived) tier of a rite. This is the single exhaustive
     * consumer of {@link Rite} for the tier mapping — adding a Rite
     * forces an arm here.
     */
    public static RiteTier tierOf(Rite rite) {
        return switch (rite) {
            case BLESSING, OFFERING, TITHE, CONFESSION                 -> MINOR;
            // ORDINATION (R1c): STANDARD — a seated village priest can ordain
            // new clergy; it is a routine life-event-style ceremony, not a
            // village-wide GRAND one.
            // VIGIL / PURIFICATION (R3b-2): STANDARD — pastoral community rites
            // a seated village priest leads, not grand celebrations.
            case NAMING, MARRIAGE, FUNERAL, COMING_OF_AGE, ORDINATION,
                 VIGIL, PURIFICATION                                  -> STANDARD;
            // CONSECRATION (R3b-1): GRAND — consecrating a sacred site is a
            // village-wide spiritual act, on par with the great ceremonies, so
            // it requires a competent officiant (seated priest or high skill).
            // SIGNATURE_RITE (R3b-3): GRAND — a faith's headline annual ceremony.
            case FEAST_DAY, HARVEST_THANKSGIVING, CONSECRATION,
                 SIGNATURE_RITE                                       -> GRAND;
        };
    }
}
