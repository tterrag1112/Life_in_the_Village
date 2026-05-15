package tterrag1112.life_in_the_village.Village.Planning.V2.Layer2;

import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Planning.V2.Inclination;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Track E1B — registry of {@link LayoutStrategy}s.
 *
 * <p>Per-inclination ordered list of strategies. {@link StrategySelector}
 * iterates in registry order; ties in score break by registry order.
 *
 * <h3>Ship set</h3>
 * 21 strategies total, per locked design pass:
 * <ul>
 *   <li>AGRICULTURAL × 3 — haufendorf / reihendorf / marschhufendorf</li>
 *   <li>INDUSTRIAL × 3 — mining / woodcutter / haufendorf</li>
 *   <li>CIVIC × 2 — angerdorf / haufendorf</li>
 *   <li>RESIDENTIAL × 2 — haufendorf / reihendorf</li>
 *   <li>SACRED × 2 — angerdorf / isolated</li>
 *   <li>DEFENSIVE × 3 — einzelhof / rundling / keep</li>
 *   <li>{@code *_cluster_fallback} × 6 — one per inclination,
 *       always passes conditions, used when no scored candidate
 *       fits.</li>
 * </ul>
 *
 * <h3>Adding a strategy</h3>
 * Append a new {@link LayoutStrategy} to the matching inclination
 * list inside {@link #buildDefaults}. No class extension, no
 * dispatch wiring.
 */
public final class LayoutStrategyRegistry {

    // RoadPrimitive typeKey() string constants — referenced as data, not
    // through any enum (RoadPrimitive has none today).
    static final String PRIM_STRAIGHT     = "StraightRoad";
    static final String PRIM_CURVED       = "CurvedRoad";
    static final String PRIM_RING         = "Ring";
    static final String PRIM_ARC          = "Arc";
    static final String PRIM_SPUR         = "Spur";
    static final String PRIM_SMOOTHED     = "SmoothedPath";
    static final String PRIM_ARM_APPROACH = "ArmApproach";
    static final String PRIM_BRIDGE       = "Bridge";
    static final String PRIM_STAIRWAY     = "Stairway";

    private static final Map<Inclination, List<LayoutStrategy>> BY_INCLINATION
            = buildDefaults();

    private LayoutStrategyRegistry() {}

    public static List<LayoutStrategy> byInclination(Inclination inc) {
        if (inc == null) return List.of();
        return BY_INCLINATION.getOrDefault(inc, List.of());
    }

    public static List<LayoutStrategy> all() {
        List<LayoutStrategy> out = new ArrayList<>();
        for (var list : BY_INCLINATION.values()) out.addAll(list);
        return Collections.unmodifiableList(out);
    }

    // =========================================================================
    // Defaults
    // =========================================================================

    private static Map<Inclination, List<LayoutStrategy>> buildDefaults() {
        Map<Inclination, List<LayoutStrategy>> map = new EnumMap<>(Inclination.class);
        // Order within each list: domain-specific strategies first, fallback last.
        map.put(Inclination.AGRICULTURAL, agricultural());
        map.put(Inclination.INDUSTRIAL,   industrial());
        map.put(Inclination.CIVIC,        civic());
        map.put(Inclination.RESIDENTIAL,  residential());
        map.put(Inclination.SACRED,       sacred());
        map.put(Inclination.DEFENSIVE,    defensive());
        // Freeze.
        for (var e : map.entrySet()) {
            e.setValue(List.copyOf(e.getValue()));
        }
        // Track E1 prompt 3 fix-up 2 — eager topology↔primitive
        // validation. Catches authoring gaps like industrial /
        // residential haufendorf shipping without Ring (the bug
        // that made the audit half-land). Throws at class load so
        // the mod fails to start rather than silently degrading.
        validateTopologyPrimitives(map);
        return Collections.unmodifiableMap(map);
    }

    /** Track E1 prompt 3 fix-up 2 — topology↔primitive compatibility
     *  check, fail-fast at class load.
     *
     *  <p>Hard requirements: HAUFENDORF, ANGERDORF, and RUNDLING
     *  are nucleus-around-a-loop topologies — the loop is the
     *  topology's defining feature, so they MUST declare
     *  {@link #PRIM_RING}. EINZELHOF, REIHENDORF, and CLUSTER
     *  have no hard primitive requirement.
     *
     *  <p>Throws {@link IllegalStateException} naming the
     *  strategy, topology, and missing primitive(s). Surfacing
     *  the failure at static init means the mod fails to start
     *  rather than swallowing the error at first village spawn. */
    private static void validateTopologyPrimitives(
            Map<Inclination, List<LayoutStrategy>> map) {
        for (List<LayoutStrategy> list : map.values()) {
            for (LayoutStrategy s : list) {
                Set<String> required = requiredPrimitivesFor(s.topology());
                if (required.isEmpty()) continue;
                Set<String> declared = s.primitives();
                Set<String> missing = new LinkedHashSet<>();
                for (String r : required) {
                    if (!declared.contains(r)) missing.add(r);
                }
                if (!missing.isEmpty()) {
                    throw new IllegalStateException(
                            "LayoutStrategyRegistry: strategy '" + s.id()
                                    + "' (topology=" + s.topology()
                                    + ") missing required primitive(s): " + missing
                                    + ". Topologies HAUFENDORF/ANGERDORF/RUNDLING"
                                    + " must declare Ring; add PRIM_RING to its"
                                    + " intendedPrimitives Set.");
                }
            }
        }
    }

    private static Set<String> requiredPrimitivesFor(LayoutTopology topology) {
        return switch (topology) {
            case HAUFENDORF, ANGERDORF, RUNDLING -> Set.of(PRIM_RING);
            case EINZELHOF, REIHENDORF, CLUSTER -> Set.of();
        };
    }

    // ── AGRICULTURAL ──────────────────────────────────────────────────────

    private static List<LayoutStrategy> agricultural() {
        List<LayoutStrategy> list = new ArrayList<>();
        list.add(new LayoutStrategy(
                "agricultural_reihendorf",
                Inclination.AGRICULTURAL,
                LayoutTopology.REIHENDORF,
                new AnchorPreferences(
                        Set.of(AnchorType.RIDGE_LINE, AnchorType.VALLEY_FLOOR,
                                AnchorType.WATER_EDGE, AnchorType.RIVER_BEND),
                        Set.of(AnchorType.FLAT_FERTILE, AnchorType.FOREST_EDGE),
                        true,
                        0.5),
                Set.of(PRIM_STRAIGHT, PRIM_CURVED, PRIM_SPUR),
                bindings(
                        BuildingType.TOWN_HALL,  List.of(AnchorType.FLAT_FERTILE),
                        BuildingType.FARMHOUSE,  List.of(AnchorType.FLAT_FERTILE),
                        BuildingType.HOUSE,      List.of(AnchorType.FLAT_FERTILE)),
                new StrategyConditions(ViabilityTier.HAMLET, ViabilityTier.CITY, Set.of()),
                "Linear agricultural village strung along a ridge / valley / waterline"));
        list.add(new LayoutStrategy(
                "agricultural_marschhufendorf",
                Inclination.AGRICULTURAL,
                LayoutTopology.REIHENDORF,
                new AnchorPreferences(
                        Set.of(AnchorType.WATER_EDGE, AnchorType.RIVER_BEND),
                        Set.of(AnchorType.FLAT_FERTILE),
                        true,
                        0.6),
                Set.of(PRIM_STRAIGHT, PRIM_BRIDGE, PRIM_SPUR),
                bindings(
                        BuildingType.TOWN_HALL,  List.of(AnchorType.FLAT_FERTILE),
                        BuildingType.FARMHOUSE,  List.of(AnchorType.FLAT_FERTILE),
                        BuildingType.HOUSE,      List.of(AnchorType.FLAT_FERTILE)),
                new StrategyConditions(ViabilityTier.HAMLET, ViabilityTier.CITY, Set.of()),
                "Reclaimed-marsh village along high-quality waterline; long fields perpendicular"));
        list.add(new LayoutStrategy(
                "agricultural_haufendorf",
                Inclination.AGRICULTURAL,
                LayoutTopology.HAUFENDORF,
                new AnchorPreferences(
                        Set.of(AnchorType.FLAT_FERTILE),
                        Set.of(AnchorType.WATER_EDGE, AnchorType.FOREST_EDGE),
                        false,
                        0.0),
                Set.of(PRIM_STRAIGHT, PRIM_SPUR, PRIM_RING),
                bindings(
                        BuildingType.TOWN_HALL,  List.of(AnchorType.FLAT_FERTILE),
                        BuildingType.MARKET,     List.of(AnchorType.FLAT_FERTILE),
                        BuildingType.FARMHOUSE,  List.of(AnchorType.FLAT_FERTILE),
                        BuildingType.HOUSE,      List.of(AnchorType.FLAT_FERTILE)),
                StrategyConditions.any(),
                "Organic farming-heap village around a flat-fertile nucleus"));
        list.add(fallback("agricultural_cluster_fallback", Inclination.AGRICULTURAL,
                "Generic agricultural cluster fallback"));
        return list;
    }

    // ── INDUSTRIAL ────────────────────────────────────────────────────────

    private static List<LayoutStrategy> industrial() {
        List<LayoutStrategy> list = new ArrayList<>();
        list.add(new LayoutStrategy(
                "industrial_mining",
                Inclination.INDUSTRIAL,
                LayoutTopology.CLUSTER,
                new AnchorPreferences(
                        Set.of(AnchorType.CLIFF_FACE),
                        Set.of(AnchorType.FLAT_FERTILE),
                        false,
                        0.5),
                Set.of(PRIM_STRAIGHT, PRIM_SPUR, PRIM_STAIRWAY),
                bindings(
                        BuildingType.MINE,       List.of(AnchorType.CLIFF_FACE),
                        BuildingType.BLACKSMITH, List.of(AnchorType.FLAT_FERTILE),
                        BuildingType.HOUSE,      List.of(AnchorType.FLAT_FERTILE),
                        BuildingType.MARKET,     List.of(AnchorType.FLAT_FERTILE)),
                StrategyConditions.any(),
                "Mining cluster anchored at a cliff face"));
        list.add(new LayoutStrategy(
                "industrial_woodcutter",
                Inclination.INDUSTRIAL,
                LayoutTopology.CLUSTER,
                new AnchorPreferences(
                        Set.of(AnchorType.FOREST_EDGE),
                        Set.of(AnchorType.FLAT_FERTILE, AnchorType.NATURAL_CLEARING),
                        false,
                        0.5),
                Set.of(PRIM_STRAIGHT, PRIM_SPUR, PRIM_CURVED),
                bindings(
                        BuildingType.WOODCUTTER, List.of(AnchorType.FOREST_EDGE),
                        BuildingType.HOUSE,      List.of(AnchorType.FLAT_FERTILE),
                        BuildingType.MARKET,     List.of(AnchorType.FLAT_FERTILE)),
                StrategyConditions.any(),
                "Woodcutter cluster anchored at a forest edge"));
        list.add(new LayoutStrategy(
                "industrial_haufendorf",
                Inclination.INDUSTRIAL,
                LayoutTopology.HAUFENDORF,
                new AnchorPreferences(
                        Set.of(AnchorType.FLAT_FERTILE),
                        Set.of(AnchorType.CLIFF_FACE, AnchorType.FOREST_EDGE),
                        false,
                        0.0),
                // Track E1 prompt 3 fix-up 2 — HAUFENDORF requires
                // Ring (organic heap around a nucleus). Audit gap
                // missed in prompt 3.
                Set.of(PRIM_STRAIGHT, PRIM_SPUR, PRIM_RING),
                bindings(
                        BuildingType.BLACKSMITH, List.of(AnchorType.FLAT_FERTILE),
                        BuildingType.MARKET,     List.of(AnchorType.FLAT_FERTILE),
                        BuildingType.HOUSE,      List.of(AnchorType.FLAT_FERTILE)),
                StrategyConditions.any(),
                "Industrial cluster on a flat plot with no resource anchor"));
        list.add(fallback("industrial_cluster_fallback", Inclination.INDUSTRIAL,
                "Generic industrial cluster fallback"));
        return list;
    }

    // ── CIVIC ─────────────────────────────────────────────────────────────

    private static List<LayoutStrategy> civic() {
        List<LayoutStrategy> list = new ArrayList<>();
        list.add(new LayoutStrategy(
                "civic_angerdorf",
                Inclination.CIVIC,
                LayoutTopology.ANGERDORF,
                new AnchorPreferences(
                        Set.of(AnchorType.FLAT_FERTILE),
                        Set.of(AnchorType.WATER_EDGE, AnchorType.NATURAL_CLEARING),
                        false,
                        0.5),
                Set.of(PRIM_RING, PRIM_ARC, PRIM_STRAIGHT, PRIM_SPUR, PRIM_ARM_APPROACH),
                bindings(
                        BuildingType.TOWN_HALL,  List.of(AnchorType.FLAT_FERTILE),
                        BuildingType.MARKET,     List.of(AnchorType.FLAT_FERTILE),
                        BuildingType.CHAPEL,     List.of(AnchorType.FLAT_FERTILE),
                        BuildingType.INN,        List.of(AnchorType.FLAT_FERTILE),
                        BuildingType.HOUSE,      List.of(AnchorType.FLAT_FERTILE)),
                new StrategyConditions(ViabilityTier.TOWN, ViabilityTier.CITY, Set.of()),
                "Oval-plaza civic centre with concentric ring"));
        list.add(new LayoutStrategy(
                "civic_haufendorf",
                Inclination.CIVIC,
                LayoutTopology.HAUFENDORF,
                new AnchorPreferences(
                        Set.of(AnchorType.FLAT_FERTILE),
                        Set.of(),
                        false,
                        0.0),
                Set.of(PRIM_STRAIGHT, PRIM_SPUR, PRIM_RING),
                bindings(
                        BuildingType.TOWN_HALL,  List.of(AnchorType.FLAT_FERTILE),
                        BuildingType.MARKET,     List.of(AnchorType.FLAT_FERTILE),
                        BuildingType.INN,        List.of(AnchorType.FLAT_FERTILE),
                        BuildingType.HOUSE,      List.of(AnchorType.FLAT_FERTILE)),
                StrategyConditions.any(),
                "Civic cluster for smaller settlements with no anger plaza"));
        list.add(fallback("civic_cluster_fallback", Inclination.CIVIC,
                "Generic civic cluster fallback"));
        return list;
    }

    // ── RESIDENTIAL ───────────────────────────────────────────────────────

    private static List<LayoutStrategy> residential() {
        List<LayoutStrategy> list = new ArrayList<>();
        list.add(new LayoutStrategy(
                "residential_reihendorf",
                Inclination.RESIDENTIAL,
                LayoutTopology.REIHENDORF,
                new AnchorPreferences(
                        Set.of(AnchorType.RIDGE_LINE, AnchorType.VALLEY_FLOOR,
                                AnchorType.WATER_EDGE, AnchorType.RIVER_BEND,
                                AnchorType.FOREST_EDGE),
                        Set.of(AnchorType.FLAT_FERTILE),
                        true,
                        0.4),
                Set.of(PRIM_STRAIGHT, PRIM_SPUR),
                bindings(
                        BuildingType.HOUSE,      List.of(AnchorType.FLAT_FERTILE),
                        BuildingType.WELL,       List.of(AnchorType.FLAT_FERTILE)),
                new StrategyConditions(ViabilityTier.HAMLET, ViabilityTier.CITY, Set.of()),
                "Houses strung along a road parallel to a linear feature"));
        list.add(new LayoutStrategy(
                "residential_haufendorf",
                Inclination.RESIDENTIAL,
                LayoutTopology.HAUFENDORF,
                new AnchorPreferences(
                        Set.of(AnchorType.FLAT_FERTILE),
                        Set.of(),
                        false,
                        0.0),
                // Track E1 prompt 3 fix-up 2 — HAUFENDORF requires
                // Ring (organic heap around a nucleus). Audit gap
                // missed in prompt 3.
                Set.of(PRIM_STRAIGHT, PRIM_SPUR, PRIM_RING),
                bindings(
                        BuildingType.HOUSE,      List.of(AnchorType.FLAT_FERTILE),
                        BuildingType.WELL,       List.of(AnchorType.FLAT_FERTILE)),
                StrategyConditions.any(),
                "Residential heap around a flat-fertile nucleus"));
        list.add(fallback("residential_cluster_fallback", Inclination.RESIDENTIAL,
                "Generic residential cluster fallback"));
        return list;
    }

    // ── SACRED ────────────────────────────────────────────────────────────

    private static List<LayoutStrategy> sacred() {
        List<LayoutStrategy> list = new ArrayList<>();
        list.add(new LayoutStrategy(
                "sacred_isolated",
                Inclination.SACRED,
                LayoutTopology.EINZELHOF,
                new AnchorPreferences(
                        Set.of(AnchorType.NATURAL_CLEARING, AnchorType.ISLAND,
                                AnchorType.DEFENSIBLE_PEAK),
                        Set.of(AnchorType.FLAT_FERTILE),
                        false,
                        0.5),
                Set.of(PRIM_STRAIGHT, PRIM_SPUR, PRIM_ARM_APPROACH),
                bindings(
                        BuildingType.SHRINE,     List.of(AnchorType.NATURAL_CLEARING,
                                                          AnchorType.ISLAND,
                                                          AnchorType.DEFENSIBLE_PEAK),
                        BuildingType.CHAPEL,     List.of(AnchorType.FLAT_FERTILE),
                        BuildingType.HOUSE,      List.of(AnchorType.FLAT_FERTILE)),
                new StrategyConditions(ViabilityTier.HAMLET, ViabilityTier.HAMLET, Set.of()),
                "Isolated sacred compound on a defensible / pristine anchor"));
        list.add(new LayoutStrategy(
                "sacred_angerdorf",
                Inclination.SACRED,
                LayoutTopology.ANGERDORF,
                new AnchorPreferences(
                        Set.of(AnchorType.FLAT_FERTILE, AnchorType.NATURAL_CLEARING),
                        Set.of(AnchorType.WATER_EDGE),
                        false,
                        0.4),
                Set.of(PRIM_RING, PRIM_ARC, PRIM_SPUR),
                bindings(
                        BuildingType.CHAPEL,     List.of(AnchorType.FLAT_FERTILE,
                                                          AnchorType.NATURAL_CLEARING),
                        BuildingType.SHRINE,     List.of(AnchorType.FLAT_FERTILE,
                                                          AnchorType.NATURAL_CLEARING),
                        BuildingType.HOUSE,      List.of(AnchorType.FLAT_FERTILE)),
                StrategyConditions.any(),
                "Sacred plaza-centred settlement on flat / clearing"));
        list.add(fallback("sacred_cluster_fallback", Inclination.SACRED,
                "Generic sacred cluster fallback"));
        return list;
    }

    // ── DEFENSIVE ─────────────────────────────────────────────────────────

    private static List<LayoutStrategy> defensive() {
        List<LayoutStrategy> list = new ArrayList<>();
        list.add(new LayoutStrategy(
                "defensive_keep",
                Inclination.DEFENSIVE,
                LayoutTopology.RUNDLING,
                new AnchorPreferences(
                        Set.of(AnchorType.DEFENSIBLE_PEAK),
                        Set.of(AnchorType.FLAT_FERTILE, AnchorType.DEFENSIBLE_RING),
                        false,
                        0.6),
                Set.of(PRIM_RING, PRIM_ARC, PRIM_ARM_APPROACH, PRIM_SPUR),
                bindings(
                        BuildingType.TOWN_HALL,  List.of(AnchorType.DEFENSIBLE_PEAK),
                        BuildingType.HOUSE,      List.of(AnchorType.FLAT_FERTILE),
                        BuildingType.MARKET,     List.of(AnchorType.FLAT_FERTILE)),
                new StrategyConditions(ViabilityTier.CITY, ViabilityTier.CITY, Set.of()),
                "Walled keep atop a defensible peak; settlement gates outside walls"));
        list.add(new LayoutStrategy(
                "defensive_rundling",
                Inclination.DEFENSIVE,
                LayoutTopology.RUNDLING,
                new AnchorPreferences(
                        Set.of(AnchorType.DEFENSIBLE_RING, AnchorType.FLAT_FERTILE),
                        Set.of(AnchorType.DEFENSIBLE_PEAK),
                        false,
                        0.5),
                Set.of(PRIM_RING, PRIM_ARM_APPROACH, PRIM_SPUR, PRIM_STRAIGHT),
                bindings(
                        BuildingType.TOWN_HALL,  List.of(AnchorType.DEFENSIBLE_RING,
                                                          AnchorType.FLAT_FERTILE),
                        BuildingType.HOUSE,      List.of(AnchorType.FLAT_FERTILE),
                        BuildingType.MARKET,     List.of(AnchorType.FLAT_FERTILE)),
                new StrategyConditions(ViabilityTier.TOWN, ViabilityTier.TOWN, Set.of()),
                "Circular Slavic-style rundling on a defensible ring or flat hilltop"));
        list.add(new LayoutStrategy(
                "defensive_einzelhof",
                Inclination.DEFENSIVE,
                LayoutTopology.EINZELHOF,
                new AnchorPreferences(
                        Set.of(AnchorType.DEFENSIBLE_PEAK, AnchorType.DEFENSIBLE_RING),
                        Set.of(AnchorType.FLAT_FERTILE),
                        false,
                        0.4),
                Set.of(PRIM_STRAIGHT, PRIM_ARM_APPROACH, PRIM_SPUR),
                bindings(
                        BuildingType.TOWN_HALL,  List.of(AnchorType.DEFENSIBLE_PEAK,
                                                          AnchorType.DEFENSIBLE_RING),
                        BuildingType.HOUSE,      List.of(AnchorType.FLAT_FERTILE)),
                new StrategyConditions(ViabilityTier.HAMLET, ViabilityTier.HAMLET, Set.of()),
                "Single-keep hamlet with handful of support buildings"));
        list.add(fallback("defensive_cluster_fallback", Inclination.DEFENSIVE,
                "Generic defensive cluster fallback"));
        return list;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Builds a fallback strategy: CLUSTER topology, no anchor reqs,
     *  full tier band, generic primitive set. Always passes
     *  conditions; scored at 0 so any other eligible candidate wins. */
    private static LayoutStrategy fallback(String id, Inclination inc, String desc) {
        return new LayoutStrategy(
                id,
                inc,
                LayoutTopology.CLUSTER,
                AnchorPreferences.any(),
                Set.of(PRIM_STRAIGHT, PRIM_SPUR),
                BuildingBindings.none(),
                StrategyConditions.any(),
                desc);
    }

    /** Tiny helper for building order-preserving binding maps. */
    @SafeVarargs
    private static BuildingBindings bindings(Object... pairs) {
        if (pairs.length % 2 != 0) {
            throw new IllegalArgumentException("bindings requires pairs");
        }
        Map<BuildingType, List<AnchorType>> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            @SuppressWarnings("unchecked")
            BuildingType key = (BuildingType) pairs[i];
            @SuppressWarnings("unchecked")
            List<AnchorType> val = (List<AnchorType>) pairs[i + 1];
            map.put(key, val);
        }
        return new BuildingBindings(map);
    }

    /** Internal: ensures registration order is stable + de-duped. */
    @SuppressWarnings("unused")
    private static Set<LayoutStrategy> orderedSet(Iterable<LayoutStrategy> items) {
        Set<LayoutStrategy> s = new LinkedHashSet<>();
        for (var i : items) s.add(i);
        return Collections.unmodifiableSet(s);
    }
}
