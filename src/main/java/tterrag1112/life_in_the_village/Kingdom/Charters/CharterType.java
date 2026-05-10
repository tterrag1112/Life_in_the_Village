package tterrag1112.life_in_the_village.Kingdom.Charters;

/**
 * Track D3.4b — type of grant a {@link Charter} confers.
 *
 * <p>Per the user-confirmed catalogue: six types, each with
 * type-specific {@link CharterParams}. {@code revocationBaseLegitimacy}
 * feeds the revocation-cost formula
 * {@code base + 0.5 × ageInDays}, applied as a one-shot legitimacy hit
 * with a 7-day stability dip of {@code base × 2}.
 */
public enum CharterType {
    /** Right to collect tolls on a road segment. */
    TOLL_RIGHTS(5),
    /** Reduction or removal of tax obligation for a grantee. */
    TAX_EXEMPTION(3),
    /** Exclusive market right for a building/scope pair. */
    MARKET_MONOPOLY(6),
    /** Player-facing path to titled nobility (Phase 5 hooks here). */
    TITLE_GRANT(10),
    /** Religious order's authority to ordain priests. Reserved for Phase 6. */
    ORDINATION_RIGHTS(4),
    /** Estate / manor land grant to a grantee. */
    LAND_GRANT(8);

    private final int revocationBaseLegitimacy;

    CharterType(int base) { this.revocationBaseLegitimacy = base; }

    public int revocationBaseLegitimacy() { return revocationBaseLegitimacy; }

    /**
     * Per the user-confirmed formula: legitimacy hit on revocation
     * scales with age. Non-negative; clamped at zero for fresh
     * charters granted same day.
     */
    public int legitimacyHit(long ageInDays) {
        long scaled = revocationBaseLegitimacy + Math.max(0L, ageInDays / 2L);
        return (int) Math.max(0L, scaled);
    }

    /** 7-day stability dip applied alongside the legitimacy hit. */
    public int stabilityDip() {
        return revocationBaseLegitimacy * 2;
    }
}
