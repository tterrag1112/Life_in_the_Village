package tterrag1112.life_in_the_village.Village.Decoration.Adjunct;

import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Doc 02 §"Building → AdjunctPlot spec" — maps each
 * {@link BuildingType} to the ordered list of
 * {@link AdjunctPlotType}s the realiser should attempt to attach
 * to it.
 *
 * <p>Order matters. The realiser probes each entry in sequence;
 * earlier entries claim the back face first, later entries fall
 * back to a side. Doc 02 §"Open decisions" caps the per-building
 * count at 3 — {@link #register} enforces this.</p>
 *
 * <p>Phase 0c registers a skeleton table only (the canonical
 * one-plot-per-type assignments plus the noble-manor two-plot
 * case). Subsystems 07 (industry), 08 (gardens), and 11
 * (homesteading) layer their additional registrations on top
 * during their own implementation phases.</p>
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

    private static final Map<BuildingType, List<AdjunctPlotType>> SPEC =
            new EnumMap<>(BuildingType.class);

    static {
        // ── Skeleton spec table (doc 02 §"Building → AdjunctPlot spec") ──
        register(BuildingType.APOTHECARY,  AdjunctPlotType.HERB_GARDEN);
        register(BuildingType.BLACKSMITH,  AdjunctPlotType.FORGE_YARD);
        register(BuildingType.INN,         AdjunctPlotType.KITCHEN_GARDEN);
        register(BuildingType.TEMPLE,      AdjunctPlotType.MEDITATION_GARDEN);
        register(BuildingType.STABLE,      AdjunctPlotType.PADDOCK);
        register(BuildingType.WEAVER,      AdjunctPlotType.DRYING_RACK_YARD);
        register(BuildingType.FISHERY,     AdjunctPlotType.DRYING_RACK_YARD);
        register(BuildingType.CARPENTRY,   AdjunctPlotType.LOG_YARD);
        register(BuildingType.WOODCUTTER,  AdjunctPlotType.LOG_YARD);
        register(BuildingType.STONEMASON,  AdjunctPlotType.KILN_YARD);
        register(BuildingType.CANDLEMAKER, AdjunctPlotType.KILN_YARD);
        register(BuildingType.BAKERY,      AdjunctPlotType.OVEN_SHED);
        // Miller: doc 02 mentions miller as a sack-variant of LOG_YARD.
        register(BuildingType.MILLER,      AdjunctPlotType.LOG_YARD);
        // Two-plot example (doc 02 §"Building → AdjunctPlot spec" line 53).
        register(BuildingType.NOBLE_MANOR, AdjunctPlotType.FORMAL_GARDEN);
        register(BuildingType.NOBLE_MANOR, AdjunctPlotType.PADDOCK);
    }

    private AdjunctPlotRegistry() {}

    /**
     * Adds {@code plotType} to the spec for {@code buildingType}.
     * Idempotent for the (buildingType, plotType) pair — re-
     * registering the same plot is a no-op rather than appending
     * a duplicate.
     *
     * @throws IllegalStateException if the building's spec list
     *         would exceed {@link #MAX_PLOTS_PER_BUILDING}
     */
    public static void register(BuildingType buildingType, AdjunctPlotType plotType) {
        if (buildingType == null || plotType == null) {
            throw new IllegalArgumentException("AdjunctPlotRegistry.register: null");
        }
        List<AdjunctPlotType> list = SPEC.computeIfAbsent(
                buildingType, k -> new ArrayList<>());
        if (list.contains(plotType)) return;
        if (list.size() >= MAX_PLOTS_PER_BUILDING) {
            throw new IllegalStateException(
                    "AdjunctPlotRegistry: " + buildingType
                            + " already has " + MAX_PLOTS_PER_BUILDING
                            + " plot types registered");
        }
        list.add(plotType);
    }

    /**
     * Returns the ordered spec for {@code buildingType}. Returns an
     * empty list (never {@code null}) when no plots are registered
     * for the type — most building types have no AdjunctPlots
     * (e.g. HOUSE, TOWN_HALL, MARKET, GUILD_HALL).
     */
    public static List<AdjunctPlotType> getPlotsForBuilding(BuildingType buildingType) {
        if (buildingType == null) return List.of();
        List<AdjunctPlotType> list = SPEC.get(buildingType);
        return list == null ? List.of() : Collections.unmodifiableList(list);
    }

    /** Read-only snapshot of the entire spec table. Useful for
     *  debug commands and tests. */
    public static Map<BuildingType, List<AdjunctPlotType>> all() {
        Map<BuildingType, List<AdjunctPlotType>> out =
                new EnumMap<>(BuildingType.class);
        for (var entry : SPEC.entrySet()) {
            out.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return Collections.unmodifiableMap(out);
    }
}
