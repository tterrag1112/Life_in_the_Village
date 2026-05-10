package tterrag1112.life_in_the_village.Kingdom.Laws;

import tterrag1112.life_in_the_village.Kingdom.Capabilities.KingdomCapability;

import java.util.List;

/**
 * Track D3.4 — numeric-parameterized law. The active value is
 * stored on the per-kingdom {@link KingdomLawInstance} (its
 * {@code scalarValue} optional). Effects read the active value
 * via {@link tterrag1112.life_in_the_village.Kingdom.Kingdom
 * #lawScalar(String)}.
 *
 * <p>v1 ships with one example: {@code education_stipend}, a
 * 0..32 bronze/day stipend the kingdom treasury pays per
 * scholar-village. Hot-path effect drains treasury in the daily
 * tax tick.
 *
 * <p>Per the user-confirmed design, {@link ScalarLaw} enactment
 * goes through Chancellor (ISSUE_DECREE capability), not King —
 * parameterized laws need an administrator's eye on the value.
 *
 * @param min          inclusive lower bound for the value.
 * @param max          inclusive upper bound.
 * @param step         GUI slider step + value validation step.
 * @param unit         display unit string ("bronze/day", "%", etc.).
 * @param defaultValue value used at draft creation; clamped into
 *                     [min, max].
 */
public record ScalarLaw(
        String id,
        String displayName,
        String description,
        KingdomLawCategory category,
        KingdomLawScope scope,
        EnactmentCost enactmentCost,
        KingdomCapability enactmentCapability,
        List<String> requiredActiveLaws,
        double min,
        double max,
        double step,
        String unit,
        double defaultValue
) implements KingdomLaw {

    public ScalarLaw {
        requiredActiveLaws = List.copyOf(requiredActiveLaws);
        if (defaultValue < min) defaultValue = min;
        if (defaultValue > max) defaultValue = max;
    }

    /** Clamps an arbitrary input into the law's [min, max] range, snapped to step. */
    public double clamp(double value) {
        if (value < min) value = min;
        if (value > max) value = max;
        if (step > 0.0) {
            double offset = value - min;
            double snapped = Math.round(offset / step) * step;
            value = min + snapped;
            if (value > max) value = max;
        }
        return value;
    }

    @Override public String archetype() { return "scalar"; }
}
