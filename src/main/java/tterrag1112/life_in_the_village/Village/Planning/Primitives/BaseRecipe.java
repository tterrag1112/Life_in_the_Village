package tterrag1112.life_in_the_village.Village.Planning.Primitives;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Village.Decoration.Plaza.PlazaShape;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.RoadShape;
import tterrag1112.life_in_the_village.Village.Planning.Zoning.PlacementSlot;
import tterrag1112.life_in_the_village.Village.VillageTypeData.ShapeType;

import java.util.ArrayList;
import java.util.List;

// CenterlineResult, RoadResult, RoadPrimitive, PrimitiveContext,
// TerminationReason, PlanContext are in the same package — no imports needed.

/**
 * Three-step lifecycle for shape recipes that fit the standard pattern:
 * prepare features, compose sectors, register named anchors.
 *
 * <h3>Default lifecycle (most recipes)</h3>
 * <ol>
 *   <li>{@link #prepareFeatures} — feature-aware pre-pass (default no-op).</li>
 *   <li>{@link #composeSectors} — REQUIRED. Build the road graph and
 *       emit sectors. The matcher consumes whatever this method
 *       produces.</li>
 *   <li>{@link #registerAnchors} — register named anchors (default no-op).</li>
 * </ol>
 *
 * <h3>Cascade-aware recipes (Phase 16b)</h3>
 * Recipes that participate in the constraint-propagation engine
 * ({@link #runWithCascade}) override {@link #compose} to invoke it
 * and override {@link #composeOnce} with a probe-then-commit body.
 * The engine retries via {@link #reEmit}; recipes declare a
 * {@link #fallbackShape} for when retries are exhausted.
 *
 * <p>Currently RADIAL and LINEAR are cascade-aware. All other recipes
 * keep the default {@link #compose} (which calls
 * {@link #composeSectors} once) and ignore the engine.
 *
 * <p>The opt-in is intentional: recipes that don't have a
 * meaningful primary spine can't usefully participate, and
 * forcing them through the engine just adds latency without value.
 *
 * <p>Recipes can also override {@link #preferredPlazaShape()} to declare
 * what plaza geometry they want.
 */
public abstract class BaseRecipe implements ShapeRecipe {

    // =========================================================================
    // Cascade engine (Phase 16b)
    // =========================================================================

    /**
     * Outcome of a constraint check. Generalizes beyond truncation —
     * future Phase 22 / architectural-shift cases (slot loss, sector
     * starvation) will reuse this enum verbatim.
     */
    public enum RecipeStatus {
        /** Plan acceptable; engine returns. */
        OK,
        /** Plan unacceptable; engine re-invokes {@link #composeOnce} with mutated state. */
        RETRY,
        /** Plan unacceptable; engine delegates to {@link #fallbackShape}. */
        FALLBACK,
        /** Site unsalvageable; engine marks layout unplannable and returns. */
        ABORT
    }

    /**
     * Reason a recipe needs to re-emit. Sealed so future phases can add
     * variants without churning every {@link #reEmit} site. Phase 16b
     * only consumes {@link SevereTruncation}; the other variants exist
     * as scaffolding for Phase 22 and the post-rework experiments.
     */
    public sealed interface ReEmitReason
            permits ReEmitReason.SevereTruncation,
                    ReEmitReason.SlotsDropped,
                    ReEmitReason.SectorStarved,
                    ReEmitReason.ValidationFailed {

        record SevereTruncation(RoadResult primary) implements ReEmitReason {}
        record SlotsDropped(int dropped, int total) implements ReEmitReason {}
        record SectorStarved(String sectorId, int unfilledMin) implements ReEmitReason {}
        /** Phase 22: planner's validator rejected the composed plan with
         *  passed/total below the 0.6 threshold. Fired from
         *  VillagePlanner after validation; the recipe's reEmit decides
         *  whether to fall back via the cascade chain. */
        record ValidationFailed(int passed, int total,
                                java.util.List<String> failureKinds)
                implements ReEmitReason {}
    }

