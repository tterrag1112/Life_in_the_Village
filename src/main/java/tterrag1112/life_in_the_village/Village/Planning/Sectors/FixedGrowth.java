package tterrag1112.life_in_the_village.Village.Planning.Sectors;

import tterrag1112.life_in_the_village.Village.Planning.Primitives.PlanContext;
import tterrag1112.life_in_the_village.Village.Planning.Zoning.PlacementSlot;

import java.util.List;

/**
 * Never grows. Used by reservations, fixed-capacity civic rings,
 * and named-anchor sectors.
 */
public final class FixedGrowth implements GrowthPolicy {

    public static final FixedGrowth INSTANCE = new FixedGrowth();

    private FixedGrowth() {}

    @Override
    public List<PlacementSlot> grow(PlanContext pctx, Sector sector, int growRound) {
        return List.of();
    }
}
