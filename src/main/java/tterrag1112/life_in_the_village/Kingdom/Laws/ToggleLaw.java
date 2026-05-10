package tterrag1112.life_in_the_village.Kingdom.Laws;

import tterrag1112.life_in_the_village.Kingdom.Capabilities.KingdomCapability;

import java.util.List;

/**
 * Track D3.4 — boolean-state law. Mirrors the effective semantics
 * of the legacy {@link tterrag1112.life_in_the_village.Kingdom.KingdomLaw}
 * enum: when the per-kingdom {@link KingdomLawInstance} for this
 * law is in state {@link KingdomLawState#ACTIVE}, the effect is
 * "on"; otherwise "off". No parameters.
 *
 * <p>All eight legacy enum values migrate to ToggleLaw instances
 * by id; their hard-coded effect constants in
 * {@code KingdomLawEffects} stay where they are (the registry's
 * {@code effectHookId} is informational; the actual hot-path
 * lookups use {@link tterrag1112.life_in_the_village.Kingdom
 * .Kingdom#hasActiveLaw(String)} directly).
 */
public record ToggleLaw(
        String id,
        String displayName,
        String description,
        KingdomLawCategory category,
        KingdomLawScope scope,
        EnactmentCost enactmentCost,
        KingdomCapability enactmentCapability,
        List<String> requiredActiveLaws
) implements KingdomLaw {

    public ToggleLaw {
        requiredActiveLaws = List.copyOf(requiredActiveLaws);
    }

    public static ToggleLaw of(String id, String displayName, String description,
                               KingdomLawCategory cat) {
        return new ToggleLaw(id, displayName, description, cat,
                KingdomLawScope.KINGDOM, EnactmentCost.ZERO,
                KingdomCapability.PASS_LAW, List.of());
    }

    @Override public String archetype() { return "toggle"; }
}
