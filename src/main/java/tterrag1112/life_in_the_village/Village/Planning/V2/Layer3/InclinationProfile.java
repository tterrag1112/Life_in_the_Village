package tterrag1112.life_in_the_village.Village.Planning.V2.Layer3;

import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Planning.V2.Inclination;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.ViabilityTier;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * Per-inclination building roster. {@code baseCounts} is indexed by
 * {@link ViabilityTier} ordinal (CITY=0, TOWN=1, HAMLET=2, OUTPOST=3);
 * UNVIABLE has no entries.
 *
 * <h3>Composition principles (Track E1 prompt 5 rebalance)</h3>
 * The rebalance turned the rural/urban shape from "FARMHOUSE-dominant
 * everywhere" to the ACOUP-flavoured medieval-economy distribution:
 *
 * <ul>
 *   <li><b>Farmhouses scale sub-linearly with tier.</b> CITY has
 *       more FARMHOUSEs than HAMLET in absolute terms but
 *       proportionally far fewer — cities import food from outlying
 *       agricultural villages rather than growing it within their
 *       walls. Pre-rebalance: CITY AGRICULTURAL had 25 FARMHOUSEs
 *       and 12 HOUSEs (ratio 2:1). Post: 7 and 45 (ratio ≈ 1:6).</li>
 *   <li><b>Houses scale super-linearly with tier.</b> Most growth
 *       from HAMLET→TOWN→CITY is non-farm population — artisans,
 *       merchants, civic workers, day labourers. CITY HOUSE counts
 *       are 3-6× HAMLET counts.</li>
 *   <li><b>Civic buildings scale roughly linearly.</b> CITY has ~3×
 *       HAMLET's civic count with diminishing returns — a CITY does
 *       have more markets and inns but not 10×.</li>
 *   <li><b>Sacred sites get pilgrim infrastructure.</b> SACRED
 *       inclination has extra INNs scaled with religious importance
 *       — pilgrims need somewhere to sleep.</li>
 *   <li><b>Resource buildings only ride resource-anchored
 *       strategies.</b> MINE / WOODCUTTER appear in INDUSTRIAL
 *       rosters but {@link LayoutStrategy#excludedBuildings()}
 *       on {@code industrial_haufendorf} (the resource-anchorless
 *       fallback) strips them at selection time.</li>
 *   <li><b>MILLER tracks BAKERY at every tier where BAKERY is
 *       authored.</b> Pre-rebalance, 5 of 6 inclinations had BAKERY
 *       without MILLER, which made ReconciliationEngine cascade-
 *       trade-fulfil BAKERY for FLOUR on every site. Adding MILLER
 *       counts equal to BAKERY counts resolves the cascade at its
 *       source.</li>
 * </ul>
 *
 * <p>Note: the values below are <i>target initial selection</i>
 * before reconciliation drops, trade-fulfillment, and the
 * {@link BuildingSelector#sampleCount sampler}'s ±25% variance.
 * The intent is the <i>shape</i> — rural/urban ratios, civic-core
 * scaling, sacred pilgrimage support. Tune individual numbers
 * freely; the principles above are what should stay stable.
 *
 * @param inclination  which {@link Inclination} this roster scores
 * @param baseCounts   {@code BuildingType → int[4]} target counts per tier
 */
public record InclinationProfile(
        Inclination inclination,
        Map<BuildingType, int[]> baseCounts) {

    /** Track E1 — composition determinism. Canonical constructor
     *  wraps the incoming map in an {@link EnumMap} so iteration of
     *  {@code baseCounts.keySet()} is by {@link BuildingType#ordinal()}
     *  declaration order, not the JVM-startup-salted iteration of
     *  {@link Map#copyOf}. {@code Map.copyOf(EnumMap)} silently
     *  promotes to an {@code ImmutableCollections.MapN}, whose
     *  iteration order varies across JVM starts; that fed
     *  {@link BuildingSelector#select}'s shared {@code Random} draws
     *  with a different per-type order each run, jittering the
     *  per-type sampled counts (e.g. FARMHOUSE 4↔5 in HAMLET/AGRI).
     *  EnumMap wrapped in {@code Collections.unmodifiableMap} gives
     *  immutability with deterministic ordinal iteration. */
    public InclinationProfile {
        if (baseCounts == null || baseCounts.isEmpty()) {
            baseCounts = Collections.unmodifiableMap(
                    new EnumMap<>(BuildingType.class));
        } else {
            EnumMap<BuildingType, int[]> stable =
                    new EnumMap<>(BuildingType.class);
            stable.putAll(baseCounts);
            baseCounts = Collections.unmodifiableMap(stable);
        }
    }

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

    /** AGRICULTURAL — FARMHOUSEs plus city-scale residential population. */
    public static final InclinationProfile AGRICULTURAL = buildAgricultural();

    /** INDUSTRIAL — workshops, MINE/WOODCUTTER (filtered out by
     *  {@code industrial_haufendorf} when no resource anchor). */
    public static final InclinationProfile INDUSTRIAL = buildIndustrial();

    /** RESIDENTIAL — HOUSE-dominant; smallest farm presence. */
    public static final InclinationProfile RESIDENTIAL = buildResidential();

    /** CIVIC — MARKET / INN / civic-authority emphasis. */
    public static final InclinationProfile CIVIC = buildCivic();

    /** SACRED — CHAPEL / SHRINE / TEMPLE + pilgrim INNs. */
    public static final InclinationProfile SACRED = buildSacred();

    /** DEFENSIVE — GUARD_TOWER / BARRACKS / WATCHTOWER + small keep. */
    public static final InclinationProfile DEFENSIVE = buildDefensive();

    private static InclinationProfile buildAgricultural() {
        EnumMap<BuildingType, int[]> m = new EnumMap<>(BuildingType.class);
        // Civic core.
        m.put(BuildingType.TOWN_HALL,  new int[]{ 1,  1, 1, 1});
        m.put(BuildingType.WELL,       new int[]{ 2,  1, 1, 0});
        m.put(BuildingType.MARKET,     new int[]{ 2,  1, 0, 0});
        m.put(BuildingType.INN,        new int[]{ 3,  1, 0, 0});
        m.put(BuildingType.TREASURY,   new int[]{ 1,  0, 0, 0});
        // Headline residential.  CITY HOUSE 45 dwarfs FARMHOUSE 7
        // (medieval city: agricultural workers are a minority of the
        // population; food comes from the surrounding villages).
        m.put(BuildingType.FARMHOUSE,  new int[]{ 7,  6, 4, 1});
        m.put(BuildingType.HOUSE,      new int[]{45, 17, 5, 1});
        // Food chain.  MILLER tracks BAKERY so reconciliation doesn't
        // cascade-trade FLOUR.
        m.put(BuildingType.BAKERY,     new int[]{ 2,  1, 0, 0});
        m.put(BuildingType.MILLER,     new int[]{ 3,  2, 1, 0});
        // Production tail.
        m.put(BuildingType.BLACKSMITH, new int[]{ 2,  1, 0, 0});
        m.put(BuildingType.CARPENTRY,  new int[]{ 1,  0, 0, 0});
        m.put(BuildingType.WOODCUTTER, new int[]{ 2,  1, 0, 0});
        m.put(BuildingType.VINEYARD,   new int[]{ 1,  0, 0, 0});
        m.put(BuildingType.WINERY,     new int[]{ 1,  0, 0, 0});
        // Religious.
        m.put(BuildingType.CHAPEL,     new int[]{ 3,  1, 0, 0});
        m.put(BuildingType.SHRINE,     new int[]{ 2,  1, 0, 0});
        // Logistics.
        m.put(BuildingType.STABLE,     new int[]{ 3,  2, 1, 0});
        m.put(BuildingType.WAREHOUSE,  new int[]{ 2,  1, 0, 0});
        m.put(BuildingType.STOCKPILE,  new int[]{ 4,  2, 1, 0});
        return new InclinationProfile(Inclination.AGRICULTURAL, Map.copyOf(m));
    }

    private static InclinationProfile buildIndustrial() {
        EnumMap<BuildingType, int[]> m = new EnumMap<>(BuildingType.class);
        // Civic core.
        m.put(BuildingType.TOWN_HALL,  new int[]{ 1,  1, 1, 1});
        m.put(BuildingType.WELL,       new int[]{ 2,  1, 1, 0});
        m.put(BuildingType.MARKET,     new int[]{ 2,  1, 1, 0});  // HAMLET 0-1 → 1
        m.put(BuildingType.INN,        new int[]{ 2,  1, 1, 0});  // HAMLET 0-1 → 1
        // Residential.
        m.put(BuildingType.FARMHOUSE,  new int[]{ 4,  2, 2, 0});
        m.put(BuildingType.HOUSE,      new int[]{40, 14, 5, 1});  // CITY 35-45 → 40
        // Food chain.
        m.put(BuildingType.BAKERY,     new int[]{ 2,  1, 0, 0});
        m.put(BuildingType.MILLER,     new int[]{ 2,  1, 0, 0});
        // Industrial spine.  MINE / WOODCUTTER are stripped by
        // industrial_haufendorf.excludedBuildings on flat-only sites
        // where they have no anchor to bind to.
        m.put(BuildingType.MINE,       new int[]{ 4,  3, 1, 1});  // CITY 3-4=4, OUTPOST=1 (req)
        m.put(BuildingType.WOODCUTTER, new int[]{ 4,  3, 1, 1});  // CITY 3-4=4, OUTPOST=1 (alt)
        m.put(BuildingType.BLACKSMITH, new int[]{ 3,  2, 1, 1});
        m.put(BuildingType.CARPENTRY,  new int[]{ 3,  2, 1, 0});
        m.put(BuildingType.STONEMASON, new int[]{ 3,  2, 1, 0});
        m.put(BuildingType.TOOLSMITH,  new int[]{ 2,  1, 0, 0});
        m.put(BuildingType.ARMORER,    new int[]{ 2,  1, 0, 0});
        m.put(BuildingType.WEAVER,     new int[]{ 1,  1, 0, 0});
        m.put(BuildingType.CANDLEMAKER,new int[]{ 1,  0, 0, 0});
        m.put(BuildingType.ATELIER,    new int[]{ 1,  0, 0, 0});
        m.put(BuildingType.GUILD_HALL_CRAFTSMEN, new int[]{ 1, 0, 0, 0});
        // Religious.
        m.put(BuildingType.CHAPEL,     new int[]{ 2,  1, 0, 0});
        m.put(BuildingType.SHRINE,     new int[]{ 1,  0, 0, 0});
        // Logistics.
        m.put(BuildingType.STABLE,     new int[]{ 2,  1, 0, 0});
        m.put(BuildingType.WAREHOUSE,  new int[]{ 3,  1, 0, 0});  // CITY 2-3=3
        m.put(BuildingType.STOCKPILE,  new int[]{ 5,  3, 1, 0});  // CITY 4-5=5, TOWN 2-3=3
        return new InclinationProfile(Inclination.INDUSTRIAL, Map.copyOf(m));
    }

    private static InclinationProfile buildResidential() {
        EnumMap<BuildingType, int[]> m = new EnumMap<>(BuildingType.class);
        // Civic core.
        m.put(BuildingType.TOWN_HALL,  new int[]{ 1,  1, 1, 1});
        m.put(BuildingType.WELL,       new int[]{ 3,  2, 1, 0});
        m.put(BuildingType.MARKET,     new int[]{ 2,  1, 0, 0});
        m.put(BuildingType.INN,        new int[]{ 3,  1, 0, 0});  // CITY 2-3=3
        // Residential — house-dominant, fewest farms.  CITY HOUSE 60
        // captures "a town that's mostly residential at scale."
        m.put(BuildingType.FARMHOUSE,  new int[]{ 3,  2, 1, 0});  // CITY 2-3=3
        m.put(BuildingType.HOUSE,      new int[]{60, 25, 9, 2});  // CITY 55-65=60, TOWN 22-28=25, HAMLET 8-10=9
        // Food chain.
        m.put(BuildingType.BAKERY,     new int[]{ 2,  1, 0, 0});
        m.put(BuildingType.MILLER,     new int[]{ 2,  1, 0, 0});
        // Production tail.
        m.put(BuildingType.BLACKSMITH, new int[]{ 2,  1, 0, 0});
        m.put(BuildingType.CARPENTRY,  new int[]{ 1,  1, 0, 0});
        m.put(BuildingType.WOODCUTTER, new int[]{ 1,  1, 0, 0});
        m.put(BuildingType.WEAVER,     new int[]{ 1,  1, 0, 0});
        m.put(BuildingType.CANDLEMAKER,new int[]{ 1,  0, 0, 0});
        // Religious.
        m.put(BuildingType.CHAPEL,     new int[]{ 2,  1, 1, 0});  // HAMLET 0-1=1
        m.put(BuildingType.SHRINE,     new int[]{ 1,  1, 0, 0});
        // Services.
        m.put(BuildingType.HEALER_HUT, new int[]{ 1,  1, 0, 0});
        m.put(BuildingType.STABLE,     new int[]{ 2,  1, 0, 0});
        m.put(BuildingType.WAREHOUSE,  new int[]{ 1,  0, 0, 0});
        m.put(BuildingType.STOCKPILE,  new int[]{ 3,  2, 1, 0});  // CITY 2-3=3, HAMLET 0-1=1
        return new InclinationProfile(Inclination.RESIDENTIAL, Map.copyOf(m));
    }

    private static InclinationProfile buildCivic() {
        EnumMap<BuildingType, int[]> m = new EnumMap<>(BuildingType.class);
        // Civic core — the headline emphasis.
        m.put(BuildingType.TOWN_HALL,  new int[]{ 1,  1, 1, 1});
        m.put(BuildingType.WELL,       new int[]{ 3,  2, 1, 0});
        m.put(BuildingType.MARKET,     new int[]{ 3,  2, 1, 0});  // TOWN 1-2=2
        m.put(BuildingType.INN,        new int[]{ 4,  2, 1, 0});  // CITY 3-4=4
        m.put(BuildingType.LIBRARY,    new int[]{ 2,  1, 0, 0});
        m.put(BuildingType.BELL_TOWER, new int[]{ 1,  1, 0, 0});
        m.put(BuildingType.TREASURY,   new int[]{ 2,  1, 0, 0});  // CITY 1-2=2
        m.put(BuildingType.CHANCELLERY, new int[]{ 1, 0, 0, 0});
        m.put(BuildingType.SCRIBE_WORKSHOP, new int[]{ 1, 0, 0, 0});
        m.put(BuildingType.GUILD_HALL_MERCHANTS, new int[]{ 1, 0, 0, 0});
        // Residential.  CIVIC tier wants population centres around
        // its institutions — slightly above RESIDENTIAL ratios.
        m.put(BuildingType.FARMHOUSE,  new int[]{ 4,  2, 2, 0});  // CITY 3-5=4
        m.put(BuildingType.HOUSE,      new int[]{53, 20, 7, 1});  // CITY 45-60=53, TOWN 18-22=20
        // Food chain.
        m.put(BuildingType.BAKERY,     new int[]{ 3,  2, 0, 0});  // CITY 2-3=3
        m.put(BuildingType.MILLER,     new int[]{ 3,  2, 0, 0});
        // Production tail.
        m.put(BuildingType.BLACKSMITH, new int[]{ 2,  1, 1, 0});  // HAMLET 0-1=1
        m.put(BuildingType.CARPENTRY,  new int[]{ 1,  1, 0, 0});
        m.put(BuildingType.WOODCUTTER, new int[]{ 1,  1, 0, 0});
        // Religious.
        m.put(BuildingType.CHAPEL,     new int[]{ 3,  2, 1, 0});  // CITY 2-3=3, TOWN 1-2=2
        m.put(BuildingType.SHRINE,     new int[]{ 2,  1, 0, 0});  // CITY 1-2=2
        // Services.
        m.put(BuildingType.HEALER_HUT, new int[]{ 1,  1, 0, 0});
        m.put(BuildingType.STABLE,     new int[]{ 3,  2, 1, 0});  // CITY 2-3=3
        m.put(BuildingType.WAREHOUSE,  new int[]{ 3,  1, 1, 0});  // CITY 2-3=3
        m.put(BuildingType.STOCKPILE,  new int[]{ 4,  2, 1, 0});  // CITY 3-4=4
        return new InclinationProfile(Inclination.CIVIC, Map.copyOf(m));
    }

    private static InclinationProfile buildSacred() {
        EnumMap<BuildingType, int[]> m = new EnumMap<>(BuildingType.class);
        // Civic core.
        m.put(BuildingType.TOWN_HALL,  new int[]{ 1,  1, 1, 1});
        m.put(BuildingType.WELL,       new int[]{ 2,  1, 1, 0});
        m.put(BuildingType.MARKET,     new int[]{ 2,  1, 0, 0});
        // Pilgrim infrastructure — SACRED gets disproportionately
        // more INNs because pilgrim flow.
        m.put(BuildingType.INN,        new int[]{ 5,  3, 2, 0});  // CITY 4-5=5, TOWN 2-3=3, HAMLET 1-2=2
        // Residential.
        m.put(BuildingType.FARMHOUSE,  new int[]{ 4,  2, 1, 0});  // CITY 3-5=4, HAMLET 0-1=1
        m.put(BuildingType.HOUSE,      new int[]{35, 14, 5, 1});  // CITY 30-40=35, TOWN 12-15=14, HAMLET 4-6=5
        // Food chain.
        m.put(BuildingType.BAKERY,     new int[]{ 2,  1, 0, 0});
        m.put(BuildingType.MILLER,     new int[]{ 2,  1, 0, 0});
        // Religious emphasis — temples, chapels, shrines, scholars.
        m.put(BuildingType.CHAPEL,     new int[]{ 3,  2, 1, 0});  // CITY 2-3=3, TOWN 1-2=2
        m.put(BuildingType.SHRINE,     new int[]{ 4,  2, 1, 1});  // CITY 3-4=4, TOWN=2, HAMLET 0-1=1
        m.put(BuildingType.TEMPLE,     new int[]{ 2,  1, 0, 0});
        m.put(BuildingType.BELL_TOWER, new int[]{ 2,  1, 0, 0});
        m.put(BuildingType.LIBRARY,    new int[]{ 1,  1, 0, 0});
        m.put(BuildingType.SCHOLARS_RETREAT, new int[]{ 1, 0, 0, 0});
        m.put(BuildingType.HEALER_HUT, new int[]{ 2,  1, 0, 0});
        m.put(BuildingType.CANDLEMAKER,new int[]{ 2,  1, 0, 0});
        m.put(BuildingType.GUILD_HALL_RELIGIOUS, new int[]{ 1, 0, 0, 0});
        // Production tail.
        m.put(BuildingType.BLACKSMITH, new int[]{ 1,  1, 0, 0});
        m.put(BuildingType.WOODCUTTER, new int[]{ 1,  1, 0, 0});
        // Logistics.
        m.put(BuildingType.STABLE,     new int[]{ 2,  1, 0, 0});  // CITY 1-2=2
        m.put(BuildingType.WAREHOUSE,  new int[]{ 1,  0, 0, 0});
        m.put(BuildingType.STOCKPILE,  new int[]{ 2,  1, 1, 0});
        return new InclinationProfile(Inclination.SACRED, Map.copyOf(m));
    }

    private static InclinationProfile buildDefensive() {
        EnumMap<BuildingType, int[]> m = new EnumMap<>(BuildingType.class);
        // Civic core — DEFENSIVE keeps the small civic shell but
        // anchors it under a CASTLE-class keep at CITY tier.
        m.put(BuildingType.TOWN_HALL,  new int[]{ 1,  1, 1, 1});
        m.put(BuildingType.WELL,       new int[]{ 2,  1, 1, 0});
        m.put(BuildingType.MARKET,     new int[]{ 2,  1, 0, 0});
        m.put(BuildingType.INN,        new int[]{ 2,  1, 1, 0});  // HAMLET 0-1=1
        m.put(BuildingType.TREASURY,   new int[]{ 1,  1, 0, 0});  // CITY=1, TOWN 0-1=1
        // Defensive emphasis — towers, barracks, armorer, castle.
        m.put(BuildingType.GUARD_TOWER,new int[]{ 4,  3, 2, 1});
        m.put(BuildingType.WATCHTOWER, new int[]{ 3,  2, 1, 1});
        m.put(BuildingType.BARRACKS,   new int[]{ 3,  2, 1, 0});
        m.put(BuildingType.CASTLE,     new int[]{ 1,  0, 0, 0});
        m.put(BuildingType.PRISON,     new int[]{ 1,  1, 0, 0});
        m.put(BuildingType.ARMORER,    new int[]{ 2,  1, 0, 0});
        m.put(BuildingType.TOOLSMITH,  new int[]{ 2,  1, 0, 0});
        // Residential.
        m.put(BuildingType.FARMHOUSE,  new int[]{ 5,  3, 2, 0});  // CITY 4-6=5, TOWN 2-3=3, HAMLET 1-2=2
        m.put(BuildingType.HOUSE,      new int[]{40, 18, 5, 1});  // CITY 35-45=40, TOWN 15-20=18, HAMLET 4-6=5
        // Food chain.
        m.put(BuildingType.BAKERY,     new int[]{ 2,  1, 0, 0});
        m.put(BuildingType.MILLER,     new int[]{ 2,  1, 0, 0});
        // Production tail.
        m.put(BuildingType.BLACKSMITH, new int[]{ 3,  2, 1, 0});  // CITY 2-3=3, HAMLET 0-1=1
        m.put(BuildingType.CARPENTRY,  new int[]{ 2,  1, 1, 0});  // CITY 1-2=2
        m.put(BuildingType.WOODCUTTER, new int[]{ 1,  1, 0, 0});
        // Religious.
        m.put(BuildingType.CHAPEL,     new int[]{ 2,  1, 1, 0});  // HAMLET 0-1=1
        m.put(BuildingType.SHRINE,     new int[]{ 1,  0, 0, 0});
        m.put(BuildingType.BELL_TOWER, new int[]{ 1,  0, 0, 0});
        // Logistics — DEFENSIVE wants STABLE for cavalry.
        m.put(BuildingType.STABLE,     new int[]{ 4,  2, 1, 0});  // CITY 3-4=4
        m.put(BuildingType.WAREHOUSE,  new int[]{ 2,  1, 0, 0});  // CITY 1-2=2
        m.put(BuildingType.STOCKPILE,  new int[]{ 4,  2, 1, 0});  // CITY 3-4=4
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
