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
 * <p>V1 ships the {@link #AGRICULTURAL} profile only. Other
 * inclinations get profiles in follow-up cycles.
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

    /** AGRICULTURAL roster — see ADAPTIVE-VILLAGE-DESIGN.md.
     *
     *  <p>Approximate totals: CITY ≈ 56, TOWN ≈ 28, HAMLET ≈ 12,
     *  OUTPOST ≈ 4. Roughly matches viability tier targets. */
    public static final InclinationProfile AGRICULTURAL = build();

    private static InclinationProfile build() {
        EnumMap<BuildingType, int[]> m = new EnumMap<>(BuildingType.class);
        // [CITY, TOWN, HAMLET, OUTPOST]
        m.put(BuildingType.TOWN_HALL,  new int[]{ 1,  1, 1, 1});
        m.put(BuildingType.WELL,       new int[]{ 2,  1, 1, 0});
        m.put(BuildingType.MARKET,     new int[]{ 1,  1, 0, 0});
        m.put(BuildingType.INN,        new int[]{ 1,  1, 0, 0});
        m.put(BuildingType.FARMHOUSE,  new int[]{12,  6, 3, 1});
        m.put(BuildingType.HOUSE,      new int[]{25, 12, 5, 1});
        m.put(BuildingType.MILLER,     new int[]{ 2,  1, 0, 0});
        m.put(BuildingType.BAKERY,     new int[]{ 1,  1, 0, 0});
        m.put(BuildingType.STABLE,     new int[]{ 1,  1, 0, 0});
        m.put(BuildingType.BLACKSMITH, new int[]{ 1,  1, 0, 0});
        m.put(BuildingType.MINE,       new int[]{ 1,  0, 0, 0});
        m.put(BuildingType.CARPENTRY,  new int[]{ 1,  0, 0, 0});
        m.put(BuildingType.WOODCUTTER, new int[]{ 2,  1, 0, 0});
        m.put(BuildingType.CHAPEL,     new int[]{ 1,  0, 0, 0});
        m.put(BuildingType.SHRINE,     new int[]{ 1,  0, 0, 0});
        m.put(BuildingType.WAREHOUSE,  new int[]{ 1,  0, 0, 0});
        m.put(BuildingType.STOCKPILE,  new int[]{ 1,  1, 0, 0});
        return new InclinationProfile(Inclination.AGRICULTURAL, Map.copyOf(m));
    }

    /** Look up the profile for an {@link Inclination}. V1 returns
     *  AGRICULTURAL for everything since other inclinations aren't
     *  rostered yet. */
    public static InclinationProfile forInclination(Inclination inc) {
        // Other inclinations land in follow-up cycles. Until then,
        // any inclination falls back to the AGRICULTURAL roster so
        // the solver still produces a village.
        return AGRICULTURAL;
    }
}
