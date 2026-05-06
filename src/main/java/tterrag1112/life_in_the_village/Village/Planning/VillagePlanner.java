package tterrag1112.life_in_the_village.Village.Planning;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import tterrag1112.life_in_the_village.Kingdom.Placement.PlacementFailureRecorder;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Buildings.FarmPlot;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.Recipes.RecipeHelpers;
import tterrag1112.life_in_the_village.Village.Decoration.Variants.StyleAutoDeriver;
import tterrag1112.life_in_the_village.Village.Decoration.Variants.StyleSelection;
import tterrag1112.life_in_the_village.Village.Decoration.Variants.VillageAgeCategoryHook;
import tterrag1112.life_in_the_village.Village.Decoration.VillageSizeTier;
import tterrag1112.life_in_the_village.Village.Planning.Graph.RoadGraph;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.PlanContext;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.ShapeRecipe;
import tterrag1112.life_in_the_village.Village.Planning.Rules.RuleContext;
import tterrag1112.life_in_the_village.Village.Planning.Rules.ShapeRule;
import tterrag1112.life_in_the_village.Village.Planning.Sectors.Sector;
import tterrag1112.life_in_the_village.Village.Planning.Terrain.TerrainAnalyzer;
import tterrag1112.life_in_the_village.Village.Planning.Terrain.TerrainProfile;
import tterrag1112.life_in_the_village.Village.Planning.Zoning.PlacementSlot;
import tterrag1112.life_in_the_village.Village.VillageTypeData;

import java.util.*;

/**
 * Orchestrator for village layout planning.
 *
 * <h3>Flow</h3>
 * <ol>
 *   <li>Analyse terrain</li>
 *   <li>Expand starter building list (resolve min/max counts)</li>
 *   <li>Build {@link LayoutDensityProfile} from village level</li>
 *   <li>Apply the type's rule stack to a fresh {@link RuleContext}</li>
 *   <li>Create a {@link PlanContext} wrapping a fresh {@link VillageLayout}</li>
 *   <li>Dispatch to a {@link ShapeRecipe} which composes primitives
 *       into the layout — roads, town square, building slots, gate endpoint</li>
 *   <li>Validate: every building must sit within
 *       {@code roadHalfWidth + footprintHalf + }{@link #VALIDATOR_ROAD_SLACK}
 *       blocks (Chebyshev) of a road centerline</li>
 *   <li>Run the orthogonal post-passes (farm plots, decoration clusters,
 *       Y clustering) and return the layout</li>
 * </ol>
 *
 * <h3>What's gone</h3>
 * The old cluster system, the TrunkGraph+TrunkRoadPlanner, the doubled
 * placement loop, {@code placeOneBuilding}, {@code placeCluster},
 * {@code refineRotations}, {@code placeWaterEdgeBuildings}, the capital
 * branch. All of it. Shape-specific logic lives in recipes now.
 */
public class VillagePlanner {

    private static final int FARM_PLOT_RADIUS = 10;
    private static final int DECORATION_RADIUS = 3;

    /**
     * Phase 16b: when the cascade engine marks a layout unplannable,
     * the planner stashes the reason here so {@link
     * tterrag1112.life_in_the_village.Village.VillageSpawner} can
     * distinguish "site genuinely unsuitable" from "planner rejected,
     * try local refinement". Reset at the start of every {@link #plan}
     * call. Single-threaded village planning makes the static field
     * safe; if planning ever becomes parallel, switch to ThreadLocal.
     */
    private static volatile String lastUnplannableReason = null;
    public static String lastUnplannableReason() { return lastUnplannableReason; }

    /**
     * Maximum Chebyshev distance allowed between a building's edge and the
     * nearest road centerline, in addition to the road's own reserved
     * half-width. This is wiggle room: it absorbs perturbation drift
     * (bounded by {@code PlacementSlot.maxDriftBlocks}, typically 6) plus a
     * small clearance gap. The full allowed centre-to-road distance is
     * {@code roadHalfWidth + footprintHalf + VALIDATOR_ROAD_SLACK}.
     *
     * <p>Replaces a hardcoded edge-distance limit that didn't scale with
     * footprint or perturbation bound. The slack value covers both — drift
     * up to 6 plus clearance — without admitting buildings that wandered
     * arbitrarily far from any road.
     */
    private static final int VALIDATOR_ROAD_SLACK = 6;

    /**
     * Conservative road half-width used by the validator. Matches
     * {@code RoadShape.RoadTier.VILLAGE_ROAD.reservedHalfWidth()} (3) which
     * is the value all road tiers actually reserve. Hardcoding here avoids
     * threading the per-edge tier through the validator just for this one
     * tolerance check; the formula is robust to small under-estimates.
     */
    private static final int VALIDATOR_ROAD_HALF_WIDTH = 3;

    /**
     * Slack for ring/floating slots whose committed building has no
     * feeding road (PlazaGenerator civic slots, agricultural fringes,
     * hull-floating clusters). The validator falls back to a hull-distance
     * check measured from village centre. The bound is
     * {@code ring2Radius + footprintHalf + VALIDATOR_HULL_SLACK}.
     */
    private static final int VALIDATOR_HULL_SLACK = 8;

    /**
     * Phase A / spec §10.5: SlotsDropped cascade threshold. If the
     * SlotEmitter's {@code dropRatio} exceeds this, the engine fires
     * the {@code SlotsDropped} reason and the recipe's {@code reEmit}
     * decides whether to recover or advance the chain. 0.30 is the
     * spec's initial guess; Phase 23.2 measurement informs tuning.
     */
    private static final double SLOTS_DROPPED_THRESHOLD = 0.30;

    // =========================================================================
    // Entry point
    // =========================================================================

