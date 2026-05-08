package tterrag1112.life_in_the_village.Village.Decoration.Adjunct;

import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Doc 02 §"Building → AdjunctPlot spec" — maps each
 * {@link BuildingType} to the ordered list of
 * {@link AdjunctPlotType}s the planner should attempt to attach to
 * it. B2.3 made the registry culture-keyed: lookups go through a
 * {@code (culture, BuildingType) → List<AdjunctPlotType>} nested
 * map with {@code "default"}-culture fallback.
 *
 * <p>Order matters per culture. The planner walks each entry in
 * sequence; earlier entries claim the back face first, later
 * entries fall back to a side. Doc 02 §"Open decisions" caps the
 * per-building count at 3 — {@link #register} enforces this within
 * a culture.</p>
 *
 * <p>B2.3 populates only the {@code "default"} culture, mirroring
 * decoration-doc 07 §"Yard catalog" + doc 08 §"Garden catalog".
 * Future cultures register via the same {@link #register} call;
 * the lookup falls back to default when a culture lacks an entry,
 * matching {@link tterrag1112.life_in_the_village.Village.Decoration.Framework.DecorationProfileRegistry}'s
 * culture-fallback contract.</p>
 *
 * <p>HOUSE → HOMESTEAD_* registrations are intentionally absent —
 * homesteads have a probability + tier gate that the static
 * registry doesn't model, so subsystem 11 adds them via a
 * different code path.</p>
 *
 * <p>FARMER also has no entry — its outdoor work happens via the
 * {@code FarmPlot} system (subsystem 10) which is village-scope,
 * not building-scope.</p>
 */
public final class AdjunctPlotRegistry {

    /** Doc 02 §"Open decisions": "max 3, hard cap in the registry". */
    public static final int MAX_PLOTS_PER_BUILDING = 3;

    /** Default-culture identifier — matches the decoration-system
     *  convention. Lookups under any unknown culture fall back to
     *  this culture's spec. */
    public static final String DEFAULT_CULTURE = "default";

    /** Outer map: culture id → per-building plot specs. Insertion-
     *  ordered so iteration is deterministic across culture. Inner
     *  map is an {@link EnumMap} keyed by {@link BuildingType}. */
    private static final Map<String, EnumMap<BuildingType, List<AdjunctPlotType>>> SPEC =
            new LinkedHashMap<>();

    static {
        // ── Default-culture spec (doc 07 §"Yard catalog" + doc 08 §"Garden catalog") ──

        // Industry (doc 07).
        register(DEFAULT_CULTURE, BuildingType.BLACKSMITH,  AdjunctPlotType.FORGE_YARD);
        register(DEFAULT_CULTURE, BuildingType.ARMORER,     AdjunctPlotType.FORGE_YARD);
        register(DEFAULT_CULTURE, BuildingType.TOOLSMITH,   AdjunctPlotType.FORGE_YARD);
        register(DEFAULT_CULTURE, BuildingType.WEAVER,      AdjunctPlotType.DRYING_RACK_YARD);
        register(DEFAULT_CULTURE, BuildingType.FISHERY,     AdjunctPlotType.DRYING_RACK_YARD);
        register(DEFAULT_CULTURE, BuildingType.CARPENTRY,   AdjunctPlotType.LOG_YARD);
        register(DEFAULT_CULTURE, BuildingType.WOODCUTTER,  AdjunctPlotType.LOG_YARD);
        register(DEFAULT_CULTURE, BuildingType.MILLER,      AdjunctPlotType.LOG_YARD);
        register(DEFAULT_CULTURE, BuildingType.STONEMASON,  AdjunctPlotType.KILN_YARD);
        register(DEFAULT_CULTURE, BuildingType.CANDLEMAKER, AdjunctPlotType.KILN_YARD);
        register(DEFAULT_CULTURE, BuildingType.MINE,        AdjunctPlotType.KILN_YARD);
        register(DEFAULT_CULTURE, BuildingType.STABLE,      AdjunctPlotType.PADDOCK);
        register(DEFAULT_CULTURE, BuildingType.BAKERY,      AdjunctPlotType.OVEN_SHED);

        // Gardens (doc 08).
        register(DEFAULT_CULTURE, BuildingType.APOTHECARY,  AdjunctPlotType.HERB_GARDEN);
        register(DEFAULT_CULTURE, BuildingType.INN,         AdjunctPlotType.KITCHEN_GARDEN);
        register(DEFAULT_CULTURE, BuildingType.TEMPLE,      AdjunctPlotType.MEDITATION_GARDEN);
        register(DEFAULT_CULTURE, BuildingType.LIBRARY,     AdjunctPlotType.MEDITATION_GARDEN);
        // Doc 08: NOBLE_MANOR gets two plots — formal garden plus a paddock.
        register(DEFAULT_CULTURE, BuildingType.NOBLE_MANOR, AdjunctPlotType.FORMAL_GARDEN);
        register(DEFAULT_CULTURE, BuildingType.NOBLE_MANOR, AdjunctPlotType.PADDOCK);
    }

    private AdjunctPlotRegistry() {}

    // ── Registration ─────────────────────────────────────────────────────

    /**
     * Adds {@code plotType} to the spec for {@code (culture,
     * buildingType)}. Idempotent for the (culture, buildingType,
     * plotType) triple — re-registering the same plot is a no-op
     * rather than appending a duplicate. Cap of {@link
     * #MAX_PLOTS_PER_BUILDING} applies per culture per building.
     *
     * @throws IllegalStateException if the building's spec list
     *         under {@code culture} would exceed
     *         {@link #MAX_PLOTS_PER_BUILDING}
     */
    public static void register(String culture, BuildingType buildingType,
                                AdjunctPlotType plotType) {
        if (culture == null || culture.isBlank()) {
            throw new IllegalArgumentException(
                    "AdjunctPlotRegistry.register: culture must be non-blank");
        }
        if (buildingType == null || plotType == null) {
            throw new IllegalArgumentException("AdjunctPlotRegistry.register: null");
        }
        EnumMap<BuildingType, List<AdjunctPlotType>> byBuilding = SPEC.computeIfAbsent(
                culture, k -> new EnumMap<>(BuildingType.class));
        List<AdjunctPlotType> list = byBuilding.computeIfAbsent(
                buildingType, k -> new ArrayList<>());
        if (list.contains(plotType)) return;
        if (list.size() >= MAX_PLOTS_PER_BUILDING) {
            throw new IllegalStateException(
                    "AdjunctPlotRegistry: " + culture + "/" + buildingType
                            + " already has " + MAX_PLOTS_PER_BUILDING
                            + " plot types registered");
        }
        list.add(plotType);
    }

    // ── Lookup ───────────────────────────────────────────────────────────

    /**
     * Returns the ordered spec for {@code buildingType} under
     * {@code culture}, falling back to {@link #DEFAULT_CULTURE}
     * when the requested culture has no entry. Returns an empty
     * list (never {@code null}) when neither culture has a spec
     * for the type — most building types have no AdjunctPlots
     * (e.g. HOUSE, TOWN_HALL, MARKET, GUILD_HALL).
     */
    public static List<AdjunctPlotType> getPlotsForBuilding(BuildingType buildingType,
                                                            String culture) {
        if (buildingType == null) return List.of();
        if (culture != null) {
            EnumMap<BuildingType, List<AdjunctPlotType>> byBuilding = SPEC.get(culture);
            if (byBuilding != null) {
                List<AdjunctPlotType> list = byBuilding.get(buildingType);
                if (list != null && !list.isEmpty()) {
                    return Collections.unmodifiableList(list);
                }
            }
        }
        // Fallback: default culture.
        EnumMap<BuildingType, List<AdjunctPlotType>> defaults = SPEC.get(DEFAULT_CULTURE);
        if (defaults == null) return List.of();
        List<AdjunctPlotType> list = defaults.get(buildingType);
        return list == null ? List.of() : Collections.unmodifiableList(list);
    }

    /** Backwards-compat overload — defaults the culture to
     *  {@link #DEFAULT_CULTURE}. Pre-B2.3 call sites that don't
     *  carry a culture context (debug commands, etc.) fall through
     *  to default. New code paths should pass an explicit culture
     *  read from village state. */
    public static List<AdjunctPlotType> getPlotsForBuilding(BuildingType buildingType) {
        return getPlotsForBuilding(buildingType, DEFAULT_CULTURE);
    }

    /** Read-only snapshot of the entire spec table for the given
     *  culture (with default-culture fallback applied per building).
     *  Useful for debug commands and tests. */
    public static Map<BuildingType, List<AdjunctPlotType>> all(String culture) {
        Map<BuildingType, List<AdjunctPlotType>> out = new EnumMap<>(BuildingType.class);
        for (BuildingType b : BuildingType.values()) {
            List<AdjunctPlotType> list = getPlotsForBuilding(b, culture);
            if (!list.isEmpty()) out.put(b, List.copyOf(list));
        }
        return Collections.unmodifiableMap(out);
    }

    /** Default-culture snapshot — preserves the pre-B2.3 zero-arg
     *  diagnostic shape. */
    public static Map<BuildingType, List<AdjunctPlotType>> all() {
        return all(DEFAULT_CULTURE);
    }

    /** Cultures that have at least one registration. Iteration order
     *  matches insertion order (so {@code "default"} comes first).
     *  Used by debug listings; consumers prefer the per-culture
     *  {@link #all(String)}. */
    public static List<String> registeredCultures() {
        return List.copyOf(SPEC.keySet());
    }
}

