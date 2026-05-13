package tterrag1112.life_in_the_village.Village.Planning.V2.Layer2;

import tterrag1112.life_in_the_village.Village.Planning.V2.Inclination;

import java.util.Set;

/**
 * Track E1B — declarative village-layout strategy.
 *
 * <p>A strategy is pure data: an id, the inclination it serves,
 * its village {@link LayoutTopology} (glumbosch form), anchor
 * preferences, the road-primitive set it intends to use, per-
 * building anchor-type bindings, and pre-scoring conditions.
 *
 * <h3>Extensibility</h3>
 * Adding a new strategy is a one-entry registration in
 * {@link LayoutStrategyRegistry}:
 *
 * <pre>{@code
 * registry.register(new LayoutStrategy(
 *     "agricultural_terraced",
 *     Inclination.AGRICULTURAL,
 *     LayoutTopology.HAUFENDORF,
 *     new AnchorPreferences(
 *         Set.of(AnchorType.RIDGE_LINE),
 *         Set.of(AnchorType.FLAT_FERTILE),
 *         true,
 *         0.5),
 *     Set.of("CurvedRoad", "Stairway", "Spur"),
 *     new BuildingBindings(Map.of(
 *         BuildingType.TOWN_HALL, List.of(AnchorType.FLAT_FERTILE),
 *         BuildingType.FARMHOUSE, List.of(AnchorType.FLAT_FERTILE))),
 *     new StrategyConditions(ViabilityTier.HAMLET, ViabilityTier.TOWN, Set.of()),
 *     "Hillside-terraced agricultural village"
 * ));
 * }</pre>
 *
 * <p>No class extension, no dispatch wiring, no inclination →
 * strategy lookup tables elsewhere.
 *
 * @param id
 *      Stable identifier, e.g. {@code "agricultural_haufendorf"}.
 *      Must be unique across the registry.
 * @param inclination
 *      The inclination this strategy serves. Selection only
 *      considers strategies whose inclination matches the site's
 *      decided inclination.
 * @param topology
 *      The village-form bucket (prompt-3 grower reads this).
 * @param anchorPrefs
 *      Anchor-type preferences and quality / linear requirements.
 * @param primitives
 *      Set of {@link tterrag1112.life_in_the_village.Village.Planning.Primitives.RoadPrimitive}
 *      {@code typeKey()} strings the strategy intends to use.
 *      Strings (not an enum) because RoadPrimitive has no
 *      enum form; strategies reference primitives by typeKey to
 *      stay decoupled from the sealed permits.
 * @param bindings
 *      Per-building anchor-type preference list. Declarative.
 * @param conditions
 *      Pre-scoring filter (tier band + anchor incompatibilities).
 * @param description
 *      Human-readable one-liner; surfaces in selection-log lines.
 */
public record LayoutStrategy(
        String id,
        Inclination inclination,
        LayoutTopology topology,
        AnchorPreferences anchorPrefs,
        Set<String> primitives,
        BuildingBindings bindings,
        StrategyConditions conditions,
        String description) {

    public LayoutStrategy {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("LayoutStrategy id must be non-blank");
        }
        if (inclination == null) {
            throw new IllegalArgumentException("LayoutStrategy.inclination required");
        }
        if (topology == null) {
            throw new IllegalArgumentException("LayoutStrategy.topology required");
        }
        if (anchorPrefs == null) anchorPrefs = AnchorPreferences.any();
        if (bindings == null)    bindings    = BuildingBindings.none();
        if (conditions == null)  conditions  = StrategyConditions.any();
        primitives = primitives == null ? Set.of() : Set.copyOf(primitives);
        if (description == null) description = "";
    }
}
