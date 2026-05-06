package tterrag1112.life_in_the_village.Village.Planning.Primitives;

import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;
import tterrag1112.life_in_the_village.Village.Decoration.Plaza.PlazaShape;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.RoadShape;
import tterrag1112.life_in_the_village.Village.Planning.Adaptive.LayoutBlueprint;
import tterrag1112.life_in_the_village.Village.Planning.Zoning.PlacementSlot;

import java.util.ArrayList;
import java.util.List;

// CenterlineResult, RoadResult, RoadPrimitive, PrimitiveContext,
// TerminationReason, PlanContext are in the same package — no imports needed.

/**
 * Phase A architectural cutover. The previous three-step lifecycle
 * (prepareFeatures → composeSectors → registerAnchors) and the
 * recipe-internal cascade engine ({@code runWithCascade},
 * {@code composeOnce}, {@code fallbackShape}) are gone. Recipes now
 * implement {@link #compose(PlanContext)} returning a
 * {@link LayoutBlueprint}; the planner realises it.
 *
 * <h3>What stays</h3>
 * <ul>
 *   <li>{@link RecipeStatus} — extended with {@code RECIPE_NOT_PORTED}
 *       for Phase A's stub diagnostic.</li>
 *   <li>{@link ReEmitReason} — the cascade-engine reason vocabulary
 *       still used by VillagePlanner's relocated chain-walking.</li>
 *   <li>{@link #checkPrimarySpine(RoadResult)} — the truncation
 *       cascade trigger.</li>
 *   <li>{@link #computeAndRecord(RoadPrimitive, PlanContext)} — utility
 *       for recipes that need to probe a primitive's centerline.</li>
 *   <li>{@link #clampToRoadReach(List, RoadResult, int)} — utility
 *       Phase B/C resolvers may use for post-emit slot clamping.</li>
 *   <li>{@link #describeReason(ReEmitReason)} — log helper.</li>
 *   <li>{@link #preferredPlazaShape()} — recipe-side declaration of
 *       plaza shape preference. Reused by Phase C/D ports until the
 *       PlazaDeclaration absorbs it.</li>
 * </ul>
 *
 * <h3>What's deleted</h3>
 * <ul>
 *   <li>{@code runWithCascade} — chain-walking moved to
 *       {@code VillagePlanner.handleSevereTruncation /
 *       handleSlotsDropped / handleValidationFailed} per spec §8.</li>
 *   <li>{@code composeOnce} — the probe-then-commit body merges into
 *       the new {@link #compose} which returns a blueprint without
 *       mutating the layout.</li>
 *   <li>{@code fallbackShape} — Phase 22 chain entries on
 *       {@code VillageTypeData.fallbackChain} are the sole source of
 *       fallback target shapes.</li>
 *   <li>{@code prepareFeatures}, {@code composeSectors},
 *       {@code registerAnchors} — recipes are mostly-data now;
 *       no shared lifecycle hooks.</li>
 * </ul>
 */
public abstract class BaseRecipe implements ShapeRecipe {

    // =========================================================================
    // Cascade engine (Phase 16b, retained for VillagePlanner)
    // =========================================================================

    /**
     * Outcome of a constraint check. Generalizes beyond truncation —
     * Phase 22 ValidationFailed cascade reuses the enum verbatim.
     *
     * <p>{@code RECIPE_NOT_PORTED} (Phase A) marks layouts that hit a
     * {@code RecipeNotPortedException} stub before they could compose.
     * Distinct from {@code ABORT} so the per-village summary line and
     * MeasureCommand's classifier can identify migration-pending sites
     * vs structurally unspawnable ones.
     */
    public enum RecipeStatus {
        /** Plan acceptable; engine returns. */
        OK,
        /** Plan unacceptable; cascade should retry with mutated state. */
        RETRY,
        /** Plan unacceptable; cascade should fall back to next chain entry. */
        FALLBACK,
        /** Site unsalvageable; layout marked unplannable. */
        ABORT,
        /** Phase A: recipe stub threw {@code RecipeNotPortedException}.
         *  Distinct from ABORT so observability tools surface
         *  migration-pending sites separately. */
        RECIPE_NOT_PORTED
    }

    /**
     * Reason a recipe needs to re-emit. Sealed so future phases can add
     * variants without churning every {@link #reEmit} site. Phase A
     * keeps the existing four variants; the spec's Phase B
     * SlotsDropped enrichment ({@code dropRatio + perIntentionDrop +
     * dropReasons}) is a Phase B refinement, not a Phase A change.
     */
    public sealed interface ReEmitReason
            permits ReEmitReason.SevereTruncation,
                    ReEmitReason.SlotsDropped,
                    ReEmitReason.SectorStarved,
                    ReEmitReason.ValidationFailed {

        record SevereTruncation(RoadResult primary) implements ReEmitReason {}
        record SlotsDropped(int dropped, int total) implements ReEmitReason {}
        record SectorStarved(String sectorId, int unfilledMin) implements ReEmitReason {}
        record ValidationFailed(int passed, int total,
                                java.util.List<String> failureKinds)
                implements ReEmitReason {}
    }

