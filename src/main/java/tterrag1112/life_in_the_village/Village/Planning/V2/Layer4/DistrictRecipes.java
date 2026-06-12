package tterrag1112.life_in_the_village.Village.Planning.V2.Layer4;

import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.ViabilityTier;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/**
 * City-morphology step 3 — district composition recipes (design doc
 * {@code 11-CITY-MORPHOLOGY-DESIGN.md} §2): per district TYPE, a member
 * table by tier of {@code (BuildingType -> weight, cap)}. The planner's
 * allocation passes consult these tables instead of hardcoded sets
 * (the old {@code RING_MEMBERS} / {@code CRAFT_SET} / {@code
 * DISTRICT_TYPES} constants and the literal HOUSE / MARKET checks).
 *
 * <p><b>Default tables encode today's behaviour exactly.</b> The
 * recipes commit is a pure refactor: on current rosters the allocation
 * is byte-identical to the pre-recipe constants. Mixed-use entries
 * (e.g. CIVIC admitting HOUSE at CITY) are layered on top as separate,
 * deliberate recipe changes.
 *
 * <p><b>Code-table on purpose.</b> Datagen/JSON for these tables is
 * explicitly deferred (Garrett's JSON-content ruling). The single
 * lookup seam is {@link #members(DistrictType, ViabilityTier)} — a
 * culture override layer can later wrap that one method (culture =
 * recipe overrides + density curve + NBT packs + palette, §2) without
 * touching any consumer.
 *
 * <p><b>Weights are reserved.</b> Today's allocation is membership +
 * cap driven (each selected roster instance of a member type routes to
 * its district, up to the cap); no consumer reads {@code weight} yet.
 * The field exists because the table shape is the design-doc contract
 * — a weighted dealer over a shared roster pool is the planned
 * consumer.
 */
public final class DistrictRecipes {

    /** The shipped district kinds (survey contract + step-3 scope). */
    public enum DistrictType { CIVIC, MARKET, WORKSHOP_QUARTER, RESIDENTIAL, GREEN_COMMONS, AGRICULTURE }

    /** One member row: a district of this kind may hold up to {@code cap}
     *  buildings of {@code type}; {@code weight} is the (reserved) dealing
     *  weight — see the class javadoc. */
    public record Member(BuildingType type, int weight, int cap) {}

    /** Cap value meaning "no per-district limit" (today's CRAFT_SET and
     *  residential HOUSE semantics: every selected instance routes in). */
    public static final int UNCAPPED = Integer.MAX_VALUE;

    private DistrictRecipes() {}

    // =====================================================================
    // Default tables — TODAY'S behaviour, verbatim.
    //
    //  CIVIC            — the plaza ring members (old RING_MEMBERS):
    //                     TOWN_HALL / CHAPEL / INN, one each, every tier.
    //                     At CITY the recipe also admits HOUSE (cap 2) —
    //                     TOWNHOUSES fronting the plaza ring, the first
    //                     mixed-use entry (§2). They come OUT of the
    //                     residential allocation (total roster HOUSE count
    //                     unchanged); TOWN and below admit none.
    //  MARKET           — the single central market hall (cap 1; the old
    //                     placeOne MARKET cap-1 drop rule).
    //  WORKSHOP_QUARTER — the craft set (old CRAFT_SET), uncapped: every
    //                     selected craft instance routes to the quarter
    //                     (CITY rosters legitimately carry duplicates,
    //                     e.g. STABLE x2).
    //  RESIDENTIAL      — HOUSE, uncapped: the precinct pass deals every
    //                     remaining house into residential districts.
    //  GREEN_COMMONS    — no building members; it is band FILL (seated
    //                     green blocks), not a roster-allocated district.
    //  AGRICULTURE      — the outer ring of FARMSTEAD nodes (design doc
    //                     13, agriculture-ring stage 1). One node per
    //                     roster FARMHOUSE (uncapped — the dealer mints a
    //                     node per instance); STABLE cap 1 reads as ONE
    //                     PER FARMSTEAD NODE (the ring pass deals one
    //                     stable beside each farmhouse while roster
    //                     stables last — moved here OUT of the
    //                     WORKSHOP_QUARTER craft set); SHRINE cap 1 is
    //                     per RING (one wayside shrine on a rural lane).
    //                     (Stage-2 flip: the DISTRICT_ONLY_MODE filter
    //                     this membership once re-admitted them through
    //                     is gone; membership now drives the ring-pass
    //                     routing and batch classification only.)
    // =====================================================================

