package tterrag1112.life_in_the_village.Kingdom.Capabilities;

/**
 * Track D3.3b — high-level capabilities a kingdom can exercise.
 *
 * <p>Each capability maps to one or more {@code OfficeRegistry}
 * kingdom offices. A kingdom can exercise a capability when its
 * King is competently held (King is sovereign and implicitly
 * satisfies every capability), OR when the named delegated office
 * is competently held. "Competently held" = competence multiplier
 * &geq; 1.0 (holder clears the office's {@code minLevel} skill
 * requirement; vacancy returns 1 + vacancyPenalty &lt; 1.0).
 *
 * <p>Capabilities are checked at decision time by
 * {@link KingdomCapabilityEvaluator}. They are never persisted;
 * they're a derived view over the office state + skill levels.
 *
 * <h3>Capability table (OR semantics, per user-confirmed design)</h3>
 * <ul>
 *   <li>{@link #ISSUE_DECREE}      — King OR Chancellor</li>
 *   <li>{@link #PASS_LAW}          — King OR Magistrate</li>
 *   <li>{@link #INVESTIGATE_CRIME} — King OR Magistrate</li>
 *   <li>{@link #DECLARE_WAR}       — King OR General</li>
 *   <li>{@link #LEVY_TROOPS}       — King OR General</li>
 *   <li>{@link #DRAFT_TREATY}      — King OR Diplomat</li>
 *   <li>{@link #INTRIGUE_FOREIGN}  — King OR Spymaster</li>
 *   <li>{@link #ISSUE_CURRENCY}    — King OR Treasurer</li>
 * </ul>
 *
 * <h3>Royal Scholar</h3>
 * The Royal Scholar grants no capability of their own; instead
 * they raise the effective competence of certain literacy-driven
 * offices (Chancellor, Magistrate). Phase 4's law / charter
 * resolution multiplies the Scholar's competence into the
 * Chancellor / Magistrate's effect. v1 ships the registry; the
 * boost is wired here as a {@code scholarBoost} hook on the
 * evaluator.
 */
public enum KingdomCapability {
    /** Pass a kingdom-tier law. Requires King OR Magistrate. */
    PASS_LAW,
    /** Issue a royal decree. Requires King OR Chancellor. */
    ISSUE_DECREE,
    /** Declare war on another kingdom. Requires King OR General. */
    DECLARE_WAR,
    /** Levy troops from member villages. Requires King OR General. */
    LEVY_TROOPS,
    /** Draft a diplomatic treaty. Requires King OR Diplomat. */
    DRAFT_TREATY,
    /** Conduct foreign intrigue. Requires King OR Spymaster. */
    INTRIGUE_FOREIGN,
    /** Investigate a criminal case at kingdom tier. Requires King OR Magistrate. */
    INVESTIGATE_CRIME,
    /** Issue a kingdom currency. Requires King OR Treasurer. */
    ISSUE_CURRENCY,
    /**
     * Track D3.6.5 — launch a conversion campaign on a kingdom
     * province. Per the prompt: "Diplomat + Treasurer + ruler +
     * treasury cost". Per the existing OR-semantics the evaluator
     * accepts King OR Diplomat OR Treasurer; treasury cost is
     * enforced at the driver site.
     */
    CONVERT_PROVINCE;
}
