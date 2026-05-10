package tterrag1112.life_in_the_village.Kingdom.Treaties;

/**
 * Track D3.4b — diplomatic treaty types.
 *
 * <p>Per the user-confirmed precedence rule:
 * <ul>
 *   <li>{@link #ALLIANCE} — mutual defense; declaring WAR on an
 *       ally cascade-breaks this treaty first; severe legitimacy
 *       hit on break.</li>
 *   <li>{@link #NON_AGGRESSION} — neither party may declare WAR
 *       while active. Lower-cost break than ALLIANCE; no positive
 *       cooperation.</li>
 *   <li>{@link #TRADE_DEAL} — economic bonus to both parties.
 *       Mid-cost break.</li>
 *   <li>{@link #VASSALAGE} — one party becomes vassal of the other;
 *       fealty-chain extension flows tribute (vassal → overlord);
 *       vassal cannot DECLARE_WAR. Severe legitimacy hit on
 *       unilateral break (rebellion).</li>
 * </ul>
 */
public enum TreatyType {
    ALLIANCE(15, -10),
    NON_AGGRESSION(5, -3),
    TRADE_DEAL(8, -5),
    VASSALAGE(20, -15);

    private final int legitimacyHitOnBreak;
    private final int stabilityDeltaOnBreak;

    TreatyType(int legHit, int stabDelta) {
        this.legitimacyHitOnBreak = legHit;
        this.stabilityDeltaOnBreak = stabDelta;
    }

    public int legitimacyHitOnBreak()  { return legitimacyHitOnBreak; }
    public int stabilityDeltaOnBreak() { return stabilityDeltaOnBreak; }

    /** True for treaties that produce a cooperative (non-hostile) relation. */
    public boolean isCooperative() {
        return this == ALLIANCE || this == TRADE_DEAL;
    }
}
