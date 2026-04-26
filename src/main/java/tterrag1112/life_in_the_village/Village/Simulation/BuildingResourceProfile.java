package tterrag1112.life_in_the_village.Village.Simulation;

import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;

import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * Phase 4 doc 25 — per-building daily contribution to the village
 * resource sim. Read by {@link VillageSimEngine#syncFromReal} as it
 * walks each village's building list and summed into the per-category
 * production / consumption totals.
 *
 * <p>v1 ships first-pass numbers per the spec's example table. Several
 * are educated guesses; the Phase 5 content tuning pass will revisit.
 * Buildings without an entry in {@link #TABLE} contribute nothing —
 * the engine treats a missing profile as neutral (spec line 214).</p>
 *
 * <p>Per spec line 217: a category whose net per-day for a single
 * building goes negative is clamped to 0 by the engine — it just
 * means consumption outweighs production for that step.</p>
 */
public record BuildingResourceProfile(
        Map<ResourceCategory, Float> productionPerDay,
        Map<ResourceCategory, Float> consumptionPerDay
) {
    public BuildingResourceProfile {
        productionPerDay  = immutableEnumMap(productionPerDay);
        consumptionPerDay = immutableEnumMap(consumptionPerDay);
    }

    private static Map<ResourceCategory, Float> immutableEnumMap(Map<ResourceCategory, Float> in) {
        if (in == null || in.isEmpty()) return Collections.emptyMap();
        EnumMap<ResourceCategory, Float> copy = new EnumMap<>(ResourceCategory.class);
        copy.putAll(in);
        return Collections.unmodifiableMap(copy);
    }

    /** Convenience constructor: empty consumption. */
    public static BuildingResourceProfile produces(Map<ResourceCategory, Float> prod) {
        return new BuildingResourceProfile(prod, Map.of());
    }

    /** Convenience constructor: empty production (e.g. HOUSE — pure consumer). */
    public static BuildingResourceProfile consumes(Map<ResourceCategory, Float> cons) {
        return new BuildingResourceProfile(Map.of(), cons);
    }

    public static final Map<BuildingType, BuildingResourceProfile> TABLE = build();

    public static BuildingResourceProfile get(BuildingType type) {
        if (type == null) return null;
        return TABLE.get(type);
    }

    private static Map<BuildingType, BuildingResourceProfile> build() {
        Map<BuildingType, BuildingResourceProfile> m = new HashMap<>();

        // ── Primary food production ────────────────────────────────────────
        m.put(BuildingType.FARMHOUSE, new BuildingResourceProfile(
                m1(ResourceCategory.FOOD, 8f, ResourceCategory.SEEDS, 2f),
                m1(ResourceCategory.SEEDS, 1f, ResourceCategory.FOOD, 1f)));
        m.put(BuildingType.BAKERY, new BuildingResourceProfile(
                m1(ResourceCategory.FOOD, 4f),
                m1(ResourceCategory.FOOD, 2f)));
        m.put(BuildingType.MILLER, new BuildingResourceProfile(
                m1(ResourceCategory.FOOD, 3f),
                m1(ResourceCategory.FOOD, 4f)));
        m.put(BuildingType.FISHERY, new BuildingResourceProfile(
                m1(ResourceCategory.FOOD, 5f),
                m1()));
        m.put(BuildingType.VINEYARD, new BuildingResourceProfile(
                m1(ResourceCategory.LUXURY, 1f, ResourceCategory.FOOD, 2f),
                m1()));
        m.put(BuildingType.WINERY, new BuildingResourceProfile(
                m1(ResourceCategory.LUXURY, 3f, ResourceCategory.COIN_INFLUX, 2f),
                m1(ResourceCategory.FOOD, 1f)));

        // ── Material extraction / heavy industry ──────────────────────────
        m.put(BuildingType.MINE, new BuildingResourceProfile(
                m1(ResourceCategory.BUILDING_MATERIALS, 6f),
                m1(ResourceCategory.TOOLS, 0.5f, ResourceCategory.FOOD, 2f)));
        m.put(BuildingType.WOODCUTTER, new BuildingResourceProfile(
                m1(ResourceCategory.BUILDING_MATERIALS, 4f),
                m1(ResourceCategory.TOOLS, 0.3f)));
        m.put(BuildingType.STONEMASON, new BuildingResourceProfile(
                m1(ResourceCategory.BUILDING_MATERIALS, 3f),
                m1(ResourceCategory.TOOLS, 0.3f)));

        // ── Smithing / craft ───────────────────────────────────────────────
        m.put(BuildingType.BLACKSMITH, new BuildingResourceProfile(
                m1(ResourceCategory.TOOLS, 3f, ResourceCategory.WEAPONS, 1f),
                m1(ResourceCategory.BUILDING_MATERIALS, 4f)));
        m.put(BuildingType.CARPENTRY, new BuildingResourceProfile(
                m1(ResourceCategory.TOOLS, 2f, ResourceCategory.BUILDING_MATERIALS, 1f),
                m1(ResourceCategory.BUILDING_MATERIALS, 3f)));
        m.put(BuildingType.ARMORER, new BuildingResourceProfile(
                m1(ResourceCategory.WEAPONS, 3f),
                m1(ResourceCategory.BUILDING_MATERIALS, 4f, ResourceCategory.TOOLS, 0.3f)));
        m.put(BuildingType.TOOLSMITH, new BuildingResourceProfile(
                m1(ResourceCategory.TOOLS, 4f),
                m1(ResourceCategory.BUILDING_MATERIALS, 3f)));
        m.put(BuildingType.ATELIER, new BuildingResourceProfile(
                m1(ResourceCategory.LUXURY, 2f),
                m1(ResourceCategory.CLOTH, 1f, ResourceCategory.BUILDING_MATERIALS, 0.5f)));
        m.put(BuildingType.WEAVER, new BuildingResourceProfile(
                m1(ResourceCategory.CLOTH, 3f),
                m1(ResourceCategory.LIVESTOCK, 0.5f, ResourceCategory.BUILDING_MATERIALS, 0.5f)));
        m.put(BuildingType.CANDLEMAKER, new BuildingResourceProfile(
                m1(ResourceCategory.LITURGICAL, 2f),
                m1(ResourceCategory.LIVESTOCK, 0.3f)));

        // ── Stockpile / commerce / inn ─────────────────────────────────────
        m.put(BuildingType.MARKET, new BuildingResourceProfile(
                m1(ResourceCategory.COIN_INFLUX, 3f),
                m1()));
        m.put(BuildingType.STOCKPILE, new BuildingResourceProfile(
                m1(),
                m1(ResourceCategory.BUILDING_MATERIALS, 0.5f)));
        m.put(BuildingType.WAREHOUSE, new BuildingResourceProfile(
                m1(),
                m1(ResourceCategory.BUILDING_MATERIALS, 0.5f)));
        m.put(BuildingType.INN, new BuildingResourceProfile(
                m1(ResourceCategory.COIN_INFLUX, 4f),
                m1(ResourceCategory.FOOD, 2f, ResourceCategory.CLOTH, 0.5f)));
        m.put(BuildingType.STABLE, new BuildingResourceProfile(
                m1(ResourceCategory.LIVESTOCK, 1f, ResourceCategory.COIN_INFLUX, 1f),
                m1(ResourceCategory.FOOD, 2f)));
        m.put(BuildingType.PIER, new BuildingResourceProfile(
                m1(ResourceCategory.COIN_INFLUX, 2f, ResourceCategory.FOOD, 1f),
                m1()));
        m.put(BuildingType.DOCKS, new BuildingResourceProfile(
                m1(ResourceCategory.COIN_INFLUX, 4f),
                m1(ResourceCategory.BUILDING_MATERIALS, 0.5f)));

        // ── Civic / military ──────────────────────────────────────────────
        m.put(BuildingType.TOWN_HALL, new BuildingResourceProfile(
                m1(ResourceCategory.COIN_INFLUX, 2f),
                m1(ResourceCategory.PAPER, 0.5f)));
        m.put(BuildingType.GUILD_HALL, new BuildingResourceProfile(
                m1(ResourceCategory.COIN_INFLUX, 2f),
                m1(ResourceCategory.PAPER, 0.3f)));
        m.put(BuildingType.GUARD_TOWER, new BuildingResourceProfile(
                m1(),
                m1(ResourceCategory.WEAPONS, 0.3f, ResourceCategory.FOOD, 1f)));
        m.put(BuildingType.WATCHTOWER, new BuildingResourceProfile(
                m1(),
                m1(ResourceCategory.FOOD, 0.5f)));
        m.put(BuildingType.BARRACKS, new BuildingResourceProfile(
                m1(),
                m1(ResourceCategory.WEAPONS, 1f, ResourceCategory.FOOD, 4f)));
        m.put(BuildingType.PRISON, new BuildingResourceProfile(
                m1(),
                m1(ResourceCategory.FOOD, 1f)));
        m.put(BuildingType.BELL_TOWER, new BuildingResourceProfile(
                m1(),
                m1()));
        m.put(BuildingType.WELL, new BuildingResourceProfile(
                m1(),
                m1()));

        // ── Religion ───────────────────────────────────────────────────────
        m.put(BuildingType.TEMPLE, new BuildingResourceProfile(
                m1(ResourceCategory.COIN_INFLUX, 5f),
                m1(ResourceCategory.LITURGICAL, 2f, ResourceCategory.FOOD, 1f)));
        m.put(BuildingType.CHAPEL, new BuildingResourceProfile(
                m1(ResourceCategory.COIN_INFLUX, 2f),
                m1(ResourceCategory.LITURGICAL, 1f)));
        m.put(BuildingType.SHRINE, new BuildingResourceProfile(
                m1(ResourceCategory.COIN_INFLUX, 1f),
                m1(ResourceCategory.LITURGICAL, 0.5f)));

        // ── Scholarship / scribal ─────────────────────────────────────────
        m.put(BuildingType.LIBRARY, new BuildingResourceProfile(
                m1(ResourceCategory.COIN_INFLUX, 1f),
                m1(ResourceCategory.PAPER, 1f)));
        m.put(BuildingType.SCRIBE_WORKSHOP, new BuildingResourceProfile(
                m1(ResourceCategory.PAPER, 1f),
                m1(ResourceCategory.PAPER, 0.5f, ResourceCategory.BUILDING_MATERIALS, 0.2f)));
        m.put(BuildingType.SCHOLARS_RETREAT, new BuildingResourceProfile(
                m1(ResourceCategory.PAPER, 0.5f),
                m1(ResourceCategory.PAPER, 0.3f)));

        // ── Medicine ───────────────────────────────────────────────────────
        m.put(BuildingType.APOTHECARY, new BuildingResourceProfile(
                m1(ResourceCategory.MEDICINE, 2f, ResourceCategory.COIN_INFLUX, 1f),
                m1(ResourceCategory.BUILDING_MATERIALS, 0.3f)));
        m.put(BuildingType.HEALER_HUT, new BuildingResourceProfile(
                m1(ResourceCategory.MEDICINE, 3f, ResourceCategory.COIN_INFLUX, 1f),
                m1(ResourceCategory.CLOTH, 0.5f, ResourceCategory.BUILDING_MATERIALS, 0.2f)));

        // ── Capital ────────────────────────────────────────────────────────
        m.put(BuildingType.CASTLE, new BuildingResourceProfile(
                m1(),
                m1(ResourceCategory.FOOD, 6f, ResourceCategory.WEAPONS, 1f,
                        ResourceCategory.LUXURY, 1f)));
        m.put(BuildingType.NOBLE_MANOR, new BuildingResourceProfile(
                m1(),
                m1(ResourceCategory.FOOD, 4f, ResourceCategory.LUXURY, 1f,
                        ResourceCategory.CLOTH, 0.5f)));
        m.put(BuildingType.CHANCELLERY, new BuildingResourceProfile(
                m1(ResourceCategory.COIN_INFLUX, 3f),
                m1(ResourceCategory.PAPER, 1.5f)));
        m.put(BuildingType.TREASURY, new BuildingResourceProfile(
                m1(),
                m1()));

        // ── Residential ────────────────────────────────────────────────────
        m.put(BuildingType.HOUSE, new BuildingResourceProfile(
                m1(),
                m1(ResourceCategory.FOOD, 2f, ResourceCategory.CLOTH, 0.3f,
                        ResourceCategory.BUILDING_MATERIALS, 0.1f)));
        m.put(BuildingType.TOWN_SQUARE, new BuildingResourceProfile(
                m1(),
                m1()));

        return Collections.unmodifiableMap(m);
    }

    /** Builds a small {@link EnumMap} from up to four (category, value)
     *  pairs. Ergonomic enough for the inline table without forcing
     *  every entry through Map.of/Map.ofEntries gymnastics. */
    private static Map<ResourceCategory, Float> m1() {
        return Map.of();
    }
    private static Map<ResourceCategory, Float> m1(ResourceCategory c, float v) {
        return Map.of(c, v);
    }
    private static Map<ResourceCategory, Float> m1(ResourceCategory c1, float v1,
                                                   ResourceCategory c2, float v2) {
        EnumMap<ResourceCategory, Float> m = new EnumMap<>(ResourceCategory.class);
        m.put(c1, v1); m.merge(c2, v2, Float::sum);
        return m;
    }
    private static Map<ResourceCategory, Float> m1(ResourceCategory c1, float v1,
                                                   ResourceCategory c2, float v2,
                                                   ResourceCategory c3, float v3) {
        EnumMap<ResourceCategory, Float> m = new EnumMap<>(ResourceCategory.class);
        m.put(c1, v1);
        m.merge(c2, v2, Float::sum);
        m.merge(c3, v3, Float::sum);
        return m;
    }
}
