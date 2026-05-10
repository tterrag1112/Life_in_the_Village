package tterrag1112.life_in_the_village.Kingdom.Audience;

/**
 * Track D3.5A — kind of {@link Petition} a player can submit to a
 * ruler's audience chamber.
 *
 * <p>Per the user-confirmed design, the audience loop is the single
 * server-authoritative entry point for player→kingdom interactions
 * that previously bypassed the political layer. Each kind dispatches
 * to a different {@link PetitionPayload} variant.
 *
 * <ul>
 *   <li>{@link #TREATY_RATIFICATION} — accept / reject a treaty
 *       drafted by another party. Carries the treaty UUID.</li>
 *   <li>{@link #CHARTER_REQUEST} — request a {@link
 *       tterrag1112.life_in_the_village.Kingdom.Charters.Charter}
 *       grant. Carries desired charter type + params.</li>
 *   <li>{@link #AUDIENCE_GRIEVANCE} — generic complaint /
 *       request-for-favour; ruler approval grants a small
 *       standing boost (data shape only — narrative payload).</li>
 *   <li>{@link #WAR_DECLARATION} — formal request to declare WAR
 *       on a target kingdom (used when the player isn't ruler but
 *       wants the ruler to act).</li>
 *   <li>{@link #BREAK_TREATY} — formal request to break a treaty.
 *       Carries the treaty UUID.</li>
 * </ul>
 */
public enum PetitionKind {
    TREATY_RATIFICATION,
    CHARTER_REQUEST,
    AUDIENCE_GRIEVANCE,
    WAR_DECLARATION,
    BREAK_TREATY
}
