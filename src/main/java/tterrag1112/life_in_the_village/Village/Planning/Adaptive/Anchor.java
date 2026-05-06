package tterrag1112.life_in_the_village.Village.Planning.Adaptive;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Village.Planning.AnchorKind;

/**
 * Spatial relationship that defines where a SlotEmitter resolver
 * should produce candidate slots for a {@link SlotIntention}. Sealed
 * interface; six implementations cover every authoring pattern observed
 * in the existing recipes.
 *
 * <h3>Anchor types</h3>
 * <ul>
 *   <li>{@link PlazaPerimeter} — slots at plaza polygon vertices,
 *       skipping vertices near road exits. Solves CROSSROADS / PLAZA /
 *       DUAL_PLAZA civic-vertex-overlaps-spur failures.</li>
 *   <li>{@link RoadAlong} — slots along one specific road's
 *       centerline at perpendicular offset. The workhorse anchor for
 *       LINEAR / SPRAWL / road-adjacent placements.</li>
 *   <li>{@link AllSpurs} — distributed across all SPUR-role roads.
 *       Truncation-tolerant by construction. Solves PLAZA's
 *       PRODUCTION_CLUSTER pattern.</li>
 *   <li>{@link RingValidated} — points on a ring at radius R, only
 *       at angles within validator-cap distance of some feeding road.
 *       Critical for outer agri/defense rings.</li>
 *   <li>{@link NamedAnchor} — slots near a named anchor
 *       (TOWN_SQUARE, HIGH_GROUND, etc.) offset radially along the
 *       feeding road. Solves HILLTOP's TOWN_HALL placement.</li>
 *   <li>{@link RegionalGather} — free placement within a region,
 *       not road-anchored. Preserves ENCLAVE-style hand-rolled
 *       courtyards.</li>
 * </ul>
 *
 * <p>Phase A defines the type vocabulary; Phase B implements the six
 * resolvers in {@link SlotEmitter}.
 */
public sealed interface Anchor {

    /** Slots at plaza polygon vertices, skipping vertices within
     *  {@code avoidSpurExitChebDist} chebyshev of any road exit. */
    record PlazaPerimeter(
            SectorRef plazaSector,
            int avoidSpurExitChebDist
    ) implements Anchor {}

    /** Slots along one specific road's centerline, perpendicular at
     *  {@code spacing} intervals. {@code bothSides=true} alternates
     *  sides. */
    record RoadAlong(
            EdgeRef edge,
            int spacing,
            boolean bothSides
    ) implements Anchor {}

    /** Slots distributed across all SPUR-role roads in the blueprint.
     *  The resolver runs {@link RoadAlong} internally per spur with
     *  {@code spacingPerSpur}, capping at the intention's count. */
    record AllSpurs(int spacingPerSpur, boolean bothSides) implements Anchor {}

    /** Slots on a ring at the specified radius, only at angular
     *  positions within validator-cap distance of some feeding road.
     *  {@code radialJitter} (0..1) breaks perfect symmetry without
     *  breaking the validator-cap invariant. */
    record RingValidated(int radius, double radialJitter) implements Anchor {}

    /** Slot at a position near a named anchor, offset along the
     *  anchor's feeding road by {@code radialOffset} blocks. */
    record NamedAnchor(AnchorKind kind, int radialOffset) implements Anchor {}

    /** Free placement within a circular region, not road-anchored.
     *  Used by recipes (e.g. ENCLAVE) that want positions
     *  inside a courtyard rather than along a road graph. */
    record RegionalGather(
            BlockPos center,
            int radius,
            int minSpacing
    ) implements Anchor {}
}