    /**
     * Engine entry point for cascade-aware recipes. Calls
     * {@link #composeOnce} up to {@code maxRetries} times, dispatching
     * on the returned {@link ReEmitReason} via {@link #reEmit}.
     *
     * <p>Contract on {@link #composeOnce} implementations: the body
     * MUST be probe-then-commit. No layout mutation may happen before
     * the recipe decides whether to return a reason. Once a reason is
     * returned, the engine assumes nothing was committed and proceeds
     * to retry / fallback / abort. Recipes that need to commit before
     * the viability check should not participate in the engine.
     */
    protected void runWithCascade(PlanContext pctx, int maxRetries) {
        prepareFeatures(pctx);
        for (int i = 0; i < maxRetries; i++) {
            ReEmitReason reason = composeOnce(pctx);
            if (reason == null) {
                registerAnchors(pctx);
                return;
            }
            RecipeStatus status = reEmit(reason, pctx);
            switch (status) {
                case OK -> {
                    registerAnchors(pctx);
                    return;
                }
                case RETRY -> {
                    // loop; reEmit has mutated cascade state on pctx
                }
                case FALLBACK -> {
                    // Phase 22: prefer the schema-declared cascade chain
                    // on PlanContext (set by VillagePlanner from
                    // VillageTypeData.getFallbackChain()). Fall back to
                    // the per-recipe fallbackShape() when the chain is
                    // unset (legacy path). The chain entry list is
                    // [primary, ...fallbacks]; cascadeChainPosition is
                    // the index of the CURRENT shape, so the next entry
                    // is at position+1.
                    ShapeType next = nextChainShape(pctx);
                    if (next == null) {
                        pctx.layout.markUnplannable(
                                "cascade exhausted — "
                                + getClass().getSimpleName()
                                + " reason=" + describeReason(reason));
                        return;
                    }
                    System.out.println("[CASCADE] "
                            + getClass().getSimpleName()
                            + " falling back to " + next
                            + " reason=" + describeReason(reason)
                            + " chainPos=" + pctx.cascadeChainPosition()
                            + "->" + (pctx.cascadeChainPosition() + 1));
                    // Advance position + reset mutable composition state.
                    pctx.advanceCascadeChain();
                    pctx.resetForFallback();
                    pctx.layout.resetForFallback();
                    pctx.setFinalShape(next);
                    ShapeRecipe.forShape(next).compose(pctx);
                    return;
                }
                case ABORT -> {
                    pctx.layout.markUnplannable("ABORT reason="
                            + describeReason(reason));
                    return;
                }
            }
        }
        pctx.layout.markUnplannable("retry budget exhausted ("
                + maxRetries + ") — " + getClass().getSimpleName());
    }

    /**
     * Probe-then-commit body for cascade-aware recipes.
     *
     * <p>Default delegates to {@link #composeSectors} and returns
     * {@code null} (= OK), which is the right behavior for non-cascade
     * recipes that nonetheless route through the engine. Cascade-aware
     * recipes override this with a probe-first body that returns a
     * {@link ReEmitReason} when the constraint check fails (without
     * having mutated the layout).
     */
    protected ReEmitReason composeOnce(PlanContext pctx) {
        composeSectors(pctx);
        return null;
    }

    /**
     * Recipe-specific dispatch on a re-emit reason. Default falls back
     * unconditionally; recipes that can recover (e.g., RADIAL on
     * truncation: rotate axis) override.
     */
    protected RecipeStatus reEmit(ReEmitReason reason, PlanContext pctx) {
        return reason == null ? RecipeStatus.OK : RecipeStatus.FALLBACK;
    }

    /**
     * The shape this recipe falls back to when the cascade engine
     * exhausts its retries or {@link #reEmit} returns
     * {@link RecipeStatus#FALLBACK}. Default {@code null} = abort.
     */
    protected ShapeType fallbackShape() {
        return null;
    }

