package tterrag1112.life_in_the_village.Village.Planning.Adaptive;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Village.Planning.AnchorKind;
import tterrag1112.life_in_the_village.Village.VillageTypeData.ShapeType;

import java.util.List;
import java.util.Map;

/**
 * The new return type of {@code BaseRecipe.compose(pctx)}.
 * Declarative description of a village's intended structure: roads,
 * plazas, sectors, slot intentions, named anchors. The planner
 * realises it after the recipe returns; the recipe never computes
 * positions or invokes primitives directly.
 *
 * <h3>Field semantics</h3>
 * <ul>
 *   <li>{@code shape} — ShapeType identity. Used by debug, dump, and
 *       cascade engine to identify which recipe produced this.</li>
 *   <li>{@code roads} — declared roads in build order. Order matters:
 *       the first road is the primary spine that
 *       {@code checkPrimarySpine} evaluates.</li>
 *   <li>{@code plazas} — zero, one, or many plaza declarations.
 *       Plaza-less recipes return an empty list. DUAL_PLAZA returns
 *       two.</li>
 *   <li>{@code sectors} — every sector this recipe wants registered,
 *       with caps and zone semantics.</li>
 *   <li>{@code slotIntentions} — declarative slot specifications. The
 *       SlotEmitter consumes these.</li>
 *   <li>{@code namedAnchors} — pre-computed anchor positions
 *       (TOWN_SQUARE, MAIN_GATE, etc.) for the LayoutPlan. Anchors
 *       depending on road realisation get populated by the planner
 *       post-realisation.</li>
 * </ul>
 *
 * <p>Phase A flip vs spec: the spec proposed a
 * {@code Map<String, Object> recipeMetadata} field for
 * cross-re-emission cascade state. Dropped — PlanContext already
 * carries axis rotation, retry counts, chain position from
 * Phases 16b/22; an opaque untyped map duplicates that state and is
 * a code smell. If a future recipe genuinely needs typed per-recipe
 * metadata, we add it deliberately at that point.
 */
public record LayoutBlueprint(
        ShapeType shape,
        List<RoadDeclaration> roads,
        List<PlazaDeclaration> plazas,
        List<SectorDeclaration> sectors,
        List<SlotIntention> slotIntentions,
        Map<AnchorKind, BlockPos> namedAnchors
) {
    public LayoutBlueprint {
        roads = List.copyOf(roads);
        plazas = List.copyOf(plazas);
        sectors = List.copyOf(sectors);
        slotIntentions = List.copyOf(slotIntentions);
        namedAnchors = Map.copyOf(namedAnchors);
    }

    /** Empty blueprint for stub recipes / abort cases. */
    public static LayoutBlueprint empty(ShapeType shape) {
        return new LayoutBlueprint(
                shape, List.of(), List.of(), List.of(),
                List.of(), Map.of());
    }
}
