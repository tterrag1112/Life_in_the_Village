package tterrag1112.life_in_the_village.Village.Planning.Sectors;

import tterrag1112.life_in_the_village.Village.Planning.Primitives.PlanContext;
import tterrag1112.life_in_the_village.Village.Planning.Zoning.PlacementSlot;

import java.util.List;

/**
 * Adds a concentric ring beyond the current outer ring with deterministic
 * angular distribution. Used by RADIAL / PLAZA / HILLTOP overflow.
 *
 * <p>Phase 6: scaffolded only. Phase 10 implements.
 *
 * @param ringSpacing  blocks outward from the current outer ring
 * @param slotCount    candidates emitted on the new ring
 */
public record AddRing(int ringSpacing, int slotCount) implements GrowthPolicy {

    @Override
    public List<PlacementSlot> grow(PlanContext pctx, Sector sector, int growRound) {
        // Phase 10 implementation:
        //   1. Resolve outer ring radius from sector geometry — typically
        //      sector's farthest slot from layout.getCenter().
        //   2. New ring radius = outerR + ringSpacing * (growRound + 1).
        //   3. seed = ExtendAlongEdge.derivedSeed(pctx, sector, growRound).
        //   4. Emit slotCount slots on the new ring at evenly-spaced
        //      angles (with a small angular offset derived from seed).
        //   5. Skip any slot whose position is on water/cliff per
        //      pctx.features.isClearForFootprint or fails hull containment.
        //   6. Add a Ring or Arc edge to the graph connecting the new
        //      slots' road-adjacent neighbors.
        return List.of();
    }
}
