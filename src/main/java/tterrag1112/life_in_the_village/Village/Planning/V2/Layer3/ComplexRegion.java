package tterrag1112.life_in_the_village.Village.Planning.V2.Layer3;

import tterrag1112.life_in_the_village.Utilities.Geometry.Polygon;

import java.util.UUID;

/**
 * Detour A — fourth V2 envelope, alongside {@link Footprint},
 * {@link FrontageStrip}, and {@link PlannedAdjunct}.
 *
 * <p>Reserves an irregular polygonal area around a building for a
 * domain-specific "complex" to fill: a farm complex around a
 * FARMHOUSE today; later, a forge yard around a BLACKSMITH or a
 * sacred grove around a TEMPLE. The envelope only carries
 * reservation geometry — the per-domain content (plots, paths,
 * tool shed, borders for farms; forges and racks for blacksmiths)
 * lives in domain-specific records (e.g.
 * {@code Village/Farms/Complex/FarmComplex}) keyed by the same
 * {@link #buildingId}.
 *
 * <p>Collision against other envelopes is checked harness-side
 * during planning: the {@link #region} polygon must not overlap
 * any other building's Footprint, FrontageStrip, PlannedAdjunct
 * AABB, or another ComplexRegion. The polygon excludes the
 * farmhouse's own footprint area (carried separately as
 * {@link #buildingBounds} so the renderer can knock out a hole
 * for the building).
 *
 * @param buildingId      stable ID of the parent building; used by
 *                        the domain record to look up its envelope.
 * @param region          irregular flood-fill polygon (2D XZ).
 * @param buildingBounds  the parent building's footprint as a
 *                        polygon, expressed within {@link #region}'s
 *                        coordinate space — the renderer subtracts
 *                        this from {@code region} to get the
 *                        content area.
 * @param reservedAt      which planning phase emitted this envelope.
 *                        Debug-only attribution; doesn't affect
 *                        reservation semantics.
 */
public record ComplexRegion(
        UUID buildingId,
        Polygon region,
        Polygon buildingBounds,
        PlanningPhase reservedAt) {

    public ComplexRegion {
        if (buildingId == null) {
            throw new IllegalArgumentException("ComplexRegion.buildingId required");
        }
        if (region == null) {
            throw new IllegalArgumentException("ComplexRegion.region required");
        }
        if (reservedAt == null) {
            reservedAt = PlanningPhase.PHASE_4B_ITERATIVE;
        }
        // buildingBounds nullable — some complexes (sacred grove)
        // surround the building without subtracting its footprint.
    }
}
