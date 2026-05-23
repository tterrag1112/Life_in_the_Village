package tterrag1112.life_in_the_village.Village.Buildings.Complex;

/**
 * Detour A — strategy enum for the path-graph topology computed
 * inside a complex region.
 *
 * <p>The chosen topology is consumed by Stage 2's
 * {@code PathTopologyPlanner} (algorithm). Today only
 * {@link #SPINE_WITH_BRANCHES} is implemented; the enum exists so
 * the spec can name its choice without hardcoding the planner.
 */
public enum PathTopology {
    /** Single spine entering from the road-facing edge, with one
     *  branch per plot reaching the plot's entry. Default for farm
     *  complexes; produces a readable "main path with side
     *  alleys" visual structure. */
    SPINE_WITH_BRANCHES,

    /** Reserved for future complex types — radial paths from a
     *  central point (e.g. sacred grove). Not implemented in
     *  Detour A; included so the spec can declare future intent
     *  without a follow-up registry edit. */
    RADIAL,

    /** Reserved — minimum spanning tree across plot centroids.
     *  Not implemented in Detour A. */
    MINIMUM_SPANNING_TREE
}
