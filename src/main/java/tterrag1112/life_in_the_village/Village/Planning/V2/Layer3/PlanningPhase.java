package tterrag1112.life_in_the_village.Village.Planning.V2.Layer3;

/**
 * Detour A — labels which planning phase produced a V2 envelope.
 *
 * <p>Small enum added so {@link ComplexRegion#reservedAt()} can
 * carry attribution back to the phase that emitted it (Phase 3
 * foundation pass vs. Phase 4 iterative pass vs. Phase 5 reassess).
 * Other envelopes ({@link Footprint}, {@link FrontageStrip},
 * {@link PlannedAdjunct}) don't currently track this; ComplexRegion
 * does because debug commands need to print "reserved by Phase 4"
 * to make sense of misplaced complexes.
 */
public enum PlanningPhase {
    PHASE_3_FOUNDATION,
    PHASE_4B_ITERATIVE,
    PHASE_5_REASSESS,
    PHASE_6_EMIT,
    /** Used by the standalone test command's synthetic-village
     *  spawn path — no real planner phase, but a value is needed. */
    HARNESS_STANDALONE
}
