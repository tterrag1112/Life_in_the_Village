package tterrag1112.life_in_the_village.Village.Planning.V2.Harness;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Cultures.Culture;
import tterrag1112.life_in_the_village.Cultures.CultureRegistry;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Decoration.Variants.Style;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer1.SyntheticTerrainSource;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer1.V2FeatureMap;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.NetworkSpec;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.SiteAnalyzer;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.SiteContext;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.ViabilityTier;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.BuildingAvailability;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.BuildingSelector;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.DependencyResolver;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.InclinationProfile;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.PlacementResult;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.ReconciliationEngine;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.UnavailableBuilding;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer4.FixedFootprintProvider;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer4.PhasedPlanner;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer4.RoadNetwork;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer4.SpineSegment;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer5.TerrainAdapter;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Track E1 — executes a single battery {@link Battery.Run} headlessly.
 * Mirrors the planner stack inside {@code V2VillageSpawnerAdapter} —
 * Layer 1 scan, Layer 2 site analysis, Layer 3 selection +
 * reconciliation + topo-sort, Layer 4 phased placement, Layer 5
 * terrain adaptation. Skips every Layer 5 step that mutates the world
 * (VegetationClearer, PadBuilder, RoadPainter, MinimalSpawner) —
 * those are realiser concerns and not part of the harness contract.
 *
 * <p>The synthetic terrain source and footprint provider are
 * deterministic — the same {@code (shape, config)} produces the same
 * planner output every invocation. Any non-determinism in the metrics
 * would indicate planner-internal noise (a real signal worth
 * investigating).
 */
public final class RunExecutor {

    private static final int FEATURE_MAP_RADIUS = 100;
    /** The harness centres every site at the world origin. Heightmap
     *  shapes are coordinate-relative inside {@link
     *  SyntheticTerrainSource}, so {@code (0, 0)} is the natural
     *  centre. The {@link SiteContext#anchor} that emerges from
     *  Layer 2 lands within the scan area regardless. */
    private static final BlockPos ORIGIN = new BlockPos(0, SyntheticTerrainSource.BASE_Y, 0);

    private RunExecutor() {}

    /** Track E1 — debug fan-out plumbing. Extra context the harness
     *  surfaces alongside metrics so {@link HarnessDebugSink} can write
     *  a per-config HEADER without touching planner internals. None
     *  of these fields gate the battery or the baseline diff. */
    public record RunContext(
            String strategyId,
            int networkNodes,
            int networkEdges,
            int primaryBindings,
            int spineSegmentCount,
            double spineTotalLength) {}

    /** Pair carried by {@link #execute} so the debug sink gets the
     *  HEADER context alongside the metrics. */
    public record ExecResult(RunMetrics metrics, RunContext context) {}

