package tterrag1112.life_in_the_village.Village.Planning.Sectors;

import tterrag1112.life_in_the_village.Village.Planning.Primitives.PlanContext;
import tterrag1112.life_in_the_village.Village.Planning.Zoning.PlacementSlot;

import java.util.List;

/**
 * Spawns a new spur edge off the parent at a deterministic angle (next
 * angle from a precomputed cardinal/ordinal list, used in order). Used
 * by CROSSROADS / DUMBELL / SPRAWL overflow.
 *
 * <p>Phase 6: scaffolded only. Phase 10 implements.
 *
 * @param spurLength    spur length in blocks
 * @param slotsPerSpur  candidates emitted along the spur
 */
public record AddSpur(int spurLength, int slotsPerSpur) implements GrowthPolicy {

    @Override
    public List<PlacementSlot> grow(PlanContext pctx, Sector sector, int growRound) {
        // Phase 10 implementation:
        //   1. Pick the next angle from the 8-direction ordinal list,
        //      indexed by growRound modulo 8. Skip already-used directions
        //      tracked by a transient field on the Sector or by inspecting
        //      existing graph edges.
        //   2. Resolve branch point on the parent edge (mid-edge).
        //   3. seed = ExtendAlongEdge.derivedSeed(pctx, sector, growRound).
        //   4. Generate spur centerline via RoadPrimitive.Spur primitive.
        //   5. Add the spur edge to the graph (TERMINUS at far end).
        //   6. Emit slotsPerSpur slots along the spur with appropriate
        //      SlotTags for the sector role.
        return List.of();
    }
}
