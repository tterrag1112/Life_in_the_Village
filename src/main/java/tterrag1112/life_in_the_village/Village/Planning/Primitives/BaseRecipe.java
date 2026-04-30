package tterrag1112.life_in_the_village.Village.Planning.Primitives;

import tterrag1112.life_in_the_village.Village.Decoration.Plaza.PlazaShape;

/**
 * Three-step lifecycle for shape recipes that fit the standard pattern:
 * prepare features, compose sectors, register named anchors. Recipes
 * that don't fit (CLUSTERED, GROVE, OUTPOST may not) continue to
 * implement {@link ShapeRecipe} directly and override {@link #compose}.
 *
 * <h3>Lifecycle</h3>
 * <ol>
 *   <li>{@link #prepareFeatures} — recipes that need feature awareness
 *       (e.g. RIVERINE locating its shore, HILLTOP locating its peak)
 *       override this. Default is no-op.</li>
 *   <li>{@link #composeSectors} — REQUIRED. Build the road graph and
 *       emit sectors. The matcher consumes whatever this method
 *       produces.</li>
 *   <li>{@link #registerAnchors} — register named anchors. Default
 *       registers MAIN_GATE if {@code pctx.layout.getMainGateEndpoint()}
 *       is set. Recipes with additional anchors (TREASURY, AUDIENCE,
 *       MANOR_NE) override.</li>
 * </ol>
 *
 * <p>Recipes can also override {@link #preferredPlazaShape()} to declare
 * what plaza geometry they want. The default is CIRCLE; PLAZA prefers
 * SQUARE; CROSSROADS may prefer RECTANGLE.
 *
 * <h3>Why final compose</h3>
 * The {@code compose} method is intentionally final on this class so
 * the lifecycle ordering can't be silently broken. A recipe that needs
 * different ordering should not extend BaseRecipe.
 */
public abstract class BaseRecipe implements ShapeRecipe {

    @Override
    public final void compose(PlanContext pctx) {
        prepareFeatures(pctx);
        composeSectors(pctx);
        registerAnchors(pctx);
    }

    /**
     * Optional pre-pass for feature-aware recipes. Default is no-op.
     *
     * <p>Override this when the recipe needs to consult or augment
     * {@code pctx.features} before laying out sectors. Examples: RIVERINE
     * computes the shore-aligned spine direction, HILLTOP locates the
     * peak, TERRACED resolves slope contour lines.
     *
     * <p>This method may also emit plaza polygons via
     * {@code pctx.features.addPlazaPolygon(...)} if the recipe wants
     * the plaza geometry pinned before sector emission.
     */
    protected void prepareFeatures(PlanContext pctx) {
        // no-op default
    }

    /**
     * Build the road graph and emit sectors. Required.
     *
     * <p>Implementations call {@code pctx.layout.addNode(...)},
     * {@code pctx.layout.addEdge(...)}, and append sectors via
     * {@link PlanContext#offerSector} (Phase 8 introduces this method).
     */
    protected abstract void composeSectors(PlanContext pctx);

    /**
     * Register named anchors after sectors are emitted. Default is
     * intentional no-op — the legacy mainGateEndpoint field on
     * VillageLayout already serves this purpose. Phase 19 introduces the
     * proper AnchorKind registry and LayoutPlan.anchors.
     *
     * <p>Recipes with additional named anchors (capital recipes registering
     * CASTLE_ANCHOR, manor-bearing recipes registering MANOR_ANCHOR_*, etc.)
     * override and call {@code super} to keep the default MAIN_GATE handling.
     */
    protected void registerAnchors(PlanContext pctx) {
        // Phase 19 wires this through to LayoutPlan.anchors.
    }

    /**
     * Declares the plaza shape this recipe prefers. Default is CIRCLE.
     * The plaza generator consults this when laying out the village
     * centre. Phase 18 wires this into PlazaGenerator / TownSquarePlacer.
     */
    public PlazaShape preferredPlazaShape() {
        return PlazaShape.CIRCLE;
    }
}