    public static ExecResult execute(Battery.Run run) {
        long t0 = System.currentTimeMillis();
        SyntheticTerrainSource source = new SyntheticTerrainSource(run.shape(), ORIGIN);

        // Layer 1.
        V2FeatureMap fmap = V2FeatureMap.scan(source, ORIGIN, FEATURE_MAP_RADIUS);
        Culture culture = CultureRegistry.getOrDefault(CultureRegistry.DEFAULT_ID);

        // Layer 2 — pass forced tier/inclination overrides BEFORE
        // strategy selection, matching the live spawn path's pre-fix-up
        // ordering in V2VillageSpawnerAdapter.
        SiteContext siteCtx = SiteAnalyzer.analyze(fmap, culture, run.config().seed(),
                source, run.config().inclination(), run.config().tier());

        if (siteCtx.tier() == ViabilityTier.UNVIABLE) {
            return new ExecResult(
                    abortedMetrics(run, t0, /*requested*/ List.of(), null, null, null),
                    contextFrom(siteCtx, null));
        }

        // Layer 3.
        InclinationProfile profile = InclinationProfile.forInclination(siteCtx.inclination());
        BuildingAvailability availability = SyntheticAvailability.INSTANCE;
        BuildingSelector.SelectionResult sel =
                BuildingSelector.select(siteCtx, fmap, profile, availability);
        ReconciliationEngine.ReconciliationResult recon =
                ReconciliationEngine.reconcile(sel.selected(), siteCtx.tier(),
                        culture.id(), availability);
        List<BuildingType> sorted = DependencyResolver.topoSort(
                recon.finalSelection(), run.config().seed());
        List<UnavailableBuilding> unavailable = sel.unavailable();
        Set<BuildingType> tradeFulfilled = new HashSet<>();
        for (var tf : recon.tradeFulfilled()) tradeFulfilled.add(tf.requiringType());

        // Layer 4. Pass the synthetic availability through to
        // PhasedPlanner so the variant-id call site inside placement
        // sees the same universal-availability provider the selector
        // saw — pre-seam this was hardcoded to the production
        // StructureAvailabilityRegistry singleton and threw headless.
        FixedFootprintProvider footprints = new FixedFootprintProvider();
        PhasedPlanner.Result phased = PhasedPlanner.run(
                siteCtx, fmap, sorted, unavailable, footprints,
                availability, tradeFulfilled);
        PlacementResult placement = phased.placement();
        RoadNetwork roads = phased.network();

        // Layer 5 (planner-only): terrain adaptation. Realiser
        // components (vegetation, pads, road painting, spawning) are
        // skipped by the harness — the contract is planner-only.
        List<TerrainAdapter.AdaptationDecision> decisions = placement.placed().isEmpty()
                ? List.of()
                : TerrainAdapter.decide(placement.placed(), source);

        long elapsed = System.currentTimeMillis() - t0;
        RunMetrics metrics = MetricsComputer.compute(
                run, sorted, placement, roads, decisions, elapsed);
        return new ExecResult(metrics, contextFrom(siteCtx, roads));
    }

    private static RunContext contextFrom(SiteContext siteCtx, RoadNetwork roads) {
        String strategyId = "<none>";
        if (siteCtx != null && siteCtx.strategy() != null
                && siteCtx.strategy().strategy() != null) {
            strategyId = siteCtx.strategy().strategy().id();
        }
        int nodes = 0, edges = 0, bindings = 0;
        NetworkSpec spec = siteCtx == null ? null : siteCtx.network();
        if (spec != null) {
            nodes = spec.nodes().size();
            edges = spec.edges().size();
            bindings = spec.primaryBindings().size();
        }
        int spineCount = 0;
        double spineLen = 0.0;
        if (roads != null && roads.skeleton() != null) {
            List<SpineSegment> segs = roads.skeleton().primarySegments();
            spineCount = segs.size();
            for (SpineSegment s : segs) {
                double dx = s.end().getX() - s.start().getX();
                double dz = s.end().getZ() - s.start().getZ();
                spineLen += Math.sqrt(dx * dx + dz * dz);
            }
        }
        return new RunContext(strategyId, nodes, edges, bindings, spineCount, spineLen);
    }

    private static RunMetrics abortedMetrics(Battery.Run run, long t0,
                                             List<BuildingType> requested,
                                             PlacementResult placement,
                                             RoadNetwork roads,
                                             List<TerrainAdapter.AdaptationDecision> decisions) {
        long elapsed = System.currentTimeMillis() - t0;
        PlacementResult empty = placement != null ? placement
                : new PlacementResult(List.of(), List.of(), List.of(),
                        java.util.Map.of(), false);
        return MetricsComputer.compute(run,
                requested == null ? List.of() : requested,
                empty,
                roads != null ? roads : emptyRoadNetwork(),
                decisions != null ? decisions : List.of(),
                elapsed);
    }

    private static RoadNetwork emptyRoadNetwork() {
        // The harness never instantiates a RoadNetwork from scratch
        // because every UNVIABLE abort is reported through metrics
        // computed from an empty placed list. The aborted-run path in
        // MetricsComputer.compute short-circuits before touching
        // RoadNetwork members.
        return null;
    }

    /** Synthetic availability — every type is available with its
     *  default variant id. Mirrors what production sees after the
     *  reload listener has indexed authored NBT, just with universal
     *  availability rather than reading from disk. */
    private static final class SyntheticAvailability implements BuildingAvailability {
        static final SyntheticAvailability INSTANCE = new SyntheticAvailability();
        @Override
        public Set<String> availableVariants(String culture, Style style,
                                             BuildingType type, int level) {
            return Set.of(type.name().toLowerCase());
        }
    }
}
