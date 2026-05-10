package tterrag1112.life_in_the_village.Kingdom.Laws;

/**
 * Track D3.4 — lifecycle state of a {@link KingdomLawInstance}.
 *
 * <p>Per the user-confirmed design:
 * <ul>
 *   <li>{@link #DRAFT} — Scholar is iterating on the proposal.
 *       Reversible. Parameters can be edited freely. No effects
 *       apply.</li>
 *   <li>{@link #PROPOSED} — committed for ratification. Awaiting
 *       enactment by the appropriate office (King for ToggleLaws,
 *       Chancellor for ScalarLaws / EnumLaws). Parameters frozen.
 *       No effects apply.</li>
 *   <li>{@link #ACTIVE} — live. Effects apply. Parameters frozen.
 *       Repeal returns the instance to DRAFT (Scholar may re-edit)
 *       or removes it entirely depending on the repeal flow.</li>
 * </ul>
 */
public enum KingdomLawState {
    DRAFT,
    PROPOSED,
    ACTIVE;
}
