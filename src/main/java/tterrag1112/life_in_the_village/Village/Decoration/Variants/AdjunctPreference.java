package tterrag1112.life_in_the_village.Village.Decoration.Variants;

/**
 * B2.1 — declared preference for an
 * {@link tterrag1112.life_in_the_village.Village.Decoration.Adjunct.AdjunctPlot}
 * attached to this variant. Optional on a manifest; most variants
 * have no adjunct preference and inherit registry defaults
 * (or have no adjunct at all).
 *
 * <p>Manifest JSON shape:</p>
 * <pre>
 *   "adjunct": {
 *       "size": [width, depth],
 *       "side": "BACK" | "LEFT" | "RIGHT" | "ANY",
 *       "required": false
 *   }
 * </pre>
 *
 * <p>Semantics consumed by {@code PhasedPlanner} during V2 Layer 3
 * placement:</p>
 * <ul>
 *   <li>{@code width} / {@code depth} — plot footprint in blocks,
 *       odd values recommended (interior centre block is the plot
 *       origin). Even values are accepted and centred conservatively.</li>
 *   <li>{@code side} — preferred side of the parent building.
 *       {@code ANY} lets the planner pick, trying BACK then LEFT
 *       then RIGHT.</li>
 *   <li>{@code required} — when {@code true}, candidates that can't
 *       fit the adjunct are dropped. When {@code false} (default),
 *       the building places without the adjunct if no side fits.</li>
 * </ul>
 */
public record AdjunctPreference(int width, int depth,
                                AdjunctSide side, boolean required) {

    /** Compact-constructor sanity: clamp degenerate dimensions to 1
     *  so the planner always has a non-empty rectangle to fit. */
    public AdjunctPreference {
        if (width  < 1) width  = 1;
        if (depth  < 1) depth  = 1;
        if (side == null) side = AdjunctSide.ANY;
    }

    /** Half-extent in the parent's local-X direction (rounded down for
     *  even widths). The reservation AABB is built outward from the
     *  origin block by this many blocks in each ± direction. */
    public int halfWidth()  { return Math.max(0, (width  - 1) / 2); }
    public int halfDepth()  { return Math.max(0, (depth  - 1) / 2); }
}
