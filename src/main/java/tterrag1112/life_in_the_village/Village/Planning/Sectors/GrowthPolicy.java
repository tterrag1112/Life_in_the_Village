package tterrag1112.life_in_the_village.Village.Planning.Sectors;

import tterrag1112.life_in_the_village.Village.Planning.Primitives.PlanContext;
import tterrag1112.life_in_the_village.Village.Planning.Zoning.PlacementSlot;

import java.util.List;

/**
 * Strategy used by the placement matcher when a sector overflows.
 *
 * <p>Determinism contract: implementations seed any randomness from
 * {@code (worldSeed, villageAnchor, sectorId, growRound)}. They MUST NOT
 * use {@code Random}, {@code level.getRandom()}, or any other source
 * that depends on call order or chunk-load state.
 *
 * <p>Implementations may mutate {@code pctx.layout.getRoadGraph()}
 * (adding new edges/nodes for ExtendAlongEdge / AddRing / AddSpur) and
 * may consult {@code pctx.features} for water/cliff awareness. They
 * MUST NOT mutate the sector's own field values; only return new slots.
 */
public sealed interface GrowthPolicy
        permits FixedGrowth, ExtendAlongEdge, AddRing, AddSpur {

    /**
     * Called when the matcher overflows the sector.
     *
     * @param pctx       planning context (graph, features, density profile, seed)
     * @param sector     the overflowing sector; read-only here
     * @param growRound  0-based round number; matcher caps at MAX_GROW_ROUNDS
     * @return new slots to append to the sector pool. Empty list = refused.
     */
    List<PlacementSlot> grow(PlanContext pctx, Sector sector, int growRound);
}
