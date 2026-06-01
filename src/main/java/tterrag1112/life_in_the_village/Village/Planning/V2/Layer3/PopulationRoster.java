package tterrag1112.life_in_the_village.Village.Planning.V2.Layer3;

import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Buildings.Inhabitants.BuildingInhabitantRegistry;
import tterrag1112.life_in_the_village.Village.Buildings.Inhabitants.BuildingInhabitantSpec;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer1.V2FeatureMap;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.ViabilityTier;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Layout Rework Step 3 / Stage 1 — population-first building roster.
 *
 * <p>Replaces the per-tier fixed count tables with a derivation from a
 * target NPC population:
 * <ol>
 *   <li><b>Tier → target population.</b> A {@code [min,max]} band on
 *       {@link ViabilityTier}, sampled deterministically from the site
 *       seed.</li>
 *   <li><b>Per-building expected headcount.</b> The EV of a building's
 *       {@link BuildingInhabitantSpec} household rolls — the single
 *       source of truth shared with {@code VillageInhabitantPopulator}.</li>
 *   <li><b>Services / civic / production by ratio + cap.</b> Per role
 *       "1 per N population" with a hard cap (the fix for the
 *       "AGRICULTURAL CITY with 3 chapels / 2 shrines / 2 markets"
 *       failure), gated by inclination ({@link InclinationProfile})
 *       and FeatureMap terrain viability.</li>
 *   <li><b>Housing fill.</b> HOUSE / FARMHOUSE counts chosen so the
 *       total NPC headcount lands near the target, split by the
 *       inclination's {@code farmhouseShare}.</li>
 * </ol>
 *
 * <p>The numbers below are starting values meant for in-world tuning;
 * the <i>shape</i> (population-first, capped roles, dependency-safe
 * food chain) is the part meant to stay stable.
 *
 * <p>Output is a {@code BuildingType → count} map (deterministic ordinal
 * iteration) consumed by {@link BuildingSelector}, which expands it to a
 * multiplicity list and applies the strategy / availability filters.
 */
public final class PopulationRoster {

    private static final long POPULATION_SALT = 0x9E37_79B9_7F4A_7C15L;

    private PopulationRoster() {}

    /** Per-role count rule: {@code count = pop < minPop ? 0 :
     *  clamp(1 + floor((pop - minPop) / perN), 1, cap)} where {@code cap}
     *  is the inclination override or {@code defaultCap}. {@code perN <= 0}
     *  means "never more than one from the ratio". */
    private record ServiceRule(int minPop, int perN, int defaultCap) {
        int count(int pop, int cap) {
            if (pop < minPop) return 0;
            int extra = perN > 0 ? (pop - minPop) / perN : 0;
            return Math.max(1, Math.min(cap, 1 + extra));
        }
    }

    /** Fallback rule for an in-scope building with no explicit entry:
     *  one once the village is mid-sized. */
    private static final ServiceRule DEFAULT_RULE = new ServiceRule(30, 0, 1);

    private static final Map<BuildingType, ServiceRule> RULES = buildRules();

