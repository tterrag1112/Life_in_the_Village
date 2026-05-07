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
                Set.of()));

        m.put(BuildingType.MARKET, new PlacementProfile(
                false, Priority.CIVIC, SizeClass.MEDIUM, 0.7,
                List.of(),
                terrain(Map.of(TerrainFactor.FLAT, 1.5)),
                adj(Map.of(AdjacencyFactor.NEAR_ANCHOR, 1.0,
                        AdjacencyFactor.NEAR_MAIN_ROAD, 1.0)),
                Set.of()));

        m.put(BuildingType.INN, new PlacementProfile(
                false, Priority.CIVIC, SizeClass.MEDIUM, 0.6,
                List.of(),
                terrain(Map.of(TerrainFactor.FLAT, 1.0)),
                adj(Map.of(AdjacencyFactor.NEAR_ANCHOR, 0.5,
                        AdjacencyFactor.NEAR_MAIN_ROAD, 1.5)),
                Set.of()));

        m.put(BuildingType.CHAPEL, new PlacementProfile(
                false, Priority.CIVIC, SizeClass.SMALL, 0.5,
                List.of(),
                terrain(Map.of(TerrainFactor.FLAT, 1.0)),
                adj(Map.of(AdjacencyFactor.NEAR_CIVIC_CENTRE, 0.5)),
                Set.of()));

        m.put(BuildingType.SHRINE, new PlacementProfile(
                false, Priority.CIVIC, SizeClass.SMALL, 0.3,
                List.of(),
                terrain(Map.of(TerrainFactor.FLAT, 0.5)),
                adj(Map.of(AdjacencyFactor.FAR_FROM_CIVIC_CENTRE, 0.5)),
                Set.of()));

        // Infrastructure ----------------------------------------------
        m.put(BuildingType.WELL, new PlacementProfile(
                false, Priority.INFRASTRUCTURE, SizeClass.SMALL, 0.7,
                List.of(),
                terrain(Map.of(TerrainFactor.FLAT, 1.0)),
                adj(Map.of(AdjacencyFactor.NEAR_CIVIC_CENTRE, 1.0,
                        AdjacencyFactor.NEAR_MAIN_ROAD, 1.0)),
                Set.of()));

        // Production --------------------------------------------------
        m.put(BuildingType.FARMHOUSE, new PlacementProfile(
                false, Priority.PRODUCTION, SizeClass.MEDIUM, 0.2,
                List.of(),
                terrain(Map.of(TerrainFactor.FLAT, 1.5,
                        TerrainFactor.NEAR_WATER, 0.5)),
                adj(Map.of(AdjacencyFactor.FAR_FROM_CIVIC_CENTRE, 0.5,
                        AdjacencyFactor.FAR_FROM_SAME_TYPE, 0.5)),
                Set.of()));

        // MILLER needs a river — no river → not selected.
        m.put(BuildingType.MILLER, new PlacementProfile(
                false, Priority.PRODUCTION, SizeClass.MEDIUM, 0.5,
                List.of(),
                terrain(Map.of(TerrainFactor.NEAR_WATER, 3.0,
                        TerrainFactor.FLAT, 1.0)),
                adj(Map.of()),
                Set.of(TerrainAggregate.RIVER)));

        // BAKERY depends on MILLER being present.
        m.put(BuildingType.BAKERY, new PlacementProfile(
                false, Priority.PRODUCTION, SizeClass.SMALL, 0.4,
                List.of(BuildingType.MILLER),
                terrain(Map.of(TerrainFactor.FLAT, 1.0)),
                adj(Map.of(AdjacencyFactor.NEAR_MAIN_ROAD, 1.0)),
                Set.of()));

        m.put(BuildingType.STABLE, new PlacementProfile(
                false, Priority.PRODUCTION, SizeClass.MEDIUM, 0.3,
                List.of(),
                terrain(Map.of(TerrainFactor.FLAT, 1.5)),
                adj(Map.of(AdjacencyFactor.FAR_FROM_CIVIC_CENTRE, 0.3,
                        AdjacencyFactor.NEAR_MAIN_ROAD, 0.5)),
                Set.of()));

        // BLACKSMITH depends on MINE.
        m.put(BuildingType.BLACKSMITH, new PlacementProfile(
                false, Priority.PRODUCTION, SizeClass.MEDIUM, 0.5,
                List.of(BuildingType.MINE),
                terrain(Map.of(TerrainFactor.FLAT, 1.0)),
                adj(Map.of(AdjacencyFactor.NEAR_MAIN_ROAD, 1.0)),
                Set.of()));

        // MINE needs exposed stone — no stone → not selected.
        m.put(BuildingType.MINE, new PlacementProfile(
                false, Priority.PRODUCTION, SizeClass.MEDIUM, 0.2,
                List.of(),
                terrain(Map.of(TerrainFactor.NEAR_STONE, 3.0)),
                adj(Map.of()),
                Set.of(TerrainAggregate.STONE_REGION)));

        // CARPENTRY — wants forest if available, but doesn't require it.
        m.put(BuildingType.CARPENTRY, new PlacementProfile(
                false, Priority.PRODUCTION, SizeClass.MEDIUM, 0.4,
                List.of(),
                terrain(Map.of(TerrainFactor.FLAT, 1.0,
                        TerrainFactor.NEAR_FOREST, 0.5)),
                adj(Map.of(AdjacencyFactor.NEAR_MAIN_ROAD, 0.5)),
                Set.of()));

        // WOODCUTTER — requires a forest region in the scan.
        m.put(BuildingType.WOODCUTTER, new PlacementProfile(
                false, Priority.PRODUCTION, SizeClass.SMALL, 0.2,
                List.of(),
                terrain(Map.of(TerrainFactor.NEAR_FOREST, 2.0,
                        TerrainFactor.FLAT, 0.5)),
                adj(Map.of()),
                Set.of(TerrainAggregate.FOREST_REGION)));

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
                Set.of()));

        // Residential -------------------------------------------------
        m.put(BuildingType.HOUSE, new PlacementProfile(
                false, Priority.RESIDENTIAL, SizeClass.MEDIUM, 0.4,
                List.of(),
                terrain(Map.of(TerrainFactor.FLAT, 1.0)),
                adj(Map.of(AdjacencyFactor.NEAR_MAIN_ROAD, 0.5,
                        AdjacencyFactor.FAR_FROM_SAME_TYPE, 0.3)),
                Set.of()));

        return Map.copyOf(m);
    }

    private static Map<TerrainFactor, Double> terrain(Map<TerrainFactor, Double> in) {
        return in.isEmpty() ? Map.of() : new EnumMap<>(in);
    }

    private static Map<AdjacencyFactor, Double> adj(Map<AdjacencyFactor, Double> in) {
        return in.isEmpty() ? Map.of() : new EnumMap<>(in);
    }
}
