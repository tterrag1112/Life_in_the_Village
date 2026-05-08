package tterrag1112.life_in_the_village.Village.Planning.V2.Layer3;

import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Planning.V2.Inclination;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.ViabilityTier;

import java.util.EnumMap;
import java.util.Map;

/**
 * Per-inclination building roster. {@code baseCounts} is indexed by
 * {@link ViabilityTier} ordinal (CITY=0, TOWN=1, HAMLET=2, OUTPOST=3);
 * UNVIABLE has no entries.
 *
 * <p>B2.9 — six distinct rosters now ship (one per {@link Inclination}).
 * Each emphasises its inclination's signature buildings while still
 * including the small civic/religious shell every village needs.
 *
 * @param inclination  which {@link Inclination} this roster scores
 * @param baseCounts   {@code BuildingType → int[4]} target counts per tier
 */
public record InclinationProfile(
        Inclination inclination,
        Map<BuildingType, int[]> baseCounts) {

    /** Returns the target count for {@code type} at {@code tier},
     *  or 0 if the type isn't in this inclination's roster. */
    public int countFor(BuildingType type, ViabilityTier tier) {
        int[] arr = baseCounts.get(type);
        if (arr == null) return 0;
        int idx = tier.ordinal();
        return idx < arr.length ? arr[idx] : 0;
    }

    // ── Rosters ─────────────────────────────────────────────────────────
    // Layout: int[]{ CITY, TOWN, HAMLET, OUTPOST }

    /** AGRICULTURAL — FARMHOUSE-heavy. CITY ratio FARMHOUSE:HOUSE ≈ 2:1. */
    public static final InclinationProfile AGRICULTURAL = buildAgricultural();

    /** INDUSTRIAL — BLACKSMITH / CARPENTRY / STONEMASON / WOODCUTTER / MINE. */
    public static final InclinationProfile INDUSTRIAL = buildIndustrial();

    /** RESIDENTIAL — HOUSE-heavy with a thin civic shell. */
    public static final InclinationProfile RESIDENTIAL = buildResidential();

    /** CIVIC — TOWN_HALL / MARKET / INN / LIBRARY emphasis. */
    public static final InclinationProfile CIVIC = buildCivic();

    /** SACRED — CHAPEL / SHRINE / TEMPLE / BELL_TOWER emphasis. */
    public static final InclinationProfile SACRED = buildSacred();

    /** DEFENSIVE — GUARD_TOWER / BARRACKS / WATCHTOWER emphasis. */
    public static final InclinationProfile DEFENSIVE = buildDefensive();

    private static InclinationProfile buildAgricultural() {
        EnumMap<BuildingType, int[]> m = new EnumMap<>(BuildingType.class);
        m.put(BuildingType.TOWN_HALL,  new int[]{ 1,  1, 1, 1});
        m.put(BuildingType.WELL,       new int[]{ 2,  1, 1, 0});
        m.put(BuildingType.MARKET,     new int[]{ 1,  1, 0, 0});
        m.put(BuildingType.INN,        new int[]{ 1,  1, 0, 0});
        // FARMHOUSE outnumbers HOUSE roughly 2:1 at every tier.
        m.put(BuildingType.FARMHOUSE,  new int[]{25, 12, 5, 2});
        m.put(BuildingType.HOUSE,      new int[]{12,  6, 3, 1});
        m.put(BuildingType.MILLER,     new int[]{ 3,  2, 1, 0});
        m.put(BuildingType.BAKERY,     new int[]{ 2,  1, 0, 0});
        m.put(BuildingType.STABLE,     new int[]{ 2,  1, 1, 0});
        m.put(BuildingType.BLACKSMITH, new int[]{ 1,  1, 0, 0});
        m.put(BuildingType.WOODCUTTER, new int[]{ 2,  1, 0, 0});
        m.put(BuildingType.CHAPEL,     new int[]{ 1,  0, 0, 0});
        m.put(BuildingType.SHRINE,     new int[]{ 1,  1, 0, 0});
        m.put(BuildingType.WAREHOUSE,  new int[]{ 1,  0, 0, 0});
        m.put(BuildingType.STOCKPILE,  new int[]{ 2,  1, 0, 0});
        m.put(BuildingType.VINEYARD,   new int[]{ 1,  0, 0, 0});
        m.put(BuildingType.WINERY,     new int[]{ 1,  0, 0, 0});
        return new InclinationProfile(Inclination.AGRICULTURAL, Map.copyOf(m));
    }

    private static InclinationProfile buildIndustrial() {
        EnumMap<BuildingType, int[]> m = new EnumMap<>(BuildingType.class);
        m.put(BuildingType.TOWN_HALL,  new int[]{ 1,  1, 1, 1});
        m.put(BuildingType.WELL,       new int[]{ 2,  1, 1, 0});
        m.put(BuildingType.MARKET,     new int[]{ 1,  1, 0, 0});
        m.put(BuildingType.INN,        new int[]{ 1,  1, 0, 0});
        m.put(BuildingType.HOUSE,      new int[]{25, 12, 5, 1});
        m.put(BuildingType.FARMHOUSE,  new int[]{ 4,  2, 1, 0});
        m.put(BuildingType.BLACKSMITH, new int[]{ 3,  2, 1, 1});
        m.put(BuildingType.CARPENTRY,  new int[]{ 3,  2, 1, 0});
        m.put(BuildingType.STONEMASON, new int[]{ 3,  2, 1, 0});
        m.put(BuildingType.WOODCUTTER, new int[]{ 4,  2, 1, 1});
        m.put(BuildingType.MINE,       new int[]{ 3,  2, 1, 1});
        m.put(BuildingType.TOOLSMITH,  new int[]{ 2,  1, 0, 0});
        m.put(BuildingType.ARMORER,    new int[]{ 2,  1, 0, 0});
        m.put(BuildingType.WEAVER,     new int[]{ 1,  1, 0, 0});
        m.put(BuildingType.CANDLEMAKER,new int[]{ 1,  0, 0, 0});
        m.put(BuildingType.ATELIER,    new int[]{ 1,  0, 0, 0});
        m.put(BuildingType.WAREHOUSE,  new int[]{ 2,  1, 0, 0});
        m.put(BuildingType.STOCKPILE,  new int[]{ 2,  1, 0, 0});
        m.put(BuildingType.BAKERY,     new int[]{ 1,  1, 0, 0});
        m.put(BuildingType.STABLE,     new int[]{ 1,  0, 0, 0});
        m.put(BuildingType.CHAPEL,     new int[]{ 1,  0, 0, 0});
        m.put(BuildingType.SHRINE,     new int[]{ 1,  0, 0, 0});
        m.put(BuildingType.GUILD_HALL_CRAFTSMEN, new int[]{ 1,  0, 0, 0});
        return new InclinationProfile(Inclination.INDUSTRIAL, Map.copyOf(m));
    }

    private static InclinationProfile buildResidential() {
        EnumMap<BuildingType, int[]> m = new EnumMap<>(BuildingType.class);
        m.put(BuildingType.TOWN_HALL,  new int[]{ 1,  1, 1, 1});
        m.put(BuildingType.WELL,       new int[]{ 3,  2, 1, 0});
        m.put(BuildingType.MARKET,     new int[]{ 1,  1, 0, 0});
        m.put(BuildingType.INN,        new int[]{ 2,  1, 0, 0});
        // House-dominant — twice the typical roster.
        m.put(BuildingType.HOUSE,      new int[]{40, 20, 8, 2});
        m.put(BuildingType.FARMHOUSE,  new int[]{ 6,  3, 1, 0});
        m.put(BuildingType.BAKERY,     new int[]{ 2,  1, 0, 0});
        m.put(BuildingType.STABLE,     new int[]{ 1,  1, 0, 0});
        m.put(BuildingType.BLACKSMITH, new int[]{ 2,  1, 0, 0});
        m.put(BuildingType.CARPENTRY,  new int[]{ 1,  1, 0, 0});
        m.put(BuildingType.WOODCUTTER, new int[]{ 1,  1, 0, 0});
        m.put(BuildingType.CHAPEL,     new int[]{ 2,  1, 0, 0});
        m.put(BuildingType.SHRINE,     new int[]{ 1,  1, 0, 0});
        m.put(BuildingType.WAREHOUSE,  new int[]{ 1,  0, 0, 0});
        m.put(BuildingType.STOCKPILE,  new int[]{ 1,  1, 0, 0});
        m.put(BuildingType.HEALER_HUT, new int[]{ 1,  1, 0, 0});
        m.put(BuildingType.WEAVER,     new int[]{ 1,  1, 0, 0});
        m.put(BuildingType.CANDLEMAKER,new int[]{ 1,  0, 0, 0});
        return new InclinationProfile(Inclination.RESIDENTIAL, Map.copyOf(m));
    }

    private static InclinationProfile buildCivic() {
        EnumMap<BuildingType, int[]> m = new EnumMap<>(BuildingType.class);
        m.put(BuildingType.TOWN_HALL,  new int[]{ 1,  1, 1, 1});
        m.put(BuildingType.WELL,       new int[]{ 3,  2, 1, 0});
        // Civic emphasis — markets, inns, library, bell tower.
        m.put(BuildingType.MARKET,     new int[]{ 3,  2, 1, 0});
        m.put(BuildingType.INN,        new int[]{ 3,  2, 1, 0});
        m.put(BuildingType.LIBRARY,    new int[]{ 2,  1, 0, 0});
        m.put(BuildingType.BELL_TOWER, new int[]{ 1,  1, 0, 0});
        m.put(BuildingType.HOUSE,      new int[]{22, 11, 4, 1});
        m.put(BuildingType.FARMHOUSE,  new int[]{ 5,  2, 1, 0});
        m.put(BuildingType.BAKERY,     new int[]{ 2,  1, 0, 0});
        m.put(BuildingType.STABLE,     new int[]{ 2,  1, 0, 0});
        m.put(BuildingType.BLACKSMITH, new int[]{ 1,  1, 0, 0});
        m.put(BuildingType.WOODCUTTER, new int[]{ 1,  1, 0, 0});
        m.put(BuildingType.WAREHOUSE,  new int[]{ 2,  1, 0, 0});
        m.put(BuildingType.STOCKPILE,  new int[]{ 2,  1, 0, 0});
        m.put(BuildingType.CHAPEL,     new int[]{ 1,  1, 0, 0});
        m.put(BuildingType.SHRINE,     new int[]{ 1,  0, 0, 0});
        m.put(BuildingType.HEALER_HUT, new int[]{ 1,  1, 0, 0});
        m.put(BuildingType.CHANCELLERY, new int[]{ 1, 0, 0, 0});
        m.put(BuildingType.TREASURY,   new int[]{ 1,  0, 0, 0});
        m.put(BuildingType.SCRIBE_WORKSHOP, new int[]{ 1, 0, 0, 0});
        m.put(BuildingType.GUILD_HALL_MERCHANTS, new int[]{ 1, 0, 0, 0});
        return new InclinationProfile(Inclination.CIVIC, Map.copyOf(m));
    }

    private static InclinationProfile buildSacred() {
        EnumMap<BuildingType, int[]> m = new EnumMap<>(BuildingType.class);
        m.put(BuildingType.TOWN_HALL,  new int[]{ 1,  1, 1, 1});
        m.put(BuildingType.WELL,       new int[]{ 2,  1, 1, 0});
        m.put(BuildingType.MARKET,     new int[]{ 1,  1, 0, 0});
        m.put(BuildingType.INN,        new int[]{ 1,  1, 0, 0});
        m.put(BuildingType.HOUSE,      new int[]{18,  9, 4, 1});
        m.put(BuildingType.FARMHOUSE,  new int[]{ 5,  2, 1, 0});
        // Sacred emphasis — temples, chapels, shrines, scholars.
        m.put(BuildingType.CHAPEL,     new int[]{ 3,  2, 1, 1});
        m.put(BuildingType.SHRINE,     new int[]{ 4,  2, 1, 1});
        m.put(BuildingType.TEMPLE,     new int[]{ 2,  1, 0, 0});
        m.put(BuildingType.BELL_TOWER, new int[]{ 2,  1, 0, 0});
        m.put(BuildingType.LIBRARY,    new int[]{ 1,  1, 0, 0});
        m.put(BuildingType.SCHOLARS_RETREAT, new int[]{ 1, 0, 0, 0});
        m.put(BuildingType.HEALER_HUT, new int[]{ 2,  1, 0, 0});
        m.put(BuildingType.CANDLEMAKER,new int[]{ 2,  1, 0, 0});
        m.put(BuildingType.BAKERY,     new int[]{ 1,  1, 0, 0});
        m.put(BuildingType.BLACKSMITH, new int[]{ 1,  1, 0, 0});
        m.put(BuildingType.WOODCUTTER, new int[]{ 1,  1, 0, 0});
        m.put(BuildingType.STABLE,     new int[]{ 1,  0, 0, 0});
        m.put(BuildingType.WAREHOUSE,  new int[]{ 1,  0, 0, 0});
        m.put(BuildingType.STOCKPILE,  new int[]{ 1,  0, 0, 0});
        m.put(BuildingType.GUILD_HALL_RELIGIOUS, new int[]{ 1, 0, 0, 0});
        return new InclinationProfile(Inclination.SACRED, Map.copyOf(m));
    }

    private static InclinationProfile buildDefensive() {
        EnumMap<BuildingType, int[]> m = new EnumMap<>(BuildingType.class);
        m.put(BuildingType.TOWN_HALL,  new int[]{ 1,  1, 1, 1});
        m.put(BuildingType.WELL,       new int[]{ 2,  1, 1, 0});
        m.put(BuildingType.MARKET,     new int[]{ 1,  1, 0, 0});
        m.put(BuildingType.INN,        new int[]{ 1,  1, 0, 0});
        m.put(BuildingType.HOUSE,      new int[]{16,  8, 3, 1});
        m.put(BuildingType.FARMHOUSE,  new int[]{ 4,  2, 1, 0});
        // Defensive emphasis — towers, barracks, armorer, castle.
        m.put(BuildingType.GUARD_TOWER,new int[]{ 4,  3, 2, 1});
        m.put(BuildingType.WATCHTOWER, new int[]{ 3,  2, 1, 1});
        m.put(BuildingType.BARRACKS,   new int[]{ 3,  2, 1, 0});
        m.put(BuildingType.CASTLE,     new int[]{ 1,  0, 0, 0});
        m.put(BuildingType.PRISON,     new int[]{ 1,  1, 0, 0});
        m.put(BuildingType.BLACKSMITH, new int[]{ 2,  1, 0, 0});
        m.put(BuildingType.ARMORER,    new int[]{ 2,  1, 0, 0});
        m.put(BuildingType.TOOLSMITH,  new int[]{ 2,  1, 0, 0});
        m.put(BuildingType.STABLE,     new int[]{ 2,  1, 0, 0});
        m.put(BuildingType.WAREHOUSE,  new int[]{ 1,  1, 0, 0});
        m.put(BuildingType.STOCKPILE,  new int[]{ 2,  1, 0, 0});
        m.put(BuildingType.BAKERY,     new int[]{ 1,  1, 0, 0});
        m.put(BuildingType.WOODCUTTER, new int[]{ 1,  1, 0, 0});
        m.put(BuildingType.CHAPEL,     new int[]{ 1,  1, 0, 0});
        m.put(BuildingType.SHRINE,     new int[]{ 1,  0, 0, 0});
        m.put(BuildingType.BELL_TOWER, new int[]{ 1,  0, 0, 0});
        return new InclinationProfile(Inclination.DEFENSIVE, Map.copyOf(m));
    }

    /** Look up the profile for an {@link Inclination}. B2.9 — every
     *  inclination now has a distinct roster. */
    public static InclinationProfile forInclination(Inclination inc) {
        return switch (inc) {
            case AGRICULTURAL -> AGRICULTURAL;
            case INDUSTRIAL   -> INDUSTRIAL;
            case RESIDENTIAL  -> RESIDENTIAL;
            case CIVIC        -> CIVIC;
            case SACRED       -> SACRED;
            case DEFENSIVE    -> DEFENSIVE;
        };
    }
}