    public static Optional<VillageLayout> plan(
            ServerLevel level, BlockPos origin,
            VillageTypeData typeData, Random rng, int villageLevel) {

        // Reset cross-call cascade-state signal at the entry to plan().
        lastUnplannableReason = null;

        // 1. Terrain analysis
        TerrainProfile terrain = TerrainAnalyzer.analyze(level, origin);
        if (!terrain.isSuitableFor(typeData.getTags())) {
            float typeSuit = TerrainProfile.computeSuitability(
                    terrain.flatRatio(), terrain.waterRatio(), terrain.steepRatio(),
                    typeData.getTags());
            String detail = String.format(
                    "suitability=%.2f type_suitability=%.2f (flat=%.2f water=%.2f steep=%.2f tree=%.2f) hasWater=%s hasRidges=%d",
                    terrain.suitability(), typeSuit,
                    terrain.flatRatio(),
                    terrain.waterRatio(),
                    terrain.steepRatio(),
                    terrain.treeRatio(),
                    terrain.hasWater(),
                    terrain.ridges().size());

            tterrag1112.life_in_the_village.Kingdom.Placement.PlacementFailureRecorder.record(
                    tterrag1112.life_in_the_village.Kingdom.Placement.PlacementFailureRecorder
                            .Reason.TERRAIN_UNSUITABLE,
                    detail, origin, typeData.getType());

            System.out.println("VillagePlanner: unsuitable terrain at " + origin + " — " + detail);
            return Optional.empty();
        }

        // 2. Expand buildings
        List<VillageTypeData.StarterBuilding> expanded =
                expandBuildingList(typeData.getStarterBuildings(), rng);
        if (expanded.isEmpty()) {
            System.out.println("VillagePlanner: no starter buildings for type "
                    + typeData.getType());
            PlacementFailureRecorder
                    .record(PlacementFailureRecorder.Reason.INSUFFICIENT_BUILDINGS,
                            "no starter buildings for type",
                            origin, typeData.getType());
            return Optional.empty();
        }

        // 3. Density profile
        LayoutDensityProfile density = LayoutDensityProfile.forLevel(villageLevel);
        StructureSizeCache sizeCache = new StructureSizeCache(level);

        // 4. Apply rule stack
        RuleContext ctx = new RuleContext(terrain, origin, rng.nextLong());
        for (ShapeRule rule : typeData.getShapeRules()) {
            try {
                rule.apply(ctx);
            } catch (Exception e) {
                System.out.println("VillagePlanner: rule '" + rule.typeName()
                        + "' failed: " + e.getMessage());
                PlacementFailureRecorder
                        .record(PlacementFailureRecorder.Reason.SHAPE_RULE_REJECTED,
                                "rule" + rule.typeName() +" failed:" + e.getMessage(),
                                origin, typeData.getType());
            }
        }

        // 5. Build the layout + plan context
        VillageLayout layout = new VillageLayout(terrain, density);
        BlockPos centre = resolveCentre(level, terrain, ctx);
        layout.setCenter(centre);

        // Remaining list is mutable; the recipe claims from it
        List<VillageTypeData.StarterBuilding> remaining = new ArrayList<>(expanded);

        long worldSeed = ctx.seed();

        // Phase 4: noise-only FeatureMap build. All terrain access flows
        // through FeatureSamplers so the result is deterministic from
        // (worldSeed, centre) regardless of chunk-load order. The map is
        // stashed on the layout so realization can call refine(level)
        // before any consumer reads from it.
        tterrag1112.life_in_the_village.Village.Planning.Features.FeatureMap features =
                tterrag1112.life_in_the_village.Village.Planning.Features.FeatureMap
                        .buildPlanning(centre, worldSeed, level, expanded);
        layout.setFeatures(features);

        PlanContext pctx = new PlanContext(
                level, layout, sizeCache, rng, ctx, density, worldSeed, remaining, features);

        // Variant context — derived once per village from the type
        // data + the resolved tier. The matcher reads this when
        // picking variants per slot (P0a-06).
        VillageSizeTier tier = VillageSizeTier.fromBuildingCount(expanded.size());
        StyleSelection styleSel = StyleAutoDeriver.resolve(typeData, tier);
        pctx.setVariantContext(typeData, styleSel, tier,
                VillageAgeCategoryHook.forNewVillage());

        // 6. Dispatch to the recipe. Seed finalShape with the requested shape
        // so the cascade engine can update it on fallback.
        VillageTypeData.ShapeType requestedShape =
                typeData.getShapeProfile().shapeType();
        pctx.setFinalShape(requestedShape);

        // Phase 22: build the cascade chain — [primary, ...fallbacks]. The
        // schema-declared fallback list is on VillageTypeData. The
        // chain-walking that used to live in BaseRecipe.runWithCascade is
        // now in this planner (Phase A relocation per spec §8).
        java.util.List<VillageTypeData.ShapeType> cascadeChain =
                new java.util.ArrayList<>();
        cascadeChain.add(requestedShape);
        if (typeData.getFallbackChain() != null) {
            cascadeChain.addAll(typeData.getFallbackChain());
        }
        pctx.setCascadeChain(cascadeChain);
        pctx.setCascadeChainPosition(0);

        int initialBuildingCount = expanded.size();
        VillageTypeData.ShapeType currentShape = requestedShape;

        // ── Phase A adaptive orchestration (per spec §2) ─────────────────
        // 1. recipe.compose(pctx) returns a LayoutBlueprint
        // 2. realiseRoads(blueprint.roads, pctx)
        // 3. checkPrimarySpine — if non-OK, handleSevereTruncation
        // 4. realisePlazas(blueprint.plazas)
        // 5. registerSectors(blueprint.sectors)
        // 6. populateNamedAnchors(blueprint.namedAnchors)
        // 7. SlotEmitter.emit (Phase A: stub returns empty)
        // 8. SlotsDropped check — handleSlotsDropped if dropRatio > 0.30
        // 9. matcher / farm-plot / validator (existing)
        //
        // Phase A: every recipe stub throws RecipeNotPortedException at
        // step 1. The unreachable orchestration below still has to
        // compile cleanly — Phase B/C/D will exercise it.
        tterrag1112.life_in_the_village.Village.Planning.Adaptive.LayoutBlueprint
                blueprint;
        try {
            blueprint = ShapeRecipe.forShape(currentShape).compose(pctx);
        } catch (tterrag1112.life_in_the_village.Village.Planning.Adaptive
                .RecipeNotPortedException e) {
            String reason = "recipe not ported: " + currentShape;
            lastUnplannableReason = reason;
            System.out.println("VillagePlanner: " + reason
                    + " — " + e.getMessage());
            PlacementFailureRecorder.record(
                    PlacementFailureRecorder.Reason.SHAPE_RULE_REJECTED,
                    reason, origin, typeData.getType());
            layout.markUnplannable(reason);
            printVillageSummary(pctx, layout, initialBuildingCount,
                    requestedShape, "RECIPE_NOT_PORTED");
            return Optional.empty();
        } catch (Exception e) {
            System.out.println("VillagePlanner: recipe failed — " + e.getMessage());
            e.printStackTrace();
            PlacementFailureRecorder.record(
                    PlacementFailureRecorder.Reason.SHAPE_RULE_REJECTED,
                    "recipe failed", origin, typeData.getType());
            return Optional.empty();
        }
        pctx.setCurrentBlueprint(blueprint);

        realiseRoads(blueprint.roads(), pctx);

        // Phase 16b: severe-truncation cascade on the first declared road.
        var primarySpineEdge = blueprint.roads().isEmpty() ? null
                : pctx.findEdge(blueprint.roads().get(0).ref());
        if (primarySpineEdge != null) {
            var primaryResult = primarySpineEdge.result();
            pctx.recordPrimarySpine(primaryResult);
            var spineStatus = tterrag1112.life_in_the_village.Village
                    .Planning.Primitives.BaseRecipe
                    .checkPrimarySpine(primaryResult);
            if (spineStatus != tterrag1112.life_in_the_village.Village
                    .Planning.Primitives.BaseRecipe.RecipeStatus.OK) {
                blueprint = handleSevereTruncation(
                        blueprint, pctx, primaryResult);
                if (blueprint == null) {
                    return finalizeUnplannable(pctx, layout, origin, typeData,
                            initialBuildingCount, requestedShape,
                            "cascade exhausted (truncation)");
                }
                pctx.setCurrentBlueprint(blueprint);
                realiseRoads(blueprint.roads(), pctx);
            }
        }

        realisePlazas(blueprint.plazas(), pctx);
        registerSectors(blueprint.sectors(), pctx);
        populateNamedAnchors(blueprint.namedAnchors(), layout);

        // SlotEmitter (Phase A stub — Phase B implements resolvers).
        var emitter = new tterrag1112.life_in_the_village.Village.Planning
                .Adaptive.SlotEmitter();
        var emitterReport = emitter.emit(
                blueprint.slotIntentions(), layout, pctx.features, pctx);
        if (emitterReport.dropRatio() > SLOTS_DROPPED_THRESHOLD) {
            blueprint = handleSlotsDropped(blueprint, pctx, emitterReport);
            if (blueprint == null) {
                return finalizeUnplannable(pctx, layout, origin, typeData,
                        initialBuildingCount, requestedShape,
                        "cascade exhausted (slots dropped)");
            }
            // Phase A: re-emission re-realises geometry and re-runs the
            // emitter. Phase B/C tunes the recursion bound.
            pctx.setCurrentBlueprint(blueprint);
            realiseRoads(blueprint.roads(), pctx);
            realisePlazas(blueprint.plazas(), pctx);
            registerSectors(blueprint.sectors(), pctx);
            populateNamedAnchors(blueprint.namedAnchors(), layout);
            emitterReport = emitter.emit(
                    blueprint.slotIntentions(), layout, pctx.features, pctx);
        }

        // Phase 16b: site may have been marked unplannable during
        // realisation (e.g. PlazaGenerator refused the site). Treat as
        // terminal.
        if (layout.isUnplannable()) {
            String reason = layout.unplannableReason() != null
                    ? layout.unplannableReason() : "(no reason given)";
            lastUnplannableReason = reason;
            System.out.println("VillagePlanner: site unplannable — " + reason);
            PlacementFailureRecorder.record(
                    PlacementFailureRecorder.Reason.TERRAIN_UNSUITABLE,
                    "site unplannable: " + reason,
                    origin, typeData.getType());
            printVillageSummary(pctx, layout, initialBuildingCount,
                    requestedShape, "UNPLANNABLE");
            return Optional.empty();
        }

        // Phase 9: snapshot the sectors so /liv layout debug
        // show_sectors can render them.
        layout.setDebugSectors(new java.util.ArrayList<>(pctx.offeredSectors()));
        pctx.runMatcher();

        if (layout.buildings().isEmpty()) {
            System.out.println("VillagePlanner: recipe produced no buildings");
            PlacementFailureRecorder.record(
                    PlacementFailureRecorder.Reason.INSUFFICIENT_BUILDINGS,
                    "recipe produced no buildings",
                    origin, typeData.getType());
            printVillageSummary(pctx, layout, initialBuildingCount,
                    requestedShape, "NO_BUILDINGS");
            return Optional.empty();
        }

        // 7. Validate the plan. Phase 22: significant validation loss
        // (passed/total < 0.6) triggers ValidationFailed cascade — Phase
        // A keeps the safety net; with pre-validated slots from Phase B
        // it should rarely fire.
        if (!validatePlan(layout, pctx)) {
            int passed = countValidatedBuildings(layout);
            int total  = layout.buildings().size();
            if (total > 0 && passed < total * 0.6) {
                blueprint = handleValidationFailed(
                        blueprint, pctx, passed, total);
                if (blueprint == null) {
                    return finalizeUnplannable(pctx, layout, origin, typeData,
                            initialBuildingCount, requestedShape,
                            "cascade exhausted (validator)");
                }
                // Phase A: re-emission path. Unreachable in Phase A
                // (recipes throw); Phase B/C/D tunes this.
                pctx.setCurrentBlueprint(blueprint);
                // Fall through to the validator's "fail" branch below
                // — the planner doesn't recurse into a re-realisation
                // loop here for Phase A. Phase 23.2 measurement informs
                // whether to add full re-realisation.
            }
            System.out.println("VillagePlanner: plan validation failed");
            PlacementFailureRecorder.record(
                    PlacementFailureRecorder.Reason.INSUFFICIENT_BUILDINGS,
                    "plan validation failed",
                    origin, typeData.getType());
            printVillageSummary(pctx, layout, initialBuildingCount,
                    requestedShape, "VALIDATION_FAILED");
            return Optional.empty();
        }

        // Phase 17: emit + claim + validate farm plot slots. Runs only after
        // the layout has passed full validation — no point planning plots
        // for a layout that's about to be discarded.
        runFarmPlotPass(pctx, typeData);

        // Ensure town square has SOMETHING marked if recipe didn't.
        // Doc 04 §"Tier scaling" — the fallback uses HAMLET radius
        // (3) since this branch only runs when the recipe never
        // committed a plaza, which is a degenerate path that
        // shouldn't host larger tiers anyway.
        if (layout.getTownSquarePos() == null) {
            BlockPos sq = solidSurface(level, centre);
            layout.addForced(new LayoutSlot(
                    LayoutSlot.SlotType.DECORATION, sq,
                    tterrag1112.life_in_the_village.Village.Decoration
                            .TownSquare.TownSquareTier.RADIUS_HAMLET));
            layout.setTownSquarePos(sq);
        }

        // 8. Orthogonal post-passes
        clusterBuildingYLevels(layout, level);
        placeFarmPlots(layout, level, terrain, density, typeData, rng);
        placeDecorationClusters(layout, level, terrain, density, rng);

        System.out.println("VillagePlanner: planned "
                + typeData.getShapeProfile().shapeType()
                + " village — " + layout);
        printVillageSummary(pctx, layout, initialBuildingCount,
                requestedShape, "OK");

        // Phase 19: build the immutable LayoutPlan handoff. Attached to
        // the layout via getPlan() so existing callers don't break;
        // spawner / decorator / FarmPlotPlacer migrate to read the plan
        // instead of layout's mutable state.
        LayoutPlan plan = LayoutPlanBuilder.build(layout, pctx);
        layout.setPlan(plan);
        StringBuilder planLog = new StringBuilder("[LAYOUT-PLAN] ");
        appendPlanSnapshot(planLog, plan);
        // Phase 22: append cascade chain context when non-trivial (chain
        // had a fallback that was either tried or available).
        if (pctx.cascadeChain().size() > 1) {
            planLog.append("  cascade: started=").append(requestedShape)
                   .append(" chain=").append(pctx.cascadeChain())
                   .append(" position=").append(pctx.cascadeChainPosition())
                   .append(pctx.cascadeChainPosition() > 0
                           ? " fallback-fired" : " no-fallback")
                   .append('\n');
        }
        System.out.println(planLog);
        return Optional.of(layout);
    }