    private static Map<BuildingType, ServiceRule> buildRules() {
        EnumMap<BuildingType, ServiceRule> m = new EnumMap<>(BuildingType.class);
        // Civic core / services.
        m.put(BuildingType.WELL,        new ServiceRule(0,  60, 3));
        m.put(BuildingType.MARKET,      new ServiceRule(25, 95, 1));
        m.put(BuildingType.INN,         new ServiceRule(35, 60, 2));
        m.put(BuildingType.STABLE,      new ServiceRule(25, 60, 2));
        m.put(BuildingType.WAREHOUSE,   new ServiceRule(40, 70, 2));
        m.put(BuildingType.STOCKPILE,   new ServiceRule(20, 40, 3));
        m.put(BuildingType.HEALER_HUT,  new ServiceRule(35, 90, 1));
        // Religious — the capped roles (caps come from the inclination).
        m.put(BuildingType.CHAPEL,      new ServiceRule(12, 68, 2));
        m.put(BuildingType.SHRINE,      new ServiceRule(50, 60, 1));
        m.put(BuildingType.TEMPLE,      new ServiceRule(45, 80, 1));
        m.put(BuildingType.BELL_TOWER,  new ServiceRule(40, 90, 1));
        // Food chain (MILLER is co-selected with BAKERY, see build()).
        m.put(BuildingType.BAKERY,      new ServiceRule(15, 35, 2));
        // Production / crafts.
        m.put(BuildingType.BLACKSMITH,  new ServiceRule(20, 60, 2));
        m.put(BuildingType.CARPENTRY,   new ServiceRule(30, 80, 1));
        m.put(BuildingType.WOODCUTTER,  new ServiceRule(20, 60, 2));
        m.put(BuildingType.MINE,        new ServiceRule(20, 50, 2));
        m.put(BuildingType.STONEMASON,  new ServiceRule(40, 80, 1));
        m.put(BuildingType.TOOLSMITH,   new ServiceRule(40, 90, 1));
        m.put(BuildingType.ARMORER,     new ServiceRule(45, 90, 1));
        m.put(BuildingType.WEAVER,      new ServiceRule(35, 90, 1));
        m.put(BuildingType.CANDLEMAKER, new ServiceRule(45, 90, 1));
        m.put(BuildingType.VINEYARD,    new ServiceRule(30, 80, 1));
        m.put(BuildingType.WINERY,      new ServiceRule(40, 90, 1));
        // Defensive — scale with population.
        m.put(BuildingType.GUARD_TOWER, new ServiceRule(12, 30, 4));
        m.put(BuildingType.WATCHTOWER,  new ServiceRule(20, 40, 3));
        m.put(BuildingType.BARRACKS,    new ServiceRule(30, 50, 3));
        m.put(BuildingType.CASTLE,      new ServiceRule(60, 0,  1));
        m.put(BuildingType.PRISON,      new ServiceRule(45, 0,  1));
        // Scholarly / kingdom civic — singletons in larger settlements.
        m.put(BuildingType.LIBRARY,     new ServiceRule(45, 90, 1));
        m.put(BuildingType.TREASURY,    new ServiceRule(45, 0,  1));
        m.put(BuildingType.CHANCELLERY, new ServiceRule(60, 0,  1));
        m.put(BuildingType.SCRIBE_WORKSHOP,   new ServiceRule(50, 0, 1));
        m.put(BuildingType.SCHOLARS_RETREAT,  new ServiceRule(50, 0, 1));
        m.put(BuildingType.ATELIER,           new ServiceRule(60, 0, 1));
        m.put(BuildingType.GUILD_HALL_CRAFTSMEN,  new ServiceRule(60, 0, 1));
        m.put(BuildingType.GUILD_HALL_MERCHANTS,  new ServiceRule(60, 0, 1));
        m.put(BuildingType.GUILD_HALL_RELIGIOUS,  new ServiceRule(60, 0, 1));
        return m;
    }

