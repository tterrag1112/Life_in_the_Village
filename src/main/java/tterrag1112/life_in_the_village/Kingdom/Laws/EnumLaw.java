package tterrag1112.life_in_the_village.Kingdom.Laws;

import tterrag1112.life_in_the_village.Kingdom.Capabilities.KingdomCapability;

import java.util.List;

/**
 * Track D3.4 — one-of-many-state law. The active choice (a string
 * id from {@link #choices}) is stored on the per-kingdom
 * {@link KingdomLawInstance} (its {@code enumChoice} optional).
 *
 * <p>v1 ships with one example: {@code official_religion}, with
 * choices {@code "none" / "state_religion" / "toleration"}. Hot-
 * path effect: religious-order eligibility checks read the active
 * choice; a "state_religion" boosts ORDINATION_RIGHTS charters,
 * a "toleration" allows multiple religious orders to coexist.
 *
 * <p>Like {@link ScalarLaw}, EnumLaw enactment goes through
 * Chancellor (ISSUE_DECREE capability) per the user-confirmed
 * archetype-authority rule.
 *
 * @param choices       ordered list of valid choice ids.
 * @param defaultChoice id chosen at draft creation; must be in
 *                      {@code choices}.
 */
public record EnumLaw(
        String id,
        String displayName,
        String description,
        KingdomLawCategory category,
        KingdomLawScope scope,
        EnactmentCost enactmentCost,
        KingdomCapability enactmentCapability,
        List<String> requiredActiveLaws,
        List<String> choices,
        String defaultChoice
) implements KingdomLaw {

    public EnumLaw {
        requiredActiveLaws = List.copyOf(requiredActiveLaws);
        choices = List.copyOf(choices);
        if (choices.isEmpty()) {
            throw new IllegalArgumentException("EnumLaw " + id + " has no choices");
        }
        if (defaultChoice == null || !choices.contains(defaultChoice)) {
            defaultChoice = choices.get(0);
        }
    }

    /**
     * Returns true when the given choice id is one of this law's
     * defined options. Used to validate user-supplied draft edits
     * before applying them.
     */
    public boolean hasChoice(String choice) {
        return choice != null && choices.contains(choice);
    }

    @Override public String archetype() { return "enum"; }
}