    /** Phase 19 plan-dump snippet shared between dumpPlan (when a plan
     *  is attached) and the post-build [LAYOUT-PLAN] log line.
     *  Phase 22 extended with chain context. */
    private static void appendPlanSnapshot(StringBuilder sb, LayoutPlan plan) {
        sb.append("  shape=").append(plan.finalShape())
          .append(" status=").append(plan.status())
          .append(" truncations=").append(plan.truncations())
          .append(" retries=").append(plan.cascadeRetries());
        if (plan.unplannableReason() != null) {
            sb.append(" reason=\"").append(plan.unplannableReason()).append('"');
        }
        sb.append('\n');
        if (plan.plaza() != null) {
            sb.append("  plaza=present centre=").append(plan.plaza().centre())
              .append(" plazaR=").append(plan.plaza().townSquareRadius())
              .append(" civicRingR=").append(plan.plaza().civicRingRadius())
              .append(" civicSlots=").append(plan.plaza().civicSlots().size())
              .append('\n');
        } else {
            sb.append("  plaza=absent\n");
        }
        sb.append("  buildings=").append(plan.buildings().size())
          .append(" plotSlots=").append(plan.plotSlots().size())
          .append(" roads=").append(plan.roads().size())
          .append(" sectors=").append(plan.sectors().size())
          .append('\n');
        sb.append("  anchors:");
        if (plan.anchors().isEmpty()) {
            sb.append(" (none)\n");
        } else {
            sb.append('\n');
            for (var entry : plan.anchors().entrySet()) {
                sb.append("    ").append(entry.getKey())
                  .append("=").append(entry.getValue()).append('\n');
            }
        }
    }