    /**
     * Builds the population-first roster for a site.
     *
     * @param profile inclination config (housing split, building set, caps)
     * @param tier    viability tier (carries the population band)
     * @param fmap    feature map (terrain viability gate for production)
     * @param seed    site seed (deterministic population sampling)
     * @return {@code BuildingType → count}, deterministic ordinal order
     */
    public static Map<BuildingType, Integer> build(InclinationProfile profile,
                                                   ViabilityTier tier,
                                                   V2FeatureMap fmap,
                                                   long seed) {
        EnumMap<BuildingType, Integer> roster = new EnumMap<>(BuildingType.class);
        if (tier == ViabilityTier.UNVIABLE) return roster;

        int targetPop = samplePopulation(tier, seed);
        if (targetPop <= 0) return roster;

        // 1. Required civic anchor — always exactly one.
        roster.put(BuildingType.TOWN_HALL, 1);

        // 2. Services / civic / production / religious by ratio + cap,
        //    gated by the inclination set and terrain viability.
        for (BuildingType type : profile.buildings()) {
            if (type == BuildingType.MILLER) continue; // co-selected below
            PlacementProfile pp = PlacementDefaults.get(type);
            if (pp == null) continue;                  // selector can't place it; don't budget for it
            if (!BuildingSelector.aggregatesPresent(   // terrain gate (MINE/WOODCUTTER/…)
                    pp.requiresAggregates(), fmap)) continue;
            ServiceRule rule = RULES.getOrDefault(type, DEFAULT_RULE);
            int cap = profile.capFor(type, rule.defaultCap());
            int count = rule.count(targetPop, cap);
            if (count > 0) roster.put(type, count);
        }

        // 3. MILLER co-selection — a preference paired with BAKERY (now
        //    safe: no river gate, no hard BAKERY→MILLER dependency).
        if (roster.getOrDefault(BuildingType.BAKERY, 0) > 0
                && profile.buildings().contains(BuildingType.MILLER)
                && viable(BuildingType.MILLER, fmap)
                && profile.capFor(BuildingType.MILLER, 1) > 0) {
            roster.put(BuildingType.MILLER, 1);
        }

        // 4. Housing fill — choose HOUSE / FARMHOUSE so total headcount
        //    lands near the target, split by the inclination ratio.
        double serviceHeadcount = 0.0;
        for (Map.Entry<BuildingType, Integer> e : roster.entrySet()) {
            serviceHeadcount += e.getValue() * expectedHeadcount(e.getKey());
        }
        double remaining = targetPop - serviceHeadcount;
        // Always leave room for at least a little residential population so
        // a service-heavy small village still has homes.
        double evHouse = Math.max(0.1, expectedHeadcount(BuildingType.HOUSE));
        double evFarm  = Math.max(0.1, expectedHeadcount(BuildingType.FARMHOUSE));
        if (remaining < evHouse) remaining = evHouse;

        double farmPop  = remaining * profile.farmhouseShare();
        double housePop = remaining - farmPop;
        int farmhouses = (int) Math.round(farmPop / evFarm);
        int houses     = (int) Math.round(housePop / evHouse);
        // Guarantee at least one dwelling overall.
        if (farmhouses + houses == 0) houses = 1;
        if (farmhouses > 0) roster.put(BuildingType.FARMHOUSE, farmhouses);
        if (houses > 0)     roster.put(BuildingType.HOUSE, houses);

        return roster;
    }

    /** Tier population band sampled deterministically from the seed. */
    private static int samplePopulation(ViabilityTier tier, long seed) {
        int lo = tier.minPopulation;
        int hi = tier.maxPopulation;
        if (hi <= lo) return lo;
        Random rng = new Random(seed ^ POPULATION_SALT);
        return lo + rng.nextInt(hi - lo + 1);
    }

    /**
     * Expected per-building headcount — the EV of its
     * {@link BuildingInhabitantSpec} household rolls. Single source of
     * truth with {@code VillageInhabitantPopulator}: a spec change moves
     * this EV. Counts the head (always 1 × chance) plus the expected
     * spouse and children.
     */
    public static double expectedHeadcount(BuildingType type) {
        double ev = 0.0;
        for (BuildingInhabitantSpec.Inhabitant inh
                : BuildingInhabitantRegistry.get(type).getInhabitants()) {
            ev += inh.chance()
                    * (1.0 + inh.spouseChance()
                            + inh.maxChildren() * (double) inh.childChanceEach());
        }
        return ev;
    }

    /** Terrain viability gate — mirrors the hard-aggregate check the
     *  {@link BuildingSelector} applies, so the roster doesn't budget
     *  population for a production building the site can't host. */
    private static boolean viable(BuildingType type, V2FeatureMap fmap) {
        PlacementProfile pp = PlacementDefaults.get(type);
        if (pp == null) return true;
        return BuildingSelector.aggregatesPresent(pp.requiresAggregates(), fmap);
    }

    /** Diagnostic: roster entries in ordinal order for logging / dumps. */
    public static List<Map.Entry<BuildingType, Integer>> ordered(
            Map<BuildingType, Integer> roster) {
        List<Map.Entry<BuildingType, Integer>> out =
                new java.util.ArrayList<>(roster.entrySet());
        out.sort(Comparator.comparingInt(e -> e.getKey().ordinal()));
        return out;
    }
}