    /**
     * Recipe-specific dispatch on a re-emit reason. Phase A: returns a
     * fresh {@link LayoutBlueprint} (the recipe's adjusted intent) or
     * {@code null} (advance the Phase 22 fallback chain to the next
     * recipe). The planner consumes the return value in
     * {@code handleSevereTruncation} / {@code handleSlotsDropped} /
     * {@code handleValidationFailed}.
     *
     * <p>Default implementation returns null for every reason —
     * vanilla recipes don't know how to recover from any constraint
     * failure and prefer the chain advance. Recipes with structural
     * recovery (rotate axis, shrink ring) override per reason.
     */
    @Nullable
    public LayoutBlueprint reEmit(ReEmitReason reason, PlanContext pctx) {
        return null;
    }

    /**
     * Generic primary-spine check shared by all cascade-aware recipes.
     * Buckets {@link RoadResult#completionRatio()} into the four
     * status levels: ≥0.6 OK, ≥0.3 RETRY, ≥0.15 FALLBACK, else ABORT.
     */
    public static RecipeStatus checkPrimarySpine(RoadResult primary) {
        double ratio = primary.completionRatio();
        if (ratio >= 0.60) return RecipeStatus.OK;
        if (ratio >= 0.30) return RecipeStatus.RETRY;
        if (ratio >= 0.15) return RecipeStatus.FALLBACK;
        return RecipeStatus.ABORT;
    }

    /**
     * Probes a primitive's centerline and wraps the result with
     * intended/actual length so callers can drive viability checks.
     * Does NOT mutate the road graph — the caller commits separately
     * via {@link tterrag1112.life_in_the_village.Village.Planning
     * .VillageLayout#addRoad}.
     */
    public static RoadResult computeAndRecord(RoadPrimitive p, PlanContext pctx) {
        CenterlineResult raw = p.computeCenterline(
                PrimitiveContext.basic(pctx.level, pctx.worldSeed));
        return new RoadResult(p, raw.points(), raw.isComplete(),
                raw.reason(), p.intendedLength(), raw.points().size());
    }

    /**
     * Drops candidate slots whose feeding road no longer reaches them.
     * Returns the input unchanged when the road is complete; otherwise
     * filters out slots more than {@code budget} blocks (chebyshev)
     * from the nearest centerline point, where
     * {@code budget = footprintHalf + roadHalfWidth + slack}.
     */
    public static List<PlacementSlot> clampToRoadReach(
            List<PlacementSlot> candidates,
            RoadResult feedingRoad,
            int slack) {
        if (feedingRoad.isComplete() || feedingRoad.centerline().isEmpty()) {
            return candidates;
        }
        int roadHalfWidth = feedingRoad.primitive().tier().reservedHalfWidth();
        List<PlacementSlot> kept = new ArrayList<>(candidates.size());
        int dropped = 0;
        for (PlacementSlot s : candidates) {
            BlockPos nearest = PlanContext.nearestOn(
                    feedingRoad.centerline(), s.pos());
            int chebDist = Math.max(
                    Math.abs(nearest.getX() - s.pos().getX()),
                    Math.abs(nearest.getZ() - s.pos().getZ()));
            int footprintHalf = s.footprintBudget() / 2;
            int budget = footprintHalf + roadHalfWidth + slack;
            if (chebDist <= budget) {
                kept.add(s);
            } else {
                dropped++;
            }
        }
        if (dropped > 0) {
            System.out.println("[CLAMP] " + feedingRoad.primitive().typeKey()
                    + " dropped=" + dropped + "/" + candidates.size()
                    + " (truncated at " + feedingRoad.actualLength() + "/"
                    + feedingRoad.intendedLength() + ")");
        }
        return kept;
    }

    public static String describeReason(ReEmitReason reason) {
        if (reason instanceof ReEmitReason.SevereTruncation t) {
            return "SevereTruncation primary="
                    + t.primary().actualLength() + "/"
                    + t.primary().intendedLength()
                    + " " + t.primary().reason();
        }
        if (reason instanceof ReEmitReason.ValidationFailed v) {
            return "ValidationFailed " + v.passed() + "/" + v.total()
                    + " kinds=" + v.failureKinds();
        }
        if (reason instanceof ReEmitReason.SlotsDropped s) {
            return "SlotsDropped " + s.dropped() + "/" + s.total();
        }
        if (reason instanceof ReEmitReason.SectorStarved s) {
            return "SectorStarved " + s.sectorId() + " unfilled=" + s.unfilledMin();
        }
        return reason.getClass().getSimpleName();
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    /**
     * Returns a declarative blueprint of the village. Phase A recipes
     * throw {@link tterrag1112.life_in_the_village.Village.Planning
     * .Adaptive.RecipeNotPortedException}; Phase C/D ports replace
     * those stubs with real declarative bodies.
     */
    @Override
    public abstract LayoutBlueprint compose(PlanContext pctx);

    /**
     * Declares the plaza shape this recipe prefers. Default CIRCLE.
     * Retained for Phase B/C/D ports; eventually subsumed by
     * {@link tterrag1112.life_in_the_village.Village.Planning
     * .Adaptive.PlazaDeclaration#shape()}.
     */
    public PlazaShape preferredPlazaShape() {
        return PlazaShape.CIRCLE;
    }
}
