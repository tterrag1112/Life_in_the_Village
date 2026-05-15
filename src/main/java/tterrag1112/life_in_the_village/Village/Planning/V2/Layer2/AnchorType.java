package tterrag1112.life_in_the_village.Village.Planning.V2.Layer2;

/**
 * Track E1 anchor detection — anchor type taxonomy.
 *
 * <p>11 anchor types span topographic, hydrological, vegetational,
 * and cultural categories. Detection heuristics live in
 * {@link AnchorDetector}; each anchor type maps to one or more
 * primitives in {@code V2FeatureMap} (flat regions, high ground,
 * stone regions, forest regions, water cells).
 *
 * <p>Per the user-locked design:
 * <ul>
 *   <li>{@link #CROSSROADS} is reserved for prompt-2+ once C2's
 *       cross-village road graph is wired. Detection returns
 *       empty for this type today.</li>
 *   <li>Strategies (prompt 2+) consume the
 *       {@code SiteContext.anchors()} list and pick which types
 *       match the chosen village form. Detection is permissive
 *       (quality ≥ {@link Anchor#QUALITY_THRESHOLD}); culling
 *       happens at strategy time.</li>
 * </ul>
 *
 * @see Anchor
 * @see AnchorDetector
 */
public enum AnchorType {
    /** Connected low-relief open ground; primary for AGRICULTURAL / RESIDENTIAL. */
    FLAT_FERTILE,
    /** Local elevation maximum with steep ≥3-side drops; DEFENSIVE / civic. */
    DEFENSIBLE_PEAK,
    /** Flat-topped hill with circumferential drop; DEFENSIVE / civic large. */
    DEFENSIBLE_RING,
    /** Thin elevated linear band; carries direction vector. */
    RIDGE_LINE,
    /** Linear depression between slopes; carries direction vector. */
    VALLEY_FLOOR,
    /** Vertical or near-vertical drop bordering flat ground; carries tangent. */
    CLIFF_FACE,
    /** Land-water adjacency with flat landside; carries shoreline tangent. */
    WATER_EDGE,
    /** {@code WATER_EDGE} with high local curvature; trade-node candidate. */
    RIVER_BEND,
    /** Flat land fully surrounded by water within scan radius. */
    ISLAND,
    /** Forest-open boundary; carries tangent. */
    FOREST_EDGE,
    /** Open region surrounded by forest on ≥3 sides. */
    NATURAL_CLEARING,
    /**
     * Cross-village road junction (reserved). Detection returns
     * empty today; prompt-2+ wires the cross-village road graph.
     */
    CROSSROADS;

    /** True for anchor types whose orientation vector is meaningful. */
    public boolean isLinear() {
        return switch (this) {
            case RIDGE_LINE, VALLEY_FLOOR, CLIFF_FACE,
                 WATER_EDGE, RIVER_BEND, FOREST_EDGE -> true;
            default -> false;
        };
    }

    /** Track E1 prompt 5 — true for anchor types that represent a
     *  specific natural-resource feature (mineable cliffs, harvestable
     *  forest edges, fishable / millable water). FLAT_FERTILE is
     *  deliberately NOT a resource anchor — every site has flat
     *  ground; it's the universal fallback. The strategy selector
     *  uses this predicate to give resource-anchored strategies a
     *  presence bonus when their preferred resource exists on the
     *  site at any quality above the (lowered) primary-quality floor,
     *  so a moderately-good cliff/forest/river site reliably picks
     *  the matching strategy over the haufendorf fallback. */
    public boolean isResourceAnchor() {
        return switch (this) {
            case CLIFF_FACE, FOREST_EDGE, WATER_EDGE, RIVER_BEND -> true;
            default -> false;
        };
    }
}
