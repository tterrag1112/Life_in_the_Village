package tterrag1112.life_in_the_village.Kingdom.AgeCycle;

/**
 * Track D3.6.6 — kingdom age-cycle states.
 *
 * <p>Per the locked design, kingdoms move through three states
 * driven by age (in-game days since founding) plus successful
 * succession count plus average ruler legitimacy. Each state
 * applies modifiers tagged {@code age_cycle.<state>} via D1's
 * modifier list (no schema fields added).
 *
 * <h3>Per-state modifiers</h3>
 * <ul>
 *   <li>{@link #FOUNDING_ERA} — legitimacy bonus to ruler,
 *       stability floor lifted, but laws cost more (institutions
 *       young).</li>
 *   <li>{@link #MATURE} — stability bonus, law/treaty costs
 *       reduced, succession resilience.</li>
 *   <li>{@link #DECADENT} — stability malus, increased rebellion
 *       threshold (rebellion fires sooner), increased intrigue
 *       success, modest legitimacy malus to new rulers.</li>
 * </ul>
 *
 * <p>Modifier id pattern: {@code age_cycle.<state_lowercase>}.
 * Engine swaps the modifier on transition.
 */
public enum KingdomAgeState {
    FOUNDING_ERA("age_cycle.founding_era",
            "Founding era — institutions young; laws cost more, but rulers carry weight.",
            +3, +5),
    MATURE("age_cycle.mature",
            "Mature — institutions stable; succession resilient.",
            +5, +2),
    DECADENT("age_cycle.decadent",
            "Decadent — instability rising; rebellion fires sooner.",
            -8, -5);

    private final String modifierId;
    private final String description;
    private final int stabilityDelta;
    private final int legitimacyDelta;

    KingdomAgeState(String modifierId, String description,
                    int stabilityDelta, int legitimacyDelta) {
        this.modifierId = modifierId;
        this.description = description;
        this.stabilityDelta = stabilityDelta;
        this.legitimacyDelta = legitimacyDelta;
    }

    public String modifierId() { return modifierId; }
    public String description() { return description; }
    public int stabilityDelta() { return stabilityDelta; }
    public int legitimacyDelta() { return legitimacyDelta; }
}
