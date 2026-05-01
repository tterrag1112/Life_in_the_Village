package tterrag1112.life_in_the_village.Village.Planning.Primitives;

/**
 * Why a {@link RoadPrimitive#computeCenterline(PrimitiveContext) computeCenterline}
 * walk stopped before completing its full geometric path.
 *
 * <p>Phase 16 plumbing: every primitive returns one of these in a
 * {@link CenterlineResult}. Callers currently log the reason and proceed
 * as if the centerline were complete; later phases branch on the reason
 * to insert TERMINUS nodes, hand off to a Bridge/Stairway primitive, or
 * abort sector emission.
 */
public enum TerminationReason {
    /** Centerline traversed its full geometric path. */
    COMPLETED,

    /** Y-discontinuity exceeded threshold relative to the previous point. */
    CLIFF_DROP,

    /** Y-discontinuity exceeded threshold relative to the previous point,
     *  in the upward direction. */
    CLIFF_RISE,

    /** Surface at this position is water, and primitive is not water-capable. */
    WATER_CROSSING,

    /** Surface unavailable (void column, unloaded chunk, etc.). */
    NO_SURFACE
}
