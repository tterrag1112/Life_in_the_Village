package tterrag1112.life_in_the_village.Village.Planning.V2.Layer2;

import java.util.List;

/**
 * Track E1 prompt-3 — output of {@link NetworkPlanner}.
 *
 * <p>The {@link LayoutTopology} controls the shape of the graph;
 * the recipe that produced the spec emits nodes (anchors, gateways,
 * junctions, synthetic ring points), edges (road primitives
 * connecting nodes), and per-building primary bindings derived
 * from the strategy's {@code intendedBindings}.
 *
 * <p>The grower always produces a non-empty spec: in the worst
 * degenerate case (single anchor, no secondaries, no gateways) the
 * spec carries one ANCHOR node and zero edges. Building selection
 * then treats the lone node as a placement seed.
 *
 * <p>For backwards-compatibility with the SpinePath-shaped pipeline,
 * the legacy {@link SpinePath} is derived from {@link #edges()} by
 * {@link NetworkPlanner#deriveSpinePath} and stored on
 * {@link SiteContext#spinePath()}.
 *
 * @param topology         the strategy's selected village form
 * @param nodes            graph nodes (insertion order preserved)
 * @param edges            graph edges (insertion order preserved)
 * @param primaryBindings  lead-building anchor bindings
 */
public record NetworkSpec(
        LayoutTopology topology,
        List<NetworkNode> nodes,
        List<NetworkEdge> edges,
        List<PrimaryBinding> primaryBindings) {

    public NetworkSpec {
        if (topology == null) {
            throw new IllegalArgumentException("NetworkSpec topology required");
        }
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
        primaryBindings = primaryBindings == null
                ? List.of() : List.copyOf(primaryBindings);
    }
}