    /**
     * Phase 16b per-village summary. Combines compose-time cascade stats
     * (truncations, retries, finalShape, primary spine ratio) with
     * post-matcher validated/total counts. Printed after the matcher
     * runs (or as close as the failure path allows).
     */
    private static void printVillageSummary(PlanContext pctx, VillageLayout layout,
            int initialBuildingCount,
            VillageTypeData.ShapeType requestedShape, String status) {
        var spine = pctx.primarySpineResult();
        String spineRatio = spine == null ? "n/a"
                : String.format("%.2f", spine.completionRatio());
        String spineLen = spine == null ? "n/a"
                : (spine.actualLength() + "/" + spine.intendedLength());
        var finalShape = pctx.finalShape() != null
                ? pctx.finalShape() : requestedShape;
        int validated = layout.buildings().size();
        System.out.println("[VILLAGE-SUMMARY] shape=" + requestedShape
                + " status=" + status
                + " spineRatio=" + spineRatio
                + " spineLen=" + spineLen
                + " truncations=" + pctx.truncationCount()
                + " retries=" + pctx.cascadeRetryCount()
                + " finalShape=" + finalShape
                + " validated=" + validated + "/" + initialBuildingCount);
    }

    // =========================================================================
    // Phase 17: farm plot pass (planner-side)
    // =========================================================================

    /**
     * Emit + claim + validate farm plot slots.
     *
     * <p>Steps: collect FARMHOUSE LayoutSlots from the validated layout;
     * call {@link RecipeHelpers#emitFarmPlotSlots} to emit candidate slots
     * around the village; for each candidate, find the nearest farmhouse
     * (capped at {@code config.plotsPerFarmhouse()} per owner); validate
     * the slot's footprint against road overlap and across-footprint
     * flatness (delta-Y &le; 6); register validated slots on the layout
     * with their {@link FarmPlotSpec}.
     *
     * <p>Plots that fail validation are silently dropped — Phase 17 is
     * plumbing-side; aesthetic recovery (try a different angle / radius)
     * is authoring work for later. The realiser surfaces the dropped
     * count in its log.
     */
    private static void runFarmPlotPass(PlanContext pctx, VillageTypeData typeData) {
        VillageTypeData.FarmPlotConfig config = typeData.getFarmPlotConfig();
        if (config.placement() == VillageTypeData.FarmPlotPlacement.NONE) return;

        VillageLayout layout = pctx.layout;
        List<LayoutSlot> farmhouses = layout.buildings().stream()
                .filter(b -> b.getBuildingType() == BuildingType.FARMHOUSE)
                .toList();
        if (farmhouses.isEmpty()) {
            System.out.println("VillagePlanner: no FARMHOUSE buildings — "
                    + "skipping farm plot pass");
            return;
        }

        List<PlacementSlot> candidates = RecipeHelpers.emitFarmPlotSlots(
                pctx, farmhouses.size(), config);
        if (candidates.isEmpty()) return;

        Map<BlockPos, Integer> claimedPerFarmhouse = new HashMap<>();
        int validated = 0;
        int droppedClaim = 0;
        int droppedValidator = 0;

        for (PlacementSlot slot : candidates) {
            // Ownership: nearest farmhouse by chebyshev — same metric the
            // matcher uses so plot ownership reflects walking distance.
            LayoutSlot owner = farmhouses.stream()
                    .min(Comparator.comparingDouble(
                            f -> f.getPos().distSqr(slot.pos())))
                    .orElse(null);
            if (owner == null) { droppedClaim++; continue; }

            BlockPos ownerPos = owner.getPos();
            int claimed = claimedPerFarmhouse.getOrDefault(ownerPos, 0);
            if (claimed >= config.plotsPerFarmhouse()) {
                droppedClaim++;
                continue;
            }
            if (!validatePlotSlot(pctx, slot)) {
                droppedValidator++;
                continue;
            }

            FarmPlot.PlotSubtype subtype = slot.tags().contains(
                    tterrag1112.life_in_the_village.Village.Planning.Zoning
                            .SlotTag.FARM_PLOT_ANIMAL)
                    ? FarmPlot.PlotSubtype.ANIMAL_PEN
                    : FarmPlot.PlotSubtype.CROP_FIELD;

            // Recover base half-dims from the slot's footprint budget by
            // peeling off the EDGE_JITTER margin the helper added. The
            // realiser's PlotShape generator takes these as input.
            int halfW = slot.footprintBudgetW() / 2 - 3;  // EDGE_JITTER = 3
            int halfL = slot.footprintBudgetL() / 2 - 3;

            FarmPlotSpec spec = new FarmPlotSpec(
                    ownerPos, subtype, halfW, halfL, pctx.rng.nextInt());
            layout.addPlotSlot(slot, spec);
            claimedPerFarmhouse.merge(ownerPos, 1, Integer::sum);
            validated++;
        }

        System.out.println("VillagePlanner: farm plot pass — emitted="
                + candidates.size() + " validated=" + validated
                + " droppedClaim=" + droppedClaim
                + " droppedValidator=" + droppedValidator
                + " (placement=" + config.placement() + " perFh="
                + config.plotsPerFarmhouse() + ")");

        // Plot slots aren't covered by the main dumpPlan (which ran inside
        // validatePlan, before this pass). Emit a separate section so the
        // realisation team can verify positions against the world.
        if (validated > 0) {
            StringBuilder sb = new StringBuilder();
            sb.append("\n--- PLOT SLOTS ---\n");
            for (PlacementSlot s : layout.plotSlots()) {
                FarmPlotSpec spec = layout.getPlotSpec(s);
                if (spec == null) continue;
                sb.append("  pos=").append(s.pos())
                  .append(" subtype=").append(spec.subtype())
                  .append(" half=").append(spec.halfW()).append('x')
                  .append(spec.halfL())
                  .append(" fp=").append(s.footprintBudgetW()).append('x')
                  .append(s.footprintBudgetL())
                  .append(" owner=").append(spec.ownerFarmhousePos())
                  .append(" seed=").append(spec.edgeJitterSeed())
                  .append('\n');
            }
            System.out.println(sb.toString());
        }
    }