    private static final Map<DistrictType, List<Member>> DEFAULTS;
    /** CITY-only override rows appended to the defaults (first mixed use). */
    private static final Map<DistrictType, List<Member>> CITY_EXTRAS;
    static {
        Map<DistrictType, List<Member>> d = new EnumMap<>(DistrictType.class);
        d.put(DistrictType.CIVIC, List.of(
                new Member(BuildingType.TOWN_HALL, 1, 1),
                new Member(BuildingType.CHAPEL, 1, 1),
                new Member(BuildingType.INN, 1, 1)));
        d.put(DistrictType.MARKET, List.of(
                new Member(BuildingType.MARKET, 1, 1)));
        d.put(DistrictType.WORKSHOP_QUARTER, List.of(
                new Member(BuildingType.BLACKSMITH, 1, UNCAPPED),
                new Member(BuildingType.BAKERY, 1, UNCAPPED),
                new Member(BuildingType.CARPENTRY, 1, UNCAPPED),
                new Member(BuildingType.MILLER, 1, UNCAPPED),
                new Member(BuildingType.WOODCUTTER, 1, UNCAPPED),
                new Member(BuildingType.STOCKPILE, 1, UNCAPPED),
                new Member(BuildingType.WAREHOUSE, 1, UNCAPPED)));
        d.put(DistrictType.RESIDENTIAL, List.of(
                new Member(BuildingType.HOUSE, 1, UNCAPPED)));
        d.put(DistrictType.GREEN_COMMONS, List.of());
        d.put(DistrictType.AGRICULTURE, List.of(
                new Member(BuildingType.FARMHOUSE, 1, UNCAPPED),
                new Member(BuildingType.STABLE, 1, 1),
                new Member(BuildingType.SHRINE, 1, 1)));
        DEFAULTS = Map.copyOf(d);
        CITY_EXTRAS = Map.of(DistrictType.CIVIC, List.of(
                new Member(BuildingType.HOUSE, 1, 2)));
    }

    /** Per-(district, tier) member-TYPE sets, precomputed so hot callers
     *  ({@code getBatch}, the batch-loop skips) pay one map lookup. */
    private static final Map<ViabilityTier, Map<DistrictType, EnumSet<BuildingType>>> TYPE_SETS;
    static {
        Map<ViabilityTier, Map<DistrictType, EnumSet<BuildingType>>> ts =
                new EnumMap<>(ViabilityTier.class);
        for (ViabilityTier tier : ViabilityTier.values()) {
            Map<DistrictType, EnumSet<BuildingType>> perDistrict =
                    new EnumMap<>(DistrictType.class);
            for (DistrictType dt : DistrictType.values()) {
                EnumSet<BuildingType> types = EnumSet.noneOf(BuildingType.class);
                for (Member m : members(dt, tier)) types.add(m.type());
                perDistrict.put(dt, types);
            }
            ts.put(tier, perDistrict);
        }
        TYPE_SETS = Map.copyOf(ts);
    }

    /**
     * THE lookup seam: the member table for {@code district} at
     * {@code tier}. A culture override layer wraps this method later;
     * every other accessor derives from it.
     */
    public static List<Member> members(DistrictType district, ViabilityTier tier) {
        List<Member> base = DEFAULTS.get(district);
        List<Member> extra = tier == ViabilityTier.CITY
                ? CITY_EXTRAS.get(district) : null;
        if (extra == null || extra.isEmpty()) return base;
        List<Member> out = new java.util.ArrayList<>(base.size() + extra.size());
        out.addAll(base);
        out.addAll(extra);
        return List.copyOf(out);
    }

    /** Member TYPES of {@code district} at {@code tier} (cached; do not
     *  mutate). */
    public static EnumSet<BuildingType> memberTypes(DistrictType district,
                                                    ViabilityTier tier) {
        return TYPE_SETS.get(tier).get(district);
    }

    /** Per-district cap for {@code type} at {@code tier}; 0 when the type
     *  is not a member of the district. */
    public static int cap(DistrictType district, ViabilityTier tier,
                          BuildingType type) {
        for (Member m : members(district, tier)) {
            if (m.type() == type) return m.cap();
        }
        return 0;
    }
}
