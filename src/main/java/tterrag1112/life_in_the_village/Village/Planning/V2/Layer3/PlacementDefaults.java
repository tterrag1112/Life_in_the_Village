package tterrag1112.life_in_the_village.Village.Planning.V2.Layer3;

import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * In-code defaults for {@link PlacementProfile} keyed by
 * {@link BuildingType}. V1 ships profiles for the AGRICULTURAL
 * inclination's roster only — types without a profile are skipped
 * by {@link BuildingSelector} and the solver gracefully (no crash).
 *
 * <p>Numbers are tuned by feel for V1 — surface real outputs from
 * {@code /litv place} and tune in a follow-up cycle.
 */
public final class PlacementDefaults {

    private static final Map<BuildingType, PlacementProfile> PROFILES = build();

    private PlacementDefaults() {}

    /** Returns the profile for {@code type}, or {@code null} if no
     *  profile is authored. The selector / solver MUST tolerate null. */
    public static PlacementProfile get(BuildingType type) {
        return PROFILES.get(type);
    }

    public static boolean has(BuildingType type) {
        return PROFILES.containsKey(type);
    }

    private static Map<BuildingType, PlacementProfile> build() {
        EnumMap<BuildingType, PlacementProfile> m = new EnumMap<>(BuildingType.class);

        // Civic --------------------------------------------------------
        m.put(BuildingType.TOWN_HALL, new PlacementProfile(
                true, Priority.CIVIC, SizeClass.LARGE, 1.0,
                List.of(),
                terrain(Map.of(TerrainFactor.FLAT, 2.0)),
                adj(Map.of(AdjacencyFactor.NEAR_ANCHOR, 2.0)),
                Set.of(),
                List.of(new Provides(Category.CIVIC_AUTHORITY, 1)),
                List.of(new Requires(Category.HOUSING, 3, false))));

        // Track D3.1 — kingdom-tier civic anchors. Capital villages
        // (CapitalGenerator-emitted layouts) declare CASTLE as a
        // required building; non-capital villages don't get it
        // because their layouts don't list it required and the
        // structure-availability gate filters non-capital sites
        // through the existing civic-anchor centrality scoring.
        // CASTLE provides 5 CIVIC_AUTHORITY units — enough alone
        // to clear the highmarch-tier provinceSeatThreshold of 6
        // when paired with a TOWN_HALL (1) plus a NOBLE_MANOR (1).
        m.put(BuildingType.CASTLE, new PlacementProfile(
                false, Priority.CIVIC, SizeClass.LARGE, 0.9,
                List.of(BuildingType.TOWN_HALL),
                terrain(Map.of(TerrainFactor.FLAT, 1.5)),
                adj(Map.of(AdjacencyFactor.NEAR_ANCHOR, 1.5)),
                Set.of(),
                List.of(new Provides(Category.CIVIC_AUTHORITY, 5),
                        new Provides(Category.DEFENSE, 3),
                        new Provides(Category.EMPLOYMENT, 3)),
                List.of(new Requires(Category.HOUSING, 5, false))));

        // Track D3.1 — noble manor. Provides 1 CIVIC_AUTHORITY (per
        // D2's translation table); declared in capital-tier layouts;
        // existing AdjunctPlot bindings (FORMAL_GARDEN, STABLE_PADDOCK)
        // already attach to it via the AdjunctPlotRegistry.
        m.put(BuildingType.NOBLE_MANOR, new PlacementProfile(
                false, Priority.CIVIC, SizeClass.MEDIUM, 0.7,
                List.of(),
                terrain(Map.of(TerrainFactor.FLAT, 1.0)),
                adj(Map.of(AdjacencyFactor.NEAR_ANCHOR, 1.0)),
                Set.of(),
                List.of(new Provides(Category.CIVIC_AUTHORITY, 1),
                        new Provides(Category.HOUSING, 3),
                        new Provides(Category.EMPLOYMENT, 1)),
                List.of(new Requires(Category.HOUSING, 1, false))));

        // Track D3.1 — kingdom treasury. Capital villages declare it
        // required. Provides COMMERCE; a separate building from the
        // CASTLE so smaller capitals can have a treasury without
        // committing to the full castle footprint.
        m.put(BuildingType.TREASURY, new PlacementProfile(
                false, Priority.CIVIC, SizeClass.MEDIUM, 0.7,
                List.of(),
                terrain(Map.of(TerrainFactor.FLAT, 1.5)),
                adj(Map.of(AdjacencyFactor.NEAR_ANCHOR, 1.5)),
                Set.of(),
                List.of(new Provides(Category.COMMERCE, 1),
                        new Provides(Category.EMPLOYMENT, 1)),
                List.of(new Requires(Category.HOUSING, 1, false))));

        m.put(BuildingType.MARKET, new PlacementProfile(
                false, Priority.CIVIC, SizeClass.MEDIUM, 0.7,
                List.of(),
                terrain(Map.of(TerrainFactor.FLAT, 1.5)),
                adj(Map.of(AdjacencyFactor.NEAR_ANCHOR, 1.0,
                        AdjacencyFactor.NEAR_MAIN_ROAD, 1.0)),
                Set.of(),
                List.of(new Provides(Category.COMMERCE, 3)),
                List.of()));

        m.put(BuildingType.INN, new PlacementProfile(
                false, Priority.CIVIC, SizeClass.MEDIUM, 0.6,
                List.of(),
                terrain(Map.of(TerrainFactor.FLAT, 1.0)),
                adj(Map.of(AdjacencyFactor.NEAR_ANCHOR, 0.5,
                        AdjacencyFactor.NEAR_MAIN_ROAD, 1.5)),
                Set.of(),
                List.of(new Provides(Category.HOUSING, 2),
                        new Provides(Category.COMMERCE, 1),
                        new Provides(Category.FOOD, 1)),
                List.of(new Requires(Category.FOOD, 1, true))));

        m.put(BuildingType.CHAPEL, new PlacementProfile(
                false, Priority.CIVIC, SizeClass.SMALL, 0.5,
                List.of(),
                terrain(Map.of(TerrainFactor.FLAT, 1.0)),
                adj(Map.of(AdjacencyFactor.NEAR_CIVIC_CENTRE, 0.5)),
                Set.of(),
                List.of(new Provides(Category.RELIGIOUS, 1),
                        new Provides(Category.EMPLOYMENT, 1)),
                List.of(new Requires(Category.HOUSING, 1, false))));

        m.put(BuildingType.SHRINE, new PlacementProfile(
                false, Priority.CIVIC, SizeClass.SMALL, 0.3,
                List.of(),
                terrain(Map.of(TerrainFactor.FLAT, 0.5)),
                adj(Map.of(AdjacencyFactor.FAR_FROM_CIVIC_CENTRE, 0.5)),
                Set.of(),
                // SHRINE provides a partial unit of religious supply —
                // capacity floors at 0 elsewhere. Use 0 here and rely
                // on CHAPEL for the meaningful religious supply.
                List.of(new Provides(Category.RELIGIOUS, 0)),
                List.of()));

        // Infrastructure ----------------------------------------------
        m.put(BuildingType.WELL, new PlacementProfile(
                false, Priority.INFRASTRUCTURE, SizeClass.SMALL, 0.7,
                List.of(),
                terrain(Map.of(TerrainFactor.FLAT, 1.0)),
                adj(Map.of(AdjacencyFactor.NEAR_CIVIC_CENTRE, 1.0,
                        AdjacencyFactor.NEAR_MAIN_ROAD, 1.0)),
                Set.of()));

        // Production --------------------------------------------------
        // Track E1 prompt 5 — FARMHOUSE no longer consumes HOUSING.
        // A farmhouse IS the homestead of an extended farm family —
        // it houses its occupants directly. Pre-fix-up the spurious
        // Requires(HOUSING, 2) caused the ReconciliationEngine to
        // cascade-drop farmhouses on housing-tight rosters. Audit
        // principle: self-providing housing types (HOUSE, FARMHOUSE,
        // and by extension any future cottage-like building) must
        // not consume HOUSING. Workplaces that draw workers from
        // outside (TOWN_HALL, CASTLE, TREASURY) legitimately do.
        // NOBLE_MANOR is intentionally mixed (provides HOUSING 3,
        // consumes 1) representing manor staff + one external
        // housekeeper; the net +2 keeps the village housing budget
        // honest, so it's left alone.
        m.put(BuildingType.FARMHOUSE, new PlacementProfile(
                false, Priority.PRODUCTION, SizeClass.MEDIUM, 0.2,
                List.of(),
                terrain(Map.of(TerrainFactor.FLAT, 1.5,
                        TerrainFactor.NEAR_WATER, 0.5)),
                adj(Map.of(AdjacencyFactor.FAR_FROM_CIVIC_CENTRE, 0.5,
                        AdjacencyFactor.FAR_FROM_SAME_TYPE, 0.5)),
                Set.of(),
                List.of(new Provides(Category.FOOD, 3),
                        new Provides(Category.EMPLOYMENT, 2)),
                List.of()));

        // MILLER needs a river — no river → not selected.
        m.put(BuildingType.MILLER, new PlacementProfile(
                false, Priority.PRODUCTION, SizeClass.MEDIUM, 0.5,
                List.of(),
                terrain(Map.of(TerrainFactor.NEAR_WATER, 3.0,
                        TerrainFactor.FLAT, 1.0)),
                adj(Map.of()),
                Set.of(TerrainAggregate.RIVER),
                List.of(new Provides(Category.FLOUR, 3),
                        new Provides(Category.EMPLOYMENT, 1)),
                List.of(new Requires(Category.HOUSING, 1, false))));

        // BAKERY depends on MILLER being present (legacy
        // requires_present); categorical FLOUR demand is tradeable
        // so HAMLET+ stays viable even without a local MILLER.
        m.put(BuildingType.BAKERY, new PlacementProfile(
                false, Priority.PRODUCTION, SizeClass.SMALL, 0.4,
                List.of(BuildingType.MILLER),
                terrain(Map.of(TerrainFactor.FLAT, 1.0)),
                adj(Map.of(AdjacencyFactor.NEAR_MAIN_ROAD, 1.0)),
                Set.of(),
                List.of(new Provides(Category.FOOD, 2),
                        new Provides(Category.COMMERCE, 1),
                        new Provides(Category.EMPLOYMENT, 1)),
                List.of(new Requires(Category.HOUSING, 1, false),
                        new Requires(Category.FLOUR, 1, true))));

        m.put(BuildingType.STABLE, new PlacementProfile(
                false, Priority.PRODUCTION, SizeClass.MEDIUM, 0.3,
                List.of(),
                terrain(Map.of(TerrainFactor.FLAT, 1.5)),
                adj(Map.of(AdjacencyFactor.FAR_FROM_CIVIC_CENTRE, 0.3,
                        AdjacencyFactor.NEAR_MAIN_ROAD, 0.5)),
                Set.of(),
                List.of(new Provides(Category.LIVESTOCK, 1)),
                List.of(new Requires(Category.HOUSING, 1, false))));

        // BLACKSMITH depends on MINE (legacy); categorical METAL_ORE
        // and FUEL are both tradeable so HAMLET+ trade fills the
        // gap when no local MINE / WOODCUTTER.
        m.put(BuildingType.BLACKSMITH, new PlacementProfile(
                false, Priority.PRODUCTION, SizeClass.MEDIUM, 0.5,
                List.of(BuildingType.MINE),
                terrain(Map.of(TerrainFactor.FLAT, 1.0)),
                adj(Map.of(AdjacencyFactor.NEAR_MAIN_ROAD, 1.0)),
                Set.of(),
                List.of(new Provides(Category.METALWORKING, 1),
                        new Provides(Category.EMPLOYMENT, 2)),
                List.of(new Requires(Category.HOUSING, 2, false),
                        new Requires(Category.METAL_ORE, 1, true),
                        new Requires(Category.FUEL, 1, true))));

        // MINE needs exposed stone — no stone → not selected.
        m.put(BuildingType.MINE, new PlacementProfile(
                false, Priority.PRODUCTION, SizeClass.MEDIUM, 0.2,
                List.of(),
                terrain(Map.of(TerrainFactor.NEAR_STONE, 3.0)),
                adj(Map.of()),
                Set.of(TerrainAggregate.STONE_REGION),
                List.of(new Provides(Category.METAL_ORE, 3),
                        new Provides(Category.EMPLOYMENT, 3)),
                List.of(new Requires(Category.HOUSING, 3, false))));

        // CARPENTRY — wants forest if available, but doesn't require it.
        m.put(BuildingType.CARPENTRY, new PlacementProfile(
                false, Priority.PRODUCTION, SizeClass.MEDIUM, 0.4,
                List.of(),
                terrain(Map.of(TerrainFactor.FLAT, 1.0,
                        TerrainFactor.NEAR_FOREST, 0.5)),
                adj(Map.of(AdjacencyFactor.NEAR_MAIN_ROAD, 0.5)),
                Set.of(),
                List.of(new Provides(Category.CRAFTING, 1),
                        new Provides(Category.EMPLOYMENT, 2)),
                List.of(new Requires(Category.HOUSING, 2, false),
                        new Requires(Category.WOOD, 1, true))));

        // WOODCUTTER — requires a forest region in the scan.
        m.put(BuildingType.WOODCUTTER, new PlacementProfile(
                false, Priority.PRODUCTION, SizeClass.SMALL, 0.2,
                List.of(),
                terrain(Map.of(TerrainFactor.NEAR_FOREST, 2.0,
                        TerrainFactor.FLAT, 0.5)),
                adj(Map.of()),
                Set.of(TerrainAggregate.FOREST_REGION),
                List.of(new Provides(Category.WOOD, 3),
                        new Provides(Category.FUEL, 3),
                        new Provides(Category.EMPLOYMENT, 2)),
                List.of(new Requires(Category.HOUSING, 2, false))));

        m.put(BuildingType.WAREHOUSE, new PlacementProfile(
                false, Priority.PRODUCTION, SizeClass.LARGE, 0.4,
                List.of(),
                terrain(Map.of(TerrainFactor.FLAT, 1.5)),
                adj(Map.of(AdjacencyFactor.NEAR_MAIN_ROAD, 1.5)),
                Set.of()));

        m.put(BuildingType.STOCKPILE, new PlacementProfile(
                false, Priority.PRODUCTION, SizeClass.MEDIUM, 0.3,
                List.of(),
                terrain(Map.of(TerrainFactor.FLAT, 1.0)),
                adj(Map.of(AdjacencyFactor.NEAR_MAIN_ROAD, 0.5)),
                Set.of(),
                // V1: STOCKPILE has no clear provides yet; spec leaves
                // it empty. Still consumes housing for its keeper.
                List.of(),
                List.of(new Requires(Category.HOUSING, 1, false))));

        // Residential -------------------------------------------------
        m.put(BuildingType.HOUSE, new PlacementProfile(
                false, Priority.RESIDENTIAL, SizeClass.MEDIUM, 0.4,
                List.of(),
                terrain(Map.of(TerrainFactor.FLAT, 1.0)),
                adj(Map.of(AdjacencyFactor.NEAR_MAIN_ROAD, 0.5,
                        AdjacencyFactor.FAR_FROM_SAME_TYPE, 0.3)),
                Set.of(),
                List.of(new Provides(Category.HOUSING, 4)),
                List.of()));

        return Map.copyOf(m);
    }

    private static Map<TerrainFactor, Double> terrain(Map<TerrainFactor, Double> in) {
        return in.isEmpty() ? Map.of() : new EnumMap<>(in);
    }

    private static Map<AdjacencyFactor, Double> adj(Map<AdjacencyFactor, Double> in) {
        return in.isEmpty() ? Map.of() : new EnumMap<>(in);
    }
}
