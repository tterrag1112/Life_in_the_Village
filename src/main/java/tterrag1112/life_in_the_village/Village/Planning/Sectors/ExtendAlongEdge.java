package tterrag1112.life_in_the_village.Village.Planning.Sectors;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.PlanContext;
import tterrag1112.life_in_the_village.Village.Planning.Zoning.PlacementSlot;

import java.util.List;

/**
 * Lengthens the sector's parent edge along its existing direction and
 * emits new slots along the extension. Used by LINEAR / RIVERINE /
 * ROADSIDE main-spine overflow.
 *
 * <p>Phase 6: scaffolded only — the grow body is a TODO with the right
 * signature and the right seed-derivation pattern. Phase 10 wires
 * actual growth into the matcher and implements the body.
 *
 * @param extensionLength blocks to extend per round
 * @param slotsPerRound   number of new slots emitted per round
 */
public record ExtendAlongEdge(int extensionLength, int slotsPerRound)
        implements GrowthPolicy {

    @Override
    public List<PlacementSlot> grow(PlanContext pctx, Sector sector, int growRound) {
        // Phase 10 implementation:
        //   1. Resolve the parent edge from sector.parentEdgeId() in
        //      pctx.layout.getRoadGraph().
        //   2. Compute the edge's outward direction (the heading at the
        //      far endpoint, away from the village).
        //   3. Seed: long seed = derivedSeed(pctx, sector, growRound).
        //   4. Surface-snap a new endpoint at extensionLength blocks along
        //      that direction. If pctx.features.isOnWater(...) at the new
        //      endpoint, refuse (return empty list).
        //   5. Add a new edge to the graph (TERMINUS at far end). The
        //      caller — matcher — wraps this so the new edge is recorded
        //      in the sector's slot lineage.
        //   6. Emit slotsPerRound slots evenly along the extension's
        //      centerline, perpendicular-offset, with the same SlotTags
        //      the sector's existing slots carry.
        //
        // Returning empty for now; downstream code expects List.of() when
        // a strategy refuses or when the implementation isn't ready.
        return List.of();
    }

    static long derivedSeed(PlanContext pctx, Sector sector, int growRound) {
        long h = pctx.worldSeed;
        BlockPos c = pctx.layout.getCenter();
        h = h * 6364136223846793005L + c.getX() * 2862933555777941757L;
        h = h * 6364136223846793005L + c.getZ() * 2862933555777941757L;
        h = h * 6364136223846793005L + sector.id().hashCode();
        h = h * 6364136223846793005L + growRound;
        return h ^ (h >>> 33);
    }
}