    /**
     * Generic primary-spine check shared by all cascade-aware recipes.
     * Buckets {@link RoadResult#completionRatio()} into the four
     * status levels: ≥0.6 OK, ≥0.3 RETRY, ≥0.15 FALLBACK, else ABORT.
     */
    protected static RecipeStatus checkPrimarySpine(RoadResult primary) {
        double ratio = primary.completionRatio();
        if (ratio >= 0.60) return RecipeStatus.OK;
        if (ratio >= 0.30) return RecipeStatus.RETRY;
        if (ratio >= 0.15) return RecipeStatus.FALLBACK;
        return RecipeStatus.ABORT;
    }

    /**
     * Probes a primitive's centerline and wraps the result with
     * intended/actual length so recipes can drive viability checks.
     * Does NOT mutate the road graph — the caller is responsible for
     * deciding whether to commit via {@link
     * tterrag1112.life_in_the_village.Village.Planning.VillageLayout#addRoad}.
     */
    protected static RoadResult computeAndRecord(RoadPrimitive p, PlanContext pctx) {
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
     *
     * <p>{@code roadHalfWidth} is read from the feeding road's tier
     * via {@link RoadShape.RoadTier#reservedHalfWidth()} so the budget
     * tracks future tier additions. {@code footprintHalf} is computed
     * from {@link PlacementSlot#footprintBudget()} / 2 inline rather
     * than via a new accessor on PlacementSlot (constraint: don't
     * change PlacementSlot).
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

    private static String describeReason(ReEmitReason reason) {
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
        return reason.getClass().getSimpleName();
    }

    /**
     * Phase 22: returns the next chain shape, or null if the chain is
     * exhausted. Prefers the schema chain on PlanContext; falls back to
     * the per-recipe {@link #fallbackShape()} only when no chain is set.
     */
    protected ShapeType nextChainShape(PlanContext pctx) {
        java.util.List<ShapeType> chain = pctx.cascadeChain();
        if (chain != null && !chain.isEmpty()) {
            int next = pctx.cascadeChainPosition() + 1;
            return next < chain.size() ? chain.get(next) : null;
        }
        // Legacy per-recipe fallback (Phase 16b path). Only fires once;
        // a second FALLBACK has nothing to advance to.
        if (pctx.cascadeChainPosition() == 0) {
            return fallbackShape();
        }
        return null;
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    /**
     * Default lifecycle: prepareFeatures → composeSectors →
     * registerAnchors. Cascade-aware recipes override this to call
     * {@link #runWithCascade} instead. The override is documented and
     * intentional — the lifecycle invariant is now a convention, not a
     * compile-time guarantee, to enable Phase 16b's engine.
     */
    @Override
    public void compose(PlanContext pctx) {
        prepareFeatures(pctx);
        composeSectors(pctx);
        registerAnchors(pctx);
    }

    /**
     * Optional pre-pass for feature-aware recipes. Default is no-op.
     */
    protected void prepareFeatures(PlanContext pctx) {
        // no-op default
    }

    /**
     * Build the road graph and emit sectors. Required for default
     * (non-cascade) recipes. Cascade-aware recipes typically leave this
     * empty (or unused) and put their body in
     * {@link #composeOnce} instead.
     */
    protected abstract void composeSectors(PlanContext pctx);

    /**
     * Register named anchors after sectors are emitted. Default no-op.
     * The legacy {@code mainGateEndpoint} field on VillageLayout
     * already serves this purpose; Phase 19 introduces the proper
     * AnchorKind registry.
     */
    protected void registerAnchors(PlanContext pctx) {
        // Phase 19 wires this through to LayoutPlan.anchors.
    }

    /**
     * Declares the plaza shape this recipe prefers. Default CIRCLE.
     */
    public PlazaShape preferredPlazaShape() {
        return PlazaShape.CIRCLE;
    }
}
