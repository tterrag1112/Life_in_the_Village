package tterrag1112.life_in_the_village.Village.Economy;

import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;

import java.util.EnumMap;
import java.util.Map;

/**
 * Starter treasury seed for a building's {@link BuildingEconomy} on
 * first creation. Consulted by
 * {@code VillageSavedData.getOrCreateBuildingEconomy} inside its
 * {@code computeIfAbsent} lambda — so the seed is paid exactly once
 * per building lifetime, never re-applied on subsequent lookups.
 *
 * <p>Defaults to zero. Civic + commercial buildings start with
 * working capital so they can pay wages / buy stock before the first
 * production cycle clears.</p>
 */
public final class BuildingStarterTable {

    private static final Map<BuildingType, Long> SEED = build();
    private static final long DEFAULT_SEED = 0L;

    private BuildingStarterTable() {}

    public static long seedFor(BuildingType type) {
        if (type == null) return DEFAULT_SEED;
        return SEED.getOrDefault(type, DEFAULT_SEED);
    }

    private static Map<BuildingType, Long> build() {
        Map<BuildingType, Long> m = new EnumMap<>(BuildingType.class);

        // ── Civic ──────────────────────────────────────────────────────
        m.put(BuildingType.TOWN_HALL,    300L);
        m.put(BuildingType.GUARD_TOWER,   80L);
        m.put(BuildingType.BARRACKS,     150L);
        m.put(BuildingType.WATCHTOWER,    60L);
        m.put(BuildingType.PRISON,        80L);
        m.put(BuildingType.BELL_TOWER,    40L);

        // ── Commerce ───────────────────────────────────────────────────
        m.put(BuildingType.MARKET,       200L);
        m.put(BuildingType.STOCKPILE,    120L);
        m.put(BuildingType.WAREHOUSE,    100L);
        m.put(BuildingType.INN,          120L);
        m.put(BuildingType.GUILD_HALL,   180L);
        m.put(BuildingType.STABLE,        80L);
        m.put(BuildingType.PIER,          60L);
        m.put(BuildingType.DOCKS,        140L);

        // ── Production ─────────────────────────────────────────────────
        m.put(BuildingType.FARMHOUSE,     60L);
        m.put(BuildingType.BLACKSMITH,   100L);
        m.put(BuildingType.CARPENTRY,     80L);
        m.put(BuildingType.STONEMASON,    80L);
        m.put(BuildingType.WEAVER,        70L);
        m.put(BuildingType.CANDLEMAKER,   60L);
        m.put(BuildingType.MILLER,        60L);
        m.put(BuildingType.BAKERY,        60L);
        m.put(BuildingType.WOODCUTTER,    50L);
        m.put(BuildingType.MINE,          80L);
        m.put(BuildingType.FISHERY,       50L);
        m.put(BuildingType.VINEYARD,      60L);
        m.put(BuildingType.WINERY,       100L);
        m.put(BuildingType.APOTHECARY,    80L);
        m.put(BuildingType.HEALER_HUT,    80L);
        m.put(BuildingType.ARMORER,      120L);
        m.put(BuildingType.TOOLSMITH,    100L);
        m.put(BuildingType.ATELIER,       80L);

        // ── Religion / scholarship ────────────────────────────────────
        m.put(BuildingType.TEMPLE,       150L);
        m.put(BuildingType.CHAPEL,        80L);
        m.put(BuildingType.SHRINE,        40L);
        m.put(BuildingType.LIBRARY,      120L);
        m.put(BuildingType.SCRIBE_WORKSHOP,  80L);
        m.put(BuildingType.SCHOLARS_RETREAT, 100L);

        // ── Capital-exclusive ─────────────────────────────────────────
        m.put(BuildingType.CASTLE,      1000L);
        m.put(BuildingType.NOBLE_MANOR,  400L);
        m.put(BuildingType.CHANCELLERY,  600L);
        m.put(BuildingType.TREASURY,    2000L);

        // ── Residential / utility — left at 0 ─────────────────────────
        // HOUSE, WELL, TOWN_SQUARE: not income-bearing.
        return m;
    }
}