    /** Plot slot validator: rejects road-footprint overlap and slopes
     *  steeper than 6 blocks across the footprint. */
    private static boolean validatePlotSlot(PlanContext pctx, PlacementSlot slot) {
        int halfW = slot.footprintBudgetW() / 2;
        int halfL = slot.footprintBudgetL() / 2;
        int w = halfW * 2;
        int l = halfL * 2;
        BlockPos origin = new BlockPos(
                slot.pos().getX() - halfW,
                slot.pos().getY(),
                slot.pos().getZ() - halfL);

        if (!pctx.layout.getRoadFootprint().isClear(origin, w, l, 1)) {
            return false;
        }

        // Across-footprint flatness — different metric from Phase 16b's
        // per-step maxStepDeltaY (which is 8). 6 here is "field flatness":
        // a 6-block delta over a 24x20 plot is roughly the upper bound a
        // levelPad pass can hide.
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (int dx = -halfW; dx <= halfW; dx += 4) {
            for (int dz = -halfL; dz <= halfL; dz += 4) {
                int y = pctx.level.getHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        slot.pos().getX() + dx, slot.pos().getZ() + dz);
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
            }
        }
        return (maxY - minY) <= 6;
    }

    // =========================================================================
    // Validation
    // =========================================================================

    private static boolean validatePlan(VillageLayout layout, PlanContext pctx) {
        dumpPlan(layout, pctx, "POST_COMPOSE");
        List<LayoutSlot> buildings = layout.buildings();
        for (LayoutSlot slot : buildings) {
            if (!validateBuildingDistance(slot, layout)) return false;
        }
        return true;
    }

    /**
     * Phase 22: counts buildings that pass {@link
     * #validateBuildingDistance(LayoutSlot, VillageLayout)} without
     * short-circuiting. Used by the validator-triggered cascade
     * threshold check.
     */
    private static int countValidatedBuildings(VillageLayout layout) {
        int passed = 0;
        for (LayoutSlot slot : layout.buildings()) {
            if (validateBuildingDistance(slot, layout)) passed++;
        }
        return passed;
    }

    // =========================================================================
    // Phase A: blueprint realisation helpers
    // =========================================================================
    //
    // These translate the recipe's declarative LayoutBlueprint into
    // mutations on VillageLayout / PlanContext. Phase A wires them but
    // they're unreachable in normal operation (recipe stubs throw
    // RecipeNotPortedException before realisation runs). Phase B/C/D
    // exercises them.

    /**
     * Realises each road declaration: invokes the primitive's
     * computeCenterline, builds a RealisedEdge, registers it on
     * PlanContext (so SlotEmitter resolvers can look up edges by ref),
     * and adds the road to the layout's RoadGraph.
     */
    private static void realiseRoads(
            java.util.List<tterrag1112.life_in_the_village.Village.Planning
                    .Adaptive.RoadDeclaration> roads,
            PlanContext pctx) {
        for (var decl : roads) {
            // Probe first — gives us the RoadResult with isComplete +
            // TerminationReason for RealisedEdge. Then commit to the
            // graph via VillageLayout.addRoad which re-runs
            // computeCenterline. Phase A: redundant compute is fine
            // since this code is unreachable; Phase B/C de-dup if
            // measurement reveals it matters.
            tterrag1112.life_in_the_village.Village.Planning.Primitives
                    .RoadResult result =
                    tterrag1112.life_in_the_village.Village.Planning.Primitives
                            .BaseRecipe.computeAndRecord(decl.primitive(), pctx);
            pctx.layout.addRoad(
                    decl.primitive(), pctx.level, pctx.worldSeed, decl.role());
            var realised = new tterrag1112.life_in_the_village.Village.Planning
                    .Adaptive.RealisedEdge(
                    decl.ref(), decl.role(), decl.tier(),
                    result.centerline(), result);
            pctx.registerRealisedEdge(decl.ref(), realised);
        }
    }

    /**
     * Realises each plaza declaration via PlazaGenerator.generate.
     * Translation: PlazaDeclaration → PlazaSpec ({@code targetRadius}
     * → {@code targetArea = πr²} approximation; the existing PlazaSpec
     * uses target area, so we square the radius and scale by π).
     */
    private static void realisePlazas(
            java.util.List<tterrag1112.life_in_the_village.Village.Planning
                    .Adaptive.PlazaDeclaration> plazas,
            PlanContext pctx) {
        for (var decl : plazas) {
            int targetArea = (int) Math.round(
                    Math.PI * decl.targetRadius() * decl.targetRadius());
            var spec = new tterrag1112.life_in_the_village.Village.Decoration
                    .Plaza.PlazaSpec(
                    decl.purpose(), decl.center(), targetArea,
                    decl.shape(), java.util.Optional.empty());
            tterrag1112.life_in_the_village.Village.Decoration.Plaza
                    .PlazaGenerator.generate(pctx, spec);
            // PlazaGenerator currently registers PlazaRegions on
            // pctx via its internal logic. The civicSector field on
            // PlazaDeclaration is recorded for SlotEmitter's
            // PlazaPerimeter resolver in Phase B; nothing reads it
            // in Phase A.
        }
    }

    /**
     * Translates each SectorDeclaration into a Sector record and
     * registers via {@code pctx.offerSector}. Defaults: empty slot
     * list, canGrow=false, growth=FixedGrowth.INSTANCE,
     * exclusionShape=null. {@code parentEdge → parentEdgeId} (-1 if
     * null).
     */
    private static void registerSectors(
            java.util.List<tterrag1112.life_in_the_village.Village.Planning
                    .Adaptive.SectorDeclaration> sectors,
            PlanContext pctx) {
        for (var decl : sectors) {
            int parentEdgeId = -1;
            if (decl.parentEdge() != null) {
                var realised = pctx.findEdge(decl.parentEdge());
                if (realised != null) {
                    // The road graph assigns sequential IDs in
                    // insertion order; SectorDeclaration's parentEdge
                    // is symbolic (string-handle) so we need to map it
                    // to the actual edge ID. Phase B/C: lift this onto
                    // RealisedEdge so the lookup is direct. For
                    // Phase A, scan the graph's edges by centerline
                    // identity.
                    for (var e : pctx.layout.getRoadGraph().allEdges()) {
                        if (e.centerline().equals(realised.centerline())) {
                            parentEdgeId = e.id();
                            break;
                        }
                    }
                }
            }
            Sector s = new Sector(
                    decl.id(),
                    decl.role(),
                    decl.zone(),
                    java.util.List.of(),
                    decl.cap(),
                    /* canGrow */ false,
                    tterrag1112.life_in_the_village.Village.Planning.Sectors
                            .FixedGrowth.INSTANCE,
                    parentEdgeId,
                    /* exclusionShape */ null,
                    decl.maxFp());
            pctx.offerSector(s);
        }
    }

    /**
     * Threads named anchors from blueprint to layout. Phase 19's
     * AnchorKind enum has TOWN_SQUARE, MAIN_GATE, SECONDARY_GATE,
     * RIVER_LANDING, HIGH_GROUND, BRIDGE_HEAD, HARBOR, KEEP — the
     * planner-side handlers below cover the two with existing layout
     * setters; Phase B/C wires the rest as their consumers land.
     */
    private static void populateNamedAnchors(
            java.util.Map<tterrag1112.life_in_the_village.Village.Planning
                    .AnchorKind, BlockPos> namedAnchors,
            VillageLayout layout) {
        for (var entry : namedAnchors.entrySet()) {
            switch (entry.getKey()) {
                case TOWN_SQUARE -> layout.setTownSquarePos(entry.getValue());
                case MAIN_GATE -> layout.setMainGateEndpoint(entry.getValue());
                default -> {
                    // Other AnchorKinds (SECONDARY_GATE, HIGH_GROUND, etc.)
                    // surface in LayoutPlan.anchors via LayoutPlanBuilder
                    // when their layout-side wiring lands. Phase A: no-op.
                }
            }
        }
    }

    // =========================================================================
    // Phase A: cascade dispatch helpers
    // =========================================================================
    //
    // The chain-walking logic that lived in BaseRecipe.runWithCascade
    // (Phase 22) lives here now. Each handler calls recipe.reEmit;
    // null return means "advance the schema chain to the next recipe."
    // Returns the new blueprint or null if the chain is exhausted.

    @org.jetbrains.annotations.Nullable
    private static tterrag1112.life_in_the_village.Village.Planning.Adaptive
            .LayoutBlueprint handleSevereTruncation(
            tterrag1112.life_in_the_village.Village.Planning.Adaptive
                    .LayoutBlueprint current,
            PlanContext pctx,
            tterrag1112.life_in_the_village.Village.Planning.Primitives
                    .RoadResult primary) {
        var reason = new tterrag1112.life_in_the_village.Village.Planning
                .Primitives.BaseRecipe.ReEmitReason.SevereTruncation(primary);
        return dispatchReEmit(current, pctx, reason);
    }

    @org.jetbrains.annotations.Nullable
    private static tterrag1112.life_in_the_village.Village.Planning.Adaptive
            .LayoutBlueprint handleSlotsDropped(
            tterrag1112.life_in_the_village.Village.Planning.Adaptive
                    .LayoutBlueprint current,
            PlanContext pctx,
            tterrag1112.life_in_the_village.Village.Planning.Adaptive
                    .SlotEmitter.EmitterReport report) {
        var reason = new tterrag1112.life_in_the_village.Village.Planning
                .Primitives.BaseRecipe.ReEmitReason.SlotsDropped(
                report.totalRequested() - report.totalEmitted(),
                report.totalRequested());
        return dispatchReEmit(current, pctx, reason);
    }

    @org.jetbrains.annotations.Nullable
    private static tterrag1112.life_in_the_village.Village.Planning.Adaptive
            .LayoutBlueprint handleValidationFailed(
            tterrag1112.life_in_the_village.Village.Planning.Adaptive
                    .LayoutBlueprint current,
            PlanContext pctx, int passed, int total) {
        var reason = new tterrag1112.life_in_the_village.Village.Planning
                .Primitives.BaseRecipe.ReEmitReason.ValidationFailed(
                passed, total, java.util.List.of());
        return dispatchReEmit(current, pctx, reason);
    }

    /**
     * Common cascade dispatch: ask the recipe to reEmit; if it returns
     * null, advance the Phase 22 schema chain to the next recipe and
     * compose from scratch. Returns the new blueprint or null if the
     * chain is exhausted.
     */
    @org.jetbrains.annotations.Nullable
    private static tterrag1112.life_in_the_village.Village.Planning.Adaptive
            .LayoutBlueprint dispatchReEmit(
            tterrag1112.life_in_the_village.Village.Planning.Adaptive
                    .LayoutBlueprint current,
            PlanContext pctx,
            tterrag1112.life_in_the_village.Village.Planning.Primitives
                    .BaseRecipe.ReEmitReason reason) {
        var currentShape = current.shape();
        var recipe = ShapeRecipe.forShape(currentShape);
        // First chance: ask the recipe to recover with a new blueprint.
        // BaseRecipe.reEmit is public (Phase A); default returns null
        // (chain advance). Recipes can override for structural recovery
        // (rotate axis, shrink ring) when their Phase C/D ports land.
        if (recipe instanceof tterrag1112.life_in_the_village.Village.Planning
                .Primitives.BaseRecipe br) {
            var recovered = br.reEmit(reason, pctx);
            if (recovered != null) {
                return recovered;
            }
        }
        // Advance the Phase 22 schema chain.
        int nextPos = pctx.cascadeChainPosition() + 1;
        if (nextPos >= pctx.cascadeChain().size()) {
            return null;
        }
        var nextShape = pctx.cascadeChain().get(nextPos);
        pctx.advanceCascadeChain();
        pctx.resetForFallback();
        pctx.layout.resetForFallback();
        pctx.setFinalShape(nextShape);
        System.out.println("[CASCADE] " + currentShape
                + " -> " + nextShape
                + " reason=" + tterrag1112.life_in_the_village.Village.Planning
                        .Primitives.BaseRecipe.describeReason(reason)
                + " chainPos=" + nextPos);
        try {
            return ShapeRecipe.forShape(nextShape).compose(pctx);
        } catch (tterrag1112.life_in_the_village.Village.Planning.Adaptive
                .RecipeNotPortedException e) {
            pctx.layout.markUnplannable("recipe not ported: " + nextShape);
            return null;
        }
    }

    /**
     * Phase A: emit the failure summary and return Optional.empty for
     * any unplannable terminal. Pulls together the markUnplannable +
     * PlacementFailureRecorder.record + printVillageSummary calls that
     * the old plan() body repeated at every failure path.
     */
    private static Optional<VillageLayout> finalizeUnplannable(
            PlanContext pctx, VillageLayout layout, BlockPos origin,
            VillageTypeData typeData, int initialBuildingCount,
            VillageTypeData.ShapeType requestedShape, String reason) {
        lastUnplannableReason = reason;
        if (!layout.isUnplannable()) {
            layout.markUnplannable(reason);
        }
        System.out.println("VillagePlanner: " + reason);
        PlacementFailureRecorder.record(
                PlacementFailureRecorder.Reason.TERRAIN_UNSUITABLE,
                reason, origin, typeData.getType());
        printVillageSummary(pctx, layout, initialBuildingCount,
                requestedShape, "UNPLANNABLE");
        return Optional.empty();
    }

    /**
     * Validates a single building's distance to the road that fed its
     * placement slot. Two branches:
     *
     * <ul>
     *   <li><b>Has feeding road</b> — slot was emitted along a specific
     *       road's centerline. Measured distance is to <em>that</em> road
     *       only, not the nearest road in the graph. Fixes the legacy bug
     *       where civic buildings on plaza-tangent slots were measured
     *       across the plaza interior to a road on the opposite side.</li>
     *   <li><b>Ring/floating slot</b> — no feeding road (PlazaGenerator
     *       civic slot, agricultural fringe, hull-floating cluster).
     *       Falls back to a hull-distance check: the building must be
     *       within {@code ring2 + footprintHalf + VALIDATOR_HULL_SLACK}
     *       of the village centre.</li>
     * </ul>
     */
    private static boolean validateBuildingDistance(LayoutSlot slot,
                                                    VillageLayout layout) {
        BlockPos centre = slot.getPos();
        int footprintHalf = Math.max(slot.getFootprintWidth(),
                slot.getFootprintLength()) / 2;
        java.util.List<BlockPos> feedingRoad = slot.getFeedingRoad();

        if (feedingRoad != null && !feedingRoad.isEmpty()) {
            int distance = nearestPointChebyshev(centre, feedingRoad);
            int allowed = VALIDATOR_ROAD_HALF_WIDTH
                    + footprintHalf
                    + VALIDATOR_ROAD_SLACK;
            if (distance > allowed) {
                System.out.println("VillagePlanner: building " + slot.getBuildingType()
                        + " at " + centre + " is " + distance
                        + " blocks from feeding road (max " + allowed
                        + " for footprint " + slot.getFootprintWidth()
                        + "x" + slot.getFootprintLength()
                        + ", feedingRoad=" + feedingRoad.size() + " pts)");
                return false;
            }
            return true;
        }

        // Ring/floating slot — no feeding road. Hull-distance check.
        BlockPos villageCentre = layout.getCenter();
        int ring2 = layout.getDensity().getRing2Radius();
        int dx = centre.getX() - villageCentre.getX();
        int dz = centre.getZ() - villageCentre.getZ();
        int distance = (int) Math.round(Math.sqrt((double) dx * dx + (double) dz * dz));
        int allowed = ring2 + footprintHalf + VALIDATOR_HULL_SLACK;
        if (distance > allowed) {
            System.out.println("VillagePlanner: building " + slot.getBuildingType()
                    + " at " + centre + " is " + distance
                    + " blocks from village centre (max " + allowed
                    + " for footprint " + slot.getFootprintWidth()
                    + "x" + slot.getFootprintLength()
                    + ", feedingRoad=none ring/floating)");
            return false;
        }
        return true;
    }

    /**
     * Structured six-section dump of the complete plan state. Called unconditionally
     * at the top of validatePlan so the output always lands in the log whether the
     * plan passes or fails. All output goes to System.out so it appears in the same
     * stream as the existing debug prints.
     *
     * <p>Sections: ROADS · SECTORS · SLOTS BY SECTOR · COMMITTED BUILDINGS ·
     * VALIDATION SUMMARY (with per-failed-building feedingRoad mismatch diagnosis).
     */
    private static void dumpPlan(VillageLayout layout, PlanContext pctx, String tag) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n=== PLAN DUMP [").append(tag).append("] centre=")
          .append(layout.getCenter()).append(" ===\n");

        // ── SECTION 0: LAYOUT PLAN SNAPSHOT (Phase 19) ───────────────────
        // Top-of-dump verification surface for the immutable LayoutPlan
        // that the spawner / decorator / expansion will consume. The plan
        // is built at the END of plan(), which is AFTER dumpPlan runs
        // inside validatePlan — so the snapshot here reflects the plan
        // FROM A PRIOR successful run if the layout has one attached
        // (none on the first dump call). On the post-validate dump call
        // the plan is also still null (not built yet); the snapshot is
        // mostly visible for the OK path's printVillageSummary.
        // For a clean snapshot of the actually-built plan, see the
        // [LAYOUT-PLAN] line emitted from plan() after build().
        LayoutPlan attachedPlan = layout.getPlan();
        sb.append("\n--- LAYOUT PLAN SNAPSHOT ---\n");
        if (attachedPlan == null) {
            sb.append("  (built after validatePlan — see [LAYOUT-PLAN] log line)\n");
        } else {
            appendPlanSnapshot(sb, attachedPlan);
        }

        // ── SECTION 1: ROADS ─────────────────────────────────────────────
        sb.append("\n--- ROADS ---\n");
        RoadGraph graph = layout.getRoadGraph();
        int edgeCount = 0;
        for (RoadGraph.Edge e : graph.allEdges()) {
            sb.append("  edge#").append(e.id())
              .append(" role=").append(e.role())
              .append(" tier=").append(e.primitive().tier())
              .append(" pts=").append(e.centerline().size());
            if (!e.centerline().isEmpty()) {
                sb.append(" from=").append(e.centerline().get(0))
                  .append(" to=").append(e.centerline().get(e.centerline().size() - 1));
            }
            sb.append('\n');
            edgeCount++;
        }
        if (edgeCount == 0) sb.append("  (none)\n");

        // ── SECTION 1.5: PLAZA (Phase 18) ────────────────────────────────
        // Plaza is the post-Phase-18 single owner of plaza geometry +
        // civic slots. Prints once per registered plaza (DUAL_PLAZA
        // produces two; ENCLAVE produces zero; everything else is one).
        sb.append("\n--- PLAZA ---\n");
        java.util.List<tterrag1112.life_in_the_village.Village.Planning.Plaza>
                plazas = layout.getPlazas();
        if (plazas.isEmpty()) {
            sb.append("  none (recipe does not register a Plaza)\n");
        } else {
            int pi = 0;
            for (var plz : plazas) {
                sb.append("  plaza#").append(pi++)
                  .append(" centre=").append(plz.centre())
                  .append(" plazaR=").append(plz.townSquareRadius())
                  .append(" civicRingR=").append(plz.civicRingRadius());
                if (plz.region() != null) {
                    sb.append(" shape=").append(plz.region().shape())
                      .append(" purpose=").append(plz.region().purpose())
                      .append(" verts=").append(plz.region().footprint().vertices().size());
                }
                int total = plz.civicSlots().size();
                sb.append(" civicSlots=").append(total).append('\n');
                int shown = 0;
                for (PlacementSlot slot : plz.civicSlotsView()) {
                    if (shown >= 6 && total > 8) {
                        sb.append("    ... ").append(total - shown)
                          .append(" more\n");
                        break;
                    }
                    sb.append("    pos=").append(slot.pos())
                      .append(" tags=").append(slot.tags())
                      .append(" fp=").append(slot.footprintBudgetW())
                      .append('x').append(slot.footprintBudgetL())
                      .append(" q=").append(slot.qualityScore())
                      .append('\n');
                    shown++;
                }
            }
        }

        // ── SECTION 2: SECTORS ───────────────────────────────────────────
        sb.append("\n--- SECTORS ---\n");
        List<Sector> sectors = layout.getDebugSectors();
        if (sectors == null || sectors.isEmpty()) {
            sb.append("  (none — flat-slot recipe or debug sectors not set)\n");
        } else {
            for (Sector s : sectors) {
                sb.append("  sector=").append(s.id())
                  .append(" role=").append(s.role())
                  .append(" zone=").append(s.zoneHint())
                  .append(" slots=").append(s.slots().size())
                  .append(" cap=").append(s.capacity())
                  .append(" edgeId=").append(s.parentEdgeId())
                  .append(" maxFp=").append(s.expectedMaxFootprint())
                  .append('\n');
            }
        }

        // ── SECTION 3: SLOTS BY SECTOR ────────────────────────────────────
        sb.append("\n--- SLOTS BY SECTOR ---\n");
        if (sectors == null || sectors.isEmpty()) {
            sb.append("  (no sectors)\n");
        } else {
            for (Sector s : sectors) {
                sb.append("  [").append(s.id()).append("]\n");
                if (s.slots().isEmpty()) {
                    sb.append("    (empty)\n");
                }
                int shown = 0;
                for (PlacementSlot slot : s.slots()) {
                    if (shown >= 8 && s.slots().size() > 10) {
                        sb.append("    ... ").append(s.slots().size() - shown)
                          .append(" more\n");
                        break;
                    }
                    sb.append("    pos=").append(slot.pos())
                      .append(" tags=").append(slot.tags())
                      .append(" fpW=").append(slot.footprintBudgetW())
                      .append(" fpL=").append(slot.footprintBudgetL())
                      .append(" drift=").append(slot.maxDriftBlocks())
                      .append(" road=").append(slot.feedingRoad() != null
                              ? slot.feedingRoad().size() + "pts" : "none")
                      .append('\n');
                    shown++;
                }
            }
        }

        // ── SECTION 4: COMMITTED BUILDINGS ────────────────────────────────
        sb.append("\n--- COMMITTED BUILDINGS ---\n");
        List<LayoutSlot> buildings = layout.buildings();
        if (buildings.isEmpty()) {
            sb.append("  (none)\n");
        } else {
            for (LayoutSlot slot : buildings) {
                String sectorId = pctx != null
                        ? pctx.committedSectorIds.get(slot.getPos()) : null;
                sb.append("  ").append(slot.getBuildingType())
                  .append(" at=").append(slot.getPos())
                  .append(" fp=").append(slot.getFootprintWidth())
                  .append("x").append(slot.getFootprintLength())
                  .append(" sector=").append(sectorId != null ? sectorId : "unknown")
                  .append(" feedRoad=").append(slot.getFeedingRoad() != null
                          ? slot.getFeedingRoad().size() + "pts" : "none")
                  .append('\n');
            }
        }

        // ── SECTION 5: VALIDATION SUMMARY ─────────────────────────────────
        sb.append("\n--- VALIDATION SUMMARY ---\n");
        int passed = 0, failed = 0;
        int ring2 = layout.getDensity().getRing2Radius();
        for (LayoutSlot slot : buildings) {
            int footprintHalf = Math.max(slot.getFootprintWidth(),
                    slot.getFootprintLength()) / 2;
            java.util.List<BlockPos> feedingRoad = slot.getFeedingRoad();
            boolean ok;
            int dist;
            int allowed;
            String mode;

            if (feedingRoad != null && !feedingRoad.isEmpty()) {
                dist = nearestPointChebyshev(slot.getPos(), feedingRoad);
                allowed = VALIDATOR_ROAD_HALF_WIDTH + footprintHalf + VALIDATOR_ROAD_SLACK;
                mode = "road";
                ok = dist <= allowed;
            } else {
                BlockPos vc = layout.getCenter();
                int dx = slot.getPos().getX() - vc.getX();
                int dz = slot.getPos().getZ() - vc.getZ();
                dist = (int) Math.round(Math.sqrt((double) dx * dx + (double) dz * dz));
                allowed = ring2 + footprintHalf + VALIDATOR_HULL_SLACK;
                mode = "hull";
                ok = dist <= allowed;
            }

            if (ok) {
                passed++;
            } else {
                failed++;
                String sectorId = pctx != null
                        ? pctx.committedSectorIds.get(slot.getPos()) : null;
                sb.append("  FAIL ").append(slot.getBuildingType())
                  .append(" at=").append(slot.getPos())
                  .append(" dist=").append(dist).append(">max=").append(allowed)
                  .append(" mode=").append(mode)
                  .append(" sector=").append(sectorId != null ? sectorId : "unknown")
                  .append('\n');
                // Nearest road across ALL edges for mismatch diagnosis.
                RoadGraph.Edge nearestEdge = layout.getRoadGraph().edgeNearest(slot.getPos());
                if (nearestEdge != null && !nearestEdge.centerline().isEmpty()) {
                    int allEdgeDist = nearestPointChebyshev(
                            slot.getPos(), nearestEdge.centerline());
                    sb.append("    nearest-any-edge: edge#").append(nearestEdge.id())
                      .append(" role=").append(nearestEdge.role())
                      .append(" chebDist=").append(allEdgeDist);
                    if (feedingRoad != null) {
                        int mismatch = dist - allEdgeDist;
                        if (mismatch > 4) {
                            sb.append(" FEEDING_ROAD_MISMATCH(+").append(mismatch).append(")");
                        }
                    }
                    sb.append('\n');
                }
            }
        }
        sb.append("  total=").append(buildings.size())
          .append(" passed=").append(passed)
          .append(" failed=").append(failed)
          .append('\n');
        sb.append("=== END PLAN DUMP ===\n");
        System.out.print(sb);
    }

    /** Chebyshev distance from {@code pos} to the nearest point in {@code points}. */
    private static int nearestPointChebyshev(BlockPos pos, java.util.List<BlockPos> points) {
        int best = Integer.MAX_VALUE;
        for (BlockPos p : points) {
            int dx = Math.abs(p.getX() - pos.getX());
            int dz = Math.abs(p.getZ() - pos.getZ());
            int cheb = Math.max(dx, dz);
            if (cheb < best) best = cheb;
        }
        return best;
    }

    // =========================================================================
    // Centre resolution
    // =========================================================================

    private static BlockPos resolveCentre(ServerLevel level, TerrainProfile terrain,
                                          RuleContext ctx) {
        BlockPos anchor = ctx.getAnchor("town_square");
        if (anchor != null) return solidSurface(level, anchor);
        return snapToFlat(level, terrain, terrain.origin());
    }

    private static BlockPos snapToFlat(ServerLevel level, TerrainProfile terrain,
                                       BlockPos origin) {
        return terrain.flatCandidates().stream()
                .filter(p -> p.distSqr(origin) <= 16 * 16)
                .min(Comparator.comparingDouble(p -> p.distSqr(origin)))
                .orElseGet(() -> solidSurface(level, origin));
    }

    private static BlockPos solidSurface(ServerLevel level, BlockPos pos) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                pos.getX(), pos.getZ());
        return new BlockPos(pos.getX(), y, pos.getZ());
    }

    // =========================================================================
    // Building list expansion (kept public — used by spawner)
    // =========================================================================

    public static List<VillageTypeData.StarterBuilding> expandBuildingList(
            List<VillageTypeData.StarterBuilding> starters, Random rng) {
        List<VillageTypeData.StarterBuilding> expanded = new ArrayList<>();
        for (VillageTypeData.StarterBuilding sb : starters) {
            int min = Math.max(1, sb.minCount());
            int max = Math.max(min, sb.maxCount());
            int count = min == max ? min : min + rng.nextInt(max - min + 1);
            for (int i = 0; i < count; i++) expanded.add(sb);
        }
        return expanded;
    }

    // =========================================================================
    // Post-passes — farm plots, decorations, Y clustering
    // =========================================================================

    private static void placeFarmPlots(VillageLayout layout, ServerLevel level,
                                       TerrainProfile terrain,
                                       LayoutDensityProfile density,
                                       VillageTypeData typeData, Random rng) {
        long farmhouseCount = typeData.getStarterBuildings().stream()
                .filter(sb -> sb.type().equals("FARMHOUSE"))
                .mapToInt(sb -> Math.max(1, sb.minCount()))
                .sum();
        if (farmhouseCount == 0) return;
        if (typeData.getShapeProfile().shapeType() == VillageTypeData.ShapeType.HILLTOP) return;

        int[] dir = flatDirVector(terrain.bestFlatDir());
        int perpX = dir[1], perpZ = -dir[0];
        int perimeterRadius = density.getRing2Radius() + 12;
        BlockPos centre = layout.getCenter();

        for (int i = 0; i < farmhouseCount; i++) {
            int sideOffset = (i - (int) farmhouseCount / 2) * (FARM_PLOT_RADIUS * 2 + 4);
            int tx = centre.getX()
                    + dir[0] * Math.max(density.getFarmOffset(), perimeterRadius)
                    + perpX * sideOffset;
            int tz = centre.getZ()
                    + dir[1] * Math.max(density.getFarmOffset(), perimeterRadius)
                    + perpZ * sideOffset;
            BlockPos ideal = terrain.bestFlatNear(
                    tx - terrain.origin().getX(),
                    tz - terrain.origin().getZ());
            BlockPos resolved = solidSurface(level, ideal);
            layout.tryAdd(new LayoutSlot(
                    LayoutSlot.SlotType.FARM_PLOT, resolved, FARM_PLOT_RADIUS));
        }
    }

    private static void placeDecorationClusters(VillageLayout layout,
                                                ServerLevel level,
                                                TerrainProfile terrain,
                                                LayoutDensityProfile density,
                                                Random rng) {
        int clusters = density.getDecorationClusters();
        int gapRadius = (density.getRing1Radius() + density.getRing2Radius()) / 2;
        BlockPos centre = layout.getCenter();
        for (int i = 0; i < clusters; i++) {
            double angle = (2 * Math.PI * i / clusters) + rng.nextDouble() * 0.8;
            int dx = (int)(Math.cos(angle) * gapRadius);
            int dz = (int)(Math.sin(angle) * gapRadius);
            BlockPos ideal = terrain.bestFlatNear(dx, dz);
            BlockPos resolved = solidSurface(level, ideal);
            layout.addForced(new LayoutSlot(
                    LayoutSlot.SlotType.DECORATION, resolved, DECORATION_RADIUS));
        }
    }

    private static void clusterBuildingYLevels(VillageLayout layout, ServerLevel level) {
        List<LayoutSlot> buildings = layout.buildings();
        for (LayoutSlot slot : buildings) {
            List<Integer> nearbyY = new ArrayList<>();
            for (LayoutSlot other : buildings) {
                if (other == slot) continue;
                int dx = Math.abs(slot.getPos().getX() - other.getPos().getX());
                int dz = Math.abs(slot.getPos().getZ() - other.getPos().getZ());
                if (Math.max(dx, dz) <= layout.getDensity().getRing2Radius()) {
                    nearbyY.add(other.getPos().getY());
                }
            }
            if (nearbyY.isEmpty()) continue;
            Collections.sort(nearbyY);
            int medianY = nearbyY.get(nearbyY.size() / 2);
            int diff = Math.abs(slot.getPos().getY() - medianY);
            if (diff > 0 && diff <= 6) {
                int actual = level.getHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        slot.getPos().getX(), slot.getPos().getZ());
                if (Math.abs(actual - medianY) <= 4) slot.snapY(medianY);
            }
        }
    }

    private static int[] flatDirVector(TerrainAnalyzer.FlatDirection dir) {
        return switch (dir) {
            case NORTH -> new int[]{0, -1};
            case SOUTH -> new int[]{0, 1};
            case EAST -> new int[]{1, 0};
            case WEST -> new int[]{-1, 0};
        };
    }
}