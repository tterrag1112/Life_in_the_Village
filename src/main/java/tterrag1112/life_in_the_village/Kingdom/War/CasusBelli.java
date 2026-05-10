package tterrag1112.life_in_the_village.Kingdom.War;

/**
 * Track D3.6.4 — casus belli vocabulary for declared wars.
 *
 * <p>Per the prompt's enumeration. Each value carries:
 * <ul>
 *   <li>{@link #legitimacyOnDeclaration} — applied to the
 *       declaring kingdom at declaration time. Aggressive
 *       casus belli (HONOUR, RECONQUEST without territorial
 *       grounding) carry a malus; defensive / treaty-based
 *       (RECLAIM_VASSAL, HOLY_WAR with religious cause) are
 *       neutral or positive.</li>
 *   <li>{@link #defaultWarScoreSeed} — seed the war's
 *       attacker-side score gets; reflects political momentum.</li>
 *   <li>{@link #allowsRegimeChange} — true when the casus belli
 *       admits regime-change war goals.</li>
 *   <li>{@link #allowsVassalization} — true when the casus belli
 *       admits vassalization war goals.</li>
 *   <li>{@link #allowsTerritory} — true when the casus belli
 *       admits territorial-claim war goals.</li>
 * </ul>
 */
public enum CasusBelli {
    /** Direct territorial claim against the defender. */
    TERRITORIAL_CLAIM(-3, 5, false, false, true),
    /** Reclaim a former vassal that's rebelled. */
    RECLAIM_VASSAL(0, 10, false, true, true),
    /** Personal slight; weakest justification. Heavy legitimacy malus. */
    HONOUR(-8, 0, false, false, false),
    /** Religious justification via 6.5; bonus during HOLY periods. */
    HOLY_WAR(2, 5, true, false, true),
    /** Disputed claim on the throne. */
    SUCCESSION_DISPUTE(-2, 5, true, false, false),
    /** Reconquest of historically-claimed territory. */
    RECONQUEST(-4, 8, false, false, true);

    private final int legitimacyOnDeclaration;
    private final int defaultWarScoreSeed;
    private final boolean allowsRegimeChange;
    private final boolean allowsVassalization;
    private final boolean allowsTerritory;

    CasusBelli(int legOnDecl, int defScoreSeed,
               boolean regimeChange, boolean vassalize, boolean territory) {
        this.legitimacyOnDeclaration = legOnDecl;
        this.defaultWarScoreSeed = defScoreSeed;
        this.allowsRegimeChange = regimeChange;
        this.allowsVassalization = vassalize;
        this.allowsTerritory = territory;
    }

    public int legitimacyOnDeclaration() { return legitimacyOnDeclaration; }
    public int defaultWarScoreSeed()     { return defaultWarScoreSeed; }
    public boolean allowsRegimeChange()  { return allowsRegimeChange; }
    public boolean allowsVassalization() { return allowsVassalization; }
    public boolean allowsTerritory()     { return allowsTerritory; }
}
