package tterrag1112.life_in_the_village.Village.Decoration.TownSquare;

import tterrag1112.life_in_the_village.Village.Decoration.VillageSizeTier;

/**
 * Doc 04 §"Tier scaling" — fold of {@link VillageSizeTier} onto the
 * dimensions a town square cares about: half-extent ("plaza radius"),
 * which features ship at this tier, etc.
 *
 * <p>The mapping table:</p>
 * <pre>
 *   HAMLET  → radius 3 (7×7), well only
 *   VILLAGE → radius 5 (11×11), well + benches
 *   TOWN    → radius 8 (17×17), fountain + benches + gazebo
 *   CITY    → radius 12 (25×25), fountain + monument + benches + gazebo
 * </pre>
 *
 * <p>Doc 04 originally proposed a separate {@code TownSquareTier} enum.
 * Since {@link VillageSizeTier} already has the same four values
 * (HAMLET / VILLAGE / TOWN / CITY) and is the canonical tier source
 * across the mod, this class just folds onto the existing enum
 * rather than duplicating it. Documented in doc 04 revision notes.</p>
 */
public final class TownSquareTier {

    /** Doc 04 §"Tier scaling" — plaza half-extent for each tier. */
    public static final int RADIUS_HAMLET  = 3;
    public static final int RADIUS_VILLAGE = 5;
    public static final int RADIUS_TOWN    = 8;
    public static final int RADIUS_CITY    = 12;

    private TownSquareTier() {}

    /**
     * Returns the plaza half-extent ({@code plazaRadius}) for the given
     * tier. Plaza diameter = {@code 2 * radius + 1} (so HAMLET = 7×7,
     * CITY = 25×25). Null tier falls back to HAMLET so legacy / test
     * paths never crash.
     */
    public static int plazaRadiusFor(VillageSizeTier tier) {
        if (tier == null) return RADIUS_HAMLET;
        return switch (tier) {
            case HAMLET  -> RADIUS_HAMLET;
            case VILLAGE -> RADIUS_VILLAGE;
            case TOWN    -> RADIUS_TOWN;
            case CITY    -> RADIUS_CITY;
        };
    }

    /** Doc 04 §"Tier scaling" — number of perimeter benches per tier. */
    public static int benchCountFor(VillageSizeTier tier) {
        if (tier == null) return 0;
        return switch (tier) {
            case HAMLET  -> 0;
            case VILLAGE -> 2;
            case TOWN, CITY -> 4;
        };
    }

    /** True for TOWN and CITY — these tiers ship a gazebo. */
    public static boolean hasGazebo(VillageSizeTier tier) {
        return tier == VillageSizeTier.TOWN || tier == VillageSizeTier.CITY;
    }

    /** True for CITY only — only the largest tier ships a monument. */
    public static boolean hasMonument(VillageSizeTier tier) {
        return tier == VillageSizeTier.CITY;
    }

    /** True for VILLAGE+ — flowerbeds appear once the plaza is big
     *  enough to space them between benches. */
    public static boolean hasFlowerbeds(VillageSizeTier tier) {
        return tier != null && tier != VillageSizeTier.HAMLET;
    }

    /** True for VILLAGE+ — VENDOR_ZONE is reserved on plazas big
     *  enough to host market stalls. HAMLET plazas are too small. */
    public static boolean hasVendorZone(VillageSizeTier tier) {
        return tier != null && tier != VillageSizeTier.HAMLET;
    }
}
