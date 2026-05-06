package tterrag1112.life_in_the_village.Village.Planning.Adaptive;

/**
 * Lightweight string handle for a road declaration. Recipes construct
 * EdgeRefs at compose time with stable IDs ("spine_n", "spur_e"); the
 * planner resolves them post-realisation by mapping each EdgeRef to a
 * {@link RealisedEdge} on {@link tterrag1112.life_in_the_village
 * .Village.Planning.Primitives.PlanContext}.
 *
 * <p>String IDs (vs. object references) keep the cascade engine
 * simple — re-emission returns a new blueprint with the same string
 * IDs, the planner re-resolves them against newly-realised edges.
 * See spec §10.4.
 */
public record EdgeRef(String id) {
    public static EdgeRef of(String id) { return new EdgeRef(id); }
}
