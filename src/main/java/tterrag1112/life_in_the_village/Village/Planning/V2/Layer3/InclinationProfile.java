package tterrag1112.life_in_the_village.Village.Planning.V2.Layer3;

import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Planning.V2.Inclination;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Per-inclination composition <i>config</i> consumed by
 * {@link PopulationRoster} (Layout Rework Step 3 / Stage 1).
 *
 * <p>This replaced the pre-Step-3 per-tier fixed count tables
 * ({@code Map<BuildingType,int[]>} indexed by tier ordinal). The
 * population-first roster derives counts from a target NPC population
 * (mapped from {@link tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.ViabilityTier})
 * using housing sizing + per-role ratios + hard caps. The fixed tables
 * produced pathologies the ratios/caps fix — e.g. AGRICULTURAL CITY
 * authoring CHAPEL×3 / SHRINE×2 / MARKET×2 with no caps.
 *
 * <p>Each inclination supplies:
 * <ul>
 *   <li>{@code farmhouseShare} — fraction of the <i>housing</i>
 *       population to house in FARMHOUSEs (the remainder in HOUSEs).
 *       AGRICULTURAL biases high; RESIDENTIAL/CIVIC low.</li>
 *   <li>{@code buildings} — the service / production / religious /
 *       specialty buildings this inclination builds (beyond the
 *       universal civic core TOWN_HALL and the housing types
 *       HOUSE/FARMHOUSE, which the roster always considers). The
 *       roster further gates terrain-locked entries (MINE, WOODCUTTER,
 *       …) on FeatureMap viability.</li>
 *   <li>{@code capOverrides} — per-type hard caps that override
 *       {@link PopulationRoster}'s default rule cap. This is where the
 *       religious / market caps live (the fix for "3 churches"): an
 *       AGRICULTURAL village caps CHAPEL at 2 and SHRINE at 1, while a
 *       SACRED village is allowed more.</li>
 * </ul>
 *
 * <p>The numbers here are starting values meant for in-world tuning;
 * the <i>shape</i> (which inclination biases toward what) is the part
 * meant to stay stable.
 *
 * @param inclination   which {@link Inclination} this config scores
 * @param farmhouseShare fraction (0..1) of housing population in FARMHOUSEs
 * @param buildings      non-core building set this inclination builds
 * @param capOverrides   per-type cap overrides (else the roster default)
 */
