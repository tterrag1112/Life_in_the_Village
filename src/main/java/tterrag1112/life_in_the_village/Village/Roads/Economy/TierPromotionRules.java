package tterrag1112.life_in_the_village.Village.Roads.Economy;

import tterrag1112.life_in_the_village.Village.Decoration.VillageSizeTier;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadEdge;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.List;

/**
 * Pure-function rules mapping village size to road tier.
 * Invariant 6: connector tier derives from village size tier, not caravan traffic.
 */
public final class TierPromotionRules {

    private TierPromotionRules() {}

    // =========================================================================
    // Tier mapping
    // =========================================================================

    /**
     * The natural connector tier for a village of the given size when it is
     * the sole or dominant maintainer of an edge.
     */
    public static RoadEdge.EdgeTier naturalTierForVillage(VillageSizeTier size) {
        return switch (size) {
            case HAMLET  -> RoadEdge.EdgeTier.LOCAL;
            case VILLAGE -> RoadEdge.EdgeTier.CONNECTOR;
            case TOWN    -> RoadEdge.EdgeTier.TRUNK;
            case CITY    -> RoadEdge.EdgeTier.TRUNK; // GREAT_ROAD is reserved for Old Realm
        };
    }

    /**
     * For a multi-maintainer edge the effective tier is the maximum of each
     * maintainer's natural tier. A shared road used by a TOWN and a HAMLET
     * is a TRUNK road — the TOWN's standard, not the HAMLET's.
     */
    public static RoadEdge.EdgeTier effectiveTier(List<Village> maintainers) {
        RoadEdge.EdgeTier highest = RoadEdge.EdgeTier.LOCAL;
        for (Village v : maintainers) {
            VillageSizeTier size = VillageSizeTier.fromBuildingCount(v.getBuildingIds().size());
            RoadEdge.EdgeTier t = naturalTierForVillage(size);
            if (tierRank(t) > tierRank(highest)) {
                highest = t;
            }
        }
        return highest;
    }

    /**
     * Returns true when the edge's tier can be changed by this promotion system.
     * Great roads are fixed and never touched.
     */
    public static boolean canBeChanged(RoadEdge edge) {
        return edge.getTier() != RoadEdge.EdgeTier.GREAT_ROAD;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Numeric rank for tier comparison: LOCAL=0, CONNECTOR=1, TRUNK=2, GREAT_ROAD=3. */
    public static int tierRank(RoadEdge.EdgeTier tier) {
        return switch (tier) {
            case LOCAL      -> 0;
            case CONNECTOR  -> 1;
            case TRUNK      -> 2;
            case GREAT_ROAD -> 3;
        };
    }
}