public record InclinationProfile(
        Inclination inclination,
        double farmhouseShare,
        Set<BuildingType> buildings,
        Map<BuildingType, Integer> capOverrides) {

    public InclinationProfile {
        // Deterministic ordinal iteration + immutability.
        EnumSet<BuildingType> b = buildings == null || buildings.isEmpty()
                ? EnumSet.noneOf(BuildingType.class)
                : EnumSet.copyOf(buildings);
        buildings = java.util.Collections.unmodifiableSet(b);
        EnumMap<BuildingType, Integer> caps = new EnumMap<>(BuildingType.class);
        if (capOverrides != null) caps.putAll(capOverrides);
        capOverrides = java.util.Collections.unmodifiableMap(caps);
    }

    /** Hard cap for {@code type} — the override if present, else the
     *  roster's default rule cap supplied by the caller. */
    public int capFor(BuildingType type, int defaultCap) {
        Integer c = capOverrides.get(type);
        return c != null ? c : defaultCap;
    }

    // ── Per-inclination configs ──────────────────────────────────────────

    public static final InclinationProfile AGRICULTURAL = buildAgricultural();
    public static final InclinationProfile INDUSTRIAL   = buildIndustrial();
    public static final InclinationProfile RESIDENTIAL  = buildResidential();
    public static final InclinationProfile CIVIC        = buildCivic();
    public static final InclinationProfile SACRED       = buildSacred();
    public static final InclinationProfile DEFENSIVE    = buildDefensive();

    private static Map<BuildingType, Integer> caps(Object... kv) {
        EnumMap<BuildingType, Integer> m = new EnumMap<>(BuildingType.class);
        for (int i = 0; i < kv.length; i += 2) {
            m.put((BuildingType) kv[i], (Integer) kv[i + 1]);
        }
        return m;
    }

    private static InclinationProfile buildAgricultural() {
        // Farm-village backbone: farmhouse-heavy housing, food chain
        // (bakery + miller, no river gate now), small civic + religious
        // core capped so a CITY doesn't author 3 chapels.
        return new InclinationProfile(Inclination.AGRICULTURAL, 0.65,
                EnumSet.of(
                        BuildingType.MARKET, BuildingType.INN,
                        BuildingType.CHAPEL, BuildingType.SHRINE,
                        BuildingType.BAKERY, BuildingType.MILLER,
                        BuildingType.BLACKSMITH, BuildingType.CARPENTRY,
                        BuildingType.WOODCUTTER, BuildingType.VINEYARD,
                        BuildingType.WINERY, BuildingType.STABLE,
                        BuildingType.WAREHOUSE, BuildingType.STOCKPILE),
                caps(BuildingType.CHAPEL, 2, BuildingType.SHRINE, 1,
                        BuildingType.MARKET, 2));
    }

    private static InclinationProfile buildIndustrial() {
        return new InclinationProfile(Inclination.INDUSTRIAL, 0.20,
                EnumSet.of(
                        BuildingType.MARKET, BuildingType.INN,
                        BuildingType.CHAPEL, BuildingType.SHRINE,
                        BuildingType.BAKERY, BuildingType.MILLER,
                        BuildingType.MINE, BuildingType.WOODCUTTER,
                        BuildingType.BLACKSMITH, BuildingType.CARPENTRY,
                        BuildingType.STONEMASON, BuildingType.TOOLSMITH,
                        BuildingType.ARMORER, BuildingType.WEAVER,
                        BuildingType.CANDLEMAKER, BuildingType.ATELIER,
                        BuildingType.GUILD_HALL_CRAFTSMEN,
                        BuildingType.STABLE, BuildingType.WAREHOUSE,
                        BuildingType.STOCKPILE),
                caps(BuildingType.CHAPEL, 1, BuildingType.SHRINE, 1,
                        BuildingType.MARKET, 2));
    }

    private static InclinationProfile buildResidential() {
        return new InclinationProfile(Inclination.RESIDENTIAL, 0.12,
                EnumSet.of(
                        BuildingType.MARKET, BuildingType.INN,
                        BuildingType.CHAPEL, BuildingType.SHRINE,
                        BuildingType.BAKERY, BuildingType.MILLER,
                        BuildingType.BLACKSMITH, BuildingType.CARPENTRY,
                        BuildingType.WOODCUTTER, BuildingType.WEAVER,
                        BuildingType.CANDLEMAKER, BuildingType.HEALER_HUT,
                        BuildingType.STABLE, BuildingType.WAREHOUSE,
                        BuildingType.STOCKPILE),
                caps(BuildingType.CHAPEL, 2, BuildingType.SHRINE, 1,
                        BuildingType.MARKET, 2));
    }

    private static InclinationProfile buildCivic() {
        return new InclinationProfile(Inclination.CIVIC, 0.18,
                EnumSet.of(
                        BuildingType.MARKET, BuildingType.INN,
                        BuildingType.CHAPEL, BuildingType.SHRINE,
                        BuildingType.LIBRARY, BuildingType.BELL_TOWER,
                        BuildingType.TREASURY, BuildingType.CHANCELLERY,
                        BuildingType.SCRIBE_WORKSHOP,
                        BuildingType.GUILD_HALL_MERCHANTS,
                        BuildingType.BAKERY, BuildingType.MILLER,
                        BuildingType.BLACKSMITH, BuildingType.CARPENTRY,
                        BuildingType.WOODCUTTER, BuildingType.HEALER_HUT,
                        BuildingType.STABLE, BuildingType.WAREHOUSE,
                        BuildingType.STOCKPILE),
                caps(BuildingType.CHAPEL, 2, BuildingType.SHRINE, 1,
                        BuildingType.MARKET, 3));
    }

    private static InclinationProfile buildSacred() {
        // The one inclination allowed real religious mass — higher
        // chapel/shrine caps + pilgrim inns.
        return new InclinationProfile(Inclination.SACRED, 0.22,
                EnumSet.of(
                        BuildingType.MARKET, BuildingType.INN,
                        BuildingType.CHAPEL, BuildingType.SHRINE,
                        BuildingType.TEMPLE, BuildingType.BELL_TOWER,
                        BuildingType.LIBRARY, BuildingType.SCHOLARS_RETREAT,
                        BuildingType.HEALER_HUT, BuildingType.CANDLEMAKER,
                        BuildingType.GUILD_HALL_RELIGIOUS,
                        BuildingType.BAKERY, BuildingType.MILLER,
                        BuildingType.BLACKSMITH, BuildingType.WOODCUTTER,
                        BuildingType.STABLE, BuildingType.WAREHOUSE,
                        BuildingType.STOCKPILE),
                caps(BuildingType.CHAPEL, 3, BuildingType.SHRINE, 3,
                        BuildingType.MARKET, 2, BuildingType.INN, 3,
                        BuildingType.TEMPLE, 2));
    }

    private static InclinationProfile buildDefensive() {
        return new InclinationProfile(Inclination.DEFENSIVE, 0.30,
                EnumSet.of(
                        BuildingType.MARKET, BuildingType.INN,
                        BuildingType.CHAPEL, BuildingType.SHRINE,
                        BuildingType.TREASURY, BuildingType.GUARD_TOWER,
                        BuildingType.WATCHTOWER, BuildingType.BARRACKS,
                        BuildingType.CASTLE, BuildingType.PRISON,
                        BuildingType.ARMORER, BuildingType.TOOLSMITH,
                        BuildingType.BAKERY, BuildingType.MILLER,
                        BuildingType.BLACKSMITH, BuildingType.CARPENTRY,
                        BuildingType.WOODCUTTER, BuildingType.BELL_TOWER,
                        BuildingType.STABLE, BuildingType.WAREHOUSE,
                        BuildingType.STOCKPILE),
                caps(BuildingType.CHAPEL, 1, BuildingType.SHRINE, 1,
                        BuildingType.MARKET, 2, BuildingType.GUARD_TOWER, 4,
                        BuildingType.WATCHTOWER, 3, BuildingType.BARRACKS, 3));
    }

    /** Look up the config for an {@link Inclination}. */
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
