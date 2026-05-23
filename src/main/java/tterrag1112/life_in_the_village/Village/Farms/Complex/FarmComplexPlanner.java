package tterrag1112.life_in_the_village.Village.Farms.Complex;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Utilities.Geometry.Polygon;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Buildings.Complex.BorderStyleId;
import tterrag1112.life_in_the_village.Village.Buildings.Complex.BuildingComplexRegistry;
import tterrag1112.life_in_the_village.Village.Buildings.Complex.BuildingComplexSpec;
import tterrag1112.life_in_the_village.Village.Buildings.FarmPlot;
import tterrag1112.life_in_the_village.Village.Farms.ArableScoring;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer1.V2FeatureMap;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Detour A — orchestrator composing the Stage-2 algorithms into a
 * single {@link FarmComplex} plan + the {@link FarmPlot} records
 * that go with it.
 *
 * <p>Pure composition: no field state, no logging side effects in
 * the {@link #plan} method. The {@link #planAndPersist} convenience
 * also writes to a {@link VillageSavedData} when the plan succeeds
 * — Stage 5 (spawn adapter) and Stage 6 (test command) are the
 * intended callers.
 *
 * <p>Defensive boundaries:
 * <ul>
 *   <li>Spec lookup miss → {@link Status#NO_SPEC_REGISTERED}.</li>
 *   <li>Seed cell inadmissible / out of bounds / biome-blocked →
 *       {@link Status#SEED_NOT_ADMISSIBLE} or
 *       {@link Status#INSUFFICIENT_AREA} as the inner
 *       {@code FloodFillRegionClaim} reports.</li>
 *   <li>Polygon construction null or {@code < 3} vertices →
 *       {@link Status#DEGENERATE_REGION}. (Polygon's canonical
 *       constructor would throw, so we test before constructing
 *       {@link FarmComplex}.)</li>
 *   <li>BSP yields no plots → {@link Status#NO_VIABLE_PLOTS}.</li>
 *   <li>Tool shed positioning fails (no point inside region
 *       outside all plots) → success with
 *       {@code toolShedPosition == null}; not a planner failure.</li>
 * </ul>
 *
 * <p>No callers in this commit. Stage 5 wires the spawn adapter
 * to call {@link #planAndPersist}; Stage 6's {@code /litv farms}
 * test command wraps it with a synthetic-village setup.
 */
public final class FarmComplexPlanner {

    private FarmComplexPlanner() {}

    /** Inputs to the planner. See class doc for boundary behaviour. */
    public record Input(
            BlockPos farmhouseOrigin,
            /** Direction from the farmhouse origin that the complex
             *  flood-fill extends. The seed is placed just past the
             *  farmhouse footprint in this direction; the path
             *  spine enters the complex on the same side.
             *
             *  <p>For real spawns the typical caller computes
             *  {@code building.facing().getOpposite()} — a farmhouse's
             *  {@code facing()} points toward the road (front of
             *  building); the complex extends out the back. The
             *  Stage 6 test command may pass any direction (the
             *  default is south).
             *
             *  <p>The planner itself is agnostic to front/back
             *  conventions; this field names the direction the
             *  complex grows. */
            Direction complexExtendsToward,
            /** Half-extent of the farmhouse footprint along world X
             *  (i.e. the building's footprint is
             *  {@code [origin.x - halfX, origin.x + halfX]}). */
            int footprintHalfX,
            int footprintHalfZ,
            UUID villageId,
            UUID farmhouseId,
            String culture,
            BuildingType buildingType,
            V2FeatureMap fmap,
            /** Nullable — null means "allow everything", used by the
             *  test command's synthetic-village path. */
            FloodFillRegionClaim.BiomeBlockedPredicate biomeCheck,
            long seed,
            /** Detour A — Prompt B Stage A. Polygons whose interior
             *  must be excluded from the flood-fill claim — parks,
             *  reserved gardens, future reservations of this kind.
             *  Null or empty ⇒ no exclusion. */
            java.util.List<tterrag1112.life_in_the_village.Utilities.Geometry.Polygon>
                    excludedPolygons,
            /** When true, the flood-fill emits one-line diagnostic
             *  output on the seed-admissibility failure path naming
             *  the category, slope, water/forest distances, computed
             *  arable score, and the threshold + which gate rejected.
             *  Off in production spawn (no log spam on every spawn);
             *  on for the {@code /liv farms test_spawn} harness. */
            boolean verbose) {

        /** Backward-compat ctor for callers that don't yet pass an
         *  exclusion list or verbosity flag. */
        public Input(BlockPos farmhouseOrigin, Direction complexExtendsToward,
                     int footprintHalfX, int footprintHalfZ,
                     UUID villageId, UUID farmhouseId, String culture,
                     BuildingType buildingType, V2FeatureMap fmap,
                     FloodFillRegionClaim.BiomeBlockedPredicate biomeCheck,
                     long seed) {
            this(farmhouseOrigin, complexExtendsToward,
                    footprintHalfX, footprintHalfZ,
                    villageId, farmhouseId, culture, buildingType,
                    fmap, biomeCheck, seed, java.util.List.of(), false);
        }

        /** Backward-compat ctor for callers passing exclusion but no
         *  verbosity. The V2 spawn adapter uses this overload —
         *  production spawn stays quiet. */
        public Input(BlockPos farmhouseOrigin, Direction complexExtendsToward,
                     int footprintHalfX, int footprintHalfZ,
                     UUID villageId, UUID farmhouseId, String culture,
                     BuildingType buildingType, V2FeatureMap fmap,
                     FloodFillRegionClaim.BiomeBlockedPredicate biomeCheck,
                     long seed,
                     java.util.List<tterrag1112.life_in_the_village.Utilities.Geometry.Polygon>
                             excludedPolygons) {
            this(farmhouseOrigin, complexExtendsToward,
                    footprintHalfX, footprintHalfZ,
                    villageId, farmhouseId, culture, buildingType,
                    fmap, biomeCheck, seed, excludedPolygons, false);
        }
    }

    public enum Status {
        SUCCESS,
        NO_SPEC_REGISTERED,
        SEED_NOT_ADMISSIBLE,
        INSUFFICIENT_AREA,
        BIOME_BLOCKED_AT_SEED,
        DEGENERATE_REGION,
        NO_VIABLE_PLOTS
    }

    /** Planner output. {@link #success()} is the only check most
     *  callers need; the {@link #detail} is for log lines and the
     *  test command's user-facing error messages. */
    public record PlanResult(
            Status status,
            String detail,
            FarmComplex complex,
            List<FarmPlot> newPlots) {

        public boolean success() { return status == Status.SUCCESS; }
        public Optional<FarmComplex> asOptional() { return Optional.ofNullable(complex); }

        public static PlanResult fail(Status s, String detail) {
            return new PlanResult(s, detail, null, List.of());
        }
    }

    // =========================================================================
    // Plan
    // =========================================================================

    public static PlanResult plan(Input in) {
        // ── 1. Spec lookup ──────────────────────────────────────────────
        Optional<BuildingComplexSpec> maybeSpec =
                BuildingComplexRegistry.get(in.culture(), in.buildingType());
        if (maybeSpec.isEmpty()) {
            return PlanResult.fail(Status.NO_SPEC_REGISTERED,
                    "No BuildingComplexSpec registered for (culture="
                            + in.culture() + ", type=" + in.buildingType() + ").");
        }
        BuildingComplexSpec spec = maybeSpec.get();

        // ── 2. Seed position ────────────────────────────────────────────
        // Just past the road-facing edge of the farmhouse, outside the
        // footprint. The flood-fill expands outward; the farmhouse's
        // own cells (BUILT category once placed) won't be admitted.
        int longestHalf = Math.max(in.footprintHalfX(), in.footprintHalfZ());
        int seedOffset = longestHalf + 2;
        BlockPos seed = in.farmhouseOrigin().offset(
                in.complexExtendsToward().getStepX() * seedOffset,
                0,
                in.complexExtendsToward().getStepZ() * seedOffset);
        int maxRadius = Math.round(longestHalf * 2 * spec.radiusMultiplier());

        // ── 3. Flood-fill ───────────────────────────────────────────────
        FloodFillRegionClaim.Result fill = FloodFillRegionClaim.run(
                new FloodFillRegionClaim.Input(
                        seed,
                        maxRadius,
                        spec.blockBudget(),
                        spec.floodFillSlopeLimit(),
                        ArableScoring.DEFAULT_THRESHOLD,
                        in.fmap(),
                        in.biomeCheck(),
                        in.excludedPolygons(),
                        in.verbose()));
        if (fill.failure() != null) {
            return switch (fill.failure()) {
                case INSUFFICIENT_AREA -> PlanResult.fail(
                        Status.INSUFFICIENT_AREA, fill.detail());
                case SEED_NOT_ADMISSIBLE, SEED_OUT_OF_BOUNDS -> PlanResult.fail(
                        Status.SEED_NOT_ADMISSIBLE, fill.detail());
                case BIOME_BLOCKED_AT_SEED -> PlanResult.fail(
                        Status.BIOME_BLOCKED_AT_SEED, fill.detail());
            };
        }
        if (fill.region() == null || fill.region().vertices().size() < 3) {
            return PlanResult.fail(Status.DEGENERATE_REGION,
                    "Flood-fill region is null or has fewer than 3 vertices.");
        }

        // ── 4. Footprint polygon (for BSP exclusion) ────────────────────
        Polygon footprintPoly = buildFootprintPolygon(in);

        // ── 5. BSP subdivision ──────────────────────────────────────────
        int targetPlotCount = spec.targetPlotCount();
        if (fill.tight()) {
            // Tight claims (< 50% of budget filled) reduce plot count so
            // we don't slice an already-marginal region into uselessly
            // small plots.
            targetPlotCount = Math.max(2, targetPlotCount / 2);
        }
        if (in.verbose()) {
            org.slf4j.LoggerFactory.getLogger(FarmComplexPlanner.class).info(
                    "FarmComplexPlanner: spec.targetPlotCount={} fill.tight={} "
                            + "fill.cellsClaimed={} → BSP targetPlotCount={}",
                    spec.targetPlotCount(), fill.tight(),
                    fill.cellsClaimed(), targetPlotCount);
        }
        BspSubdivider.Result bsp = BspSubdivider.run(new BspSubdivider.Input(
                fill.region(),
                footprintPoly,
                spec.minPlotSize(),
                targetPlotCount,
                spec.plotTypeMix(),
                in.fmap().cellSize(),
                in.seed(),
                in.verbose()));
        if (bsp.plots().isEmpty()) {
            return PlanResult.fail(Status.NO_VIABLE_PLOTS,
                    "BSP produced zero plots above minPlotSize=" + spec.minPlotSize()
                            + " (dropped " + bsp.droppedTooSmall() + ").");
        }

        // ── 6. Mint FarmPlot records, paired with BSP plotIndex ────────
        UUID complexId = new UUID(in.seed() ^ 0xC0_C0_C0L,
                in.farmhouseId().getLeastSignificantBits());
        List<FarmPlot> newPlots = new ArrayList<>(bsp.plots().size());
        for (PlotPlan pp : bsp.plots()) {
            UUID plotId = UUID.randomUUID();
            String name = "Plot " + (pp.plotIndex() + 1);
            FarmPlot.CropType crop = pp.crop() == null
                    ? FarmPlot.CropType.WHEAT : pp.crop();
            FarmPlot plot = new FarmPlot(plotId, name, pp.centroid(),
                    /* legacy radius — bbox half-dim heuristic */
                    radiusFromPolygon(pp.polygon()),
                    crop,
                    crop == FarmPlot.CropType.PASTURE
                            ? FarmPlot.PlotSubtype.ANIMAL_PEN
                            : FarmPlot.PlotSubtype.CROP_FIELD);
            plot.setPolygon(pp.polygon());
            plot.setFarmhouseId(in.farmhouseId());
            plot.setComplexId(complexId);
            newPlots.add(plot);
        }

        // ── 7. Path topology ────────────────────────────────────────────
        PathTopologyPlanner.Result paths = PathTopologyPlanner.run(
                new PathTopologyPlanner.Input(
                        fill.region(),
                        seed,
                        bsp.plots(),
                        spec.pathStyle()));

        List<PathSegment> persistedSegments = new ArrayList<>(paths.segments().size());
        for (PathTopologyPlanner.Segment s : paths.segments()) {
            persistedSegments.add(new PathSegment(
                    s.start(), s.end(), s.width(), s.isSpine()));
        }
        List<PlotEntry> persistedEntries = new ArrayList<>(paths.plotEntries().size());
        for (PathTopologyPlanner.PlotEntry e : paths.plotEntries()) {
            UUID plotId = newPlots.get(e.plotIndex()).getId();
            persistedEntries.add(new PlotEntry(plotId, e.entry(), e.spineAttach()));
        }

        // ── 8. Border style assignment ──────────────────────────────────
        BorderStyleAssigner.Result borders = BorderStyleAssigner.run(
                new BorderStyleAssigner.Input(
                        bsp.plots(),
                        spec.borderStylePool(),
                        in.seed() + 1));
        List<PlotBorders> persistedBorders = new ArrayList<>(borders.perPlot().size());
        for (BorderStyleAssigner.PlotBorders pb : borders.perPlot()) {
            UUID plotId = newPlots.get(pb.plotIndex()).getId();
            List<PlotBorders.EdgeStyle> edgeOverrides = new ArrayList<>();
            for (Map.Entry<Integer, BorderStyleId> e : pb.edgeStyles().entrySet()) {
                if (e.getValue() != pb.primary()) {
                    edgeOverrides.add(new PlotBorders.EdgeStyle(
                            e.getKey(), e.getValue()));
                }
            }
            persistedBorders.add(new PlotBorders(plotId, pb.primary(), edgeOverrides));
        }

        // ── 9. Tool shed positioning ────────────────────────────────────
        BlockPos toolShed = pickToolShedPosition(fill.region(), paths,
                newPlots);
        // null is allowed — caller renders without a shed if positioning failed.

        // ── 10. Assemble ────────────────────────────────────────────────
        List<UUID> plotIds = new ArrayList<>(newPlots.size());
        for (FarmPlot p : newPlots) plotIds.add(p.getId());
        FarmComplex complex = new FarmComplex(
                complexId,
                in.villageId(),
                in.farmhouseId(),
                fill.region(),
                plotIds,
                persistedSegments,
                persistedEntries,
                toolShed,
                persistedBorders,
                List.of() /* gatePositions — populated by renderer at spawn time */);

        return new PlanResult(Status.SUCCESS, null, complex, newPlots);
    }

    /** Run {@link #plan} and, on success, persist both the complex
     *  and the new plot records into {@code data}. Returns the
     *  {@link PlanResult} so callers can act on the status. */
    public static PlanResult planAndPersist(Input in, VillageSavedData data) {
        PlanResult r = plan(in);
        if (r.success() && data != null) {
            data.addFarmComplex(r.complex());
            for (FarmPlot p : r.newPlots()) data.addFarmPlot(p);
        }
        return r;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static Polygon buildFootprintPolygon(Input in) {
        BlockPos o = in.farmhouseOrigin();
        int hx = in.footprintHalfX();
        int hz = in.footprintHalfZ();
        int y = o.getY();
        List<BlockPos> verts = List.of(
                new BlockPos(o.getX() - hx, y, o.getZ() - hz),
                new BlockPos(o.getX() + hx, y, o.getZ() - hz),
                new BlockPos(o.getX() + hx, y, o.getZ() + hz),
                new BlockPos(o.getX() - hx, y, o.getZ() + hz));
        return new Polygon(verts);
    }

    /** Conservative bbox half-dim → radius mapping. Stored solely
     *  to keep the {@link FarmPlot} legacy "circle" containment
     *  path working when the polygon path isn't taken. */
    private static int radiusFromPolygon(Polygon p) {
        Polygon.AABB bb = Polygon.boundingBox(p);
        int hw = (bb.maxX() - bb.minX()) / 2;
        int hh = (bb.maxZ() - bb.minZ()) / 2;
        return Math.max(4, Math.min(hw, hh));
    }

    /** Pick a point near the spine's farmhouse-side end, offset
     *  perpendicular to the spine direction, inside the region and
     *  outside every plot polygon. Falls back to null if no such
     *  point passes within {@link #TOOL_SHED_PROBE_ATTEMPTS}. */
    private static final int TOOL_SHED_PROBE_ATTEMPTS = 8;
    private static final int TOOL_SHED_OFFSET = 4;

    private static BlockPos pickToolShedPosition(Polygon region,
                                                  PathTopologyPlanner.Result paths,
                                                  List<FarmPlot> plots) {
        if (paths.segments().isEmpty()) return null;
        // First spine segment runs from the stub outside the region
        // to the road-facing origin; use the second spine segment
        // (origin → farthest) as the anchor.
        PathTopologyPlanner.Segment anchorSeg = null;
        for (PathTopologyPlanner.Segment s : paths.segments()) {
            if (s.isSpine()) { anchorSeg = s; }
        }
        if (anchorSeg == null) return null;

        BlockPos a = anchorSeg.start();
        BlockPos b = anchorSeg.end();
        double dx = b.getX() - a.getX();
        double dz = b.getZ() - a.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1e-9) return null;
        // Perpendicular unit vector (rotate 90° CW).
        double pxu =  dz / len;
        double pzu = -dx / len;
        int y = a.getY();

        for (int attempt = 0; attempt < TOOL_SHED_PROBE_ATTEMPTS; attempt++) {
            // Alternate sides; step further along the spine each iteration.
            int side = (attempt % 2 == 0) ? +1 : -1;
            double t = 0.1 + 0.1 * (attempt / 2.0);
            double cx = a.getX() + dx * t + side * pxu * TOOL_SHED_OFFSET;
            double cz = a.getZ() + dz * t + side * pzu * TOOL_SHED_OFFSET;
            BlockPos candidate = new BlockPos((int) cx, y, (int) cz);
            if (!Polygon.contains(region, candidate)) continue;
            boolean insidePlot = false;
            for (FarmPlot plot : plots) {
                if (plot.getPolygon() != null
                        && Polygon.contains(plot.getPolygon(), candidate)) {
                    insidePlot = true;
                    break;
                }
            }
            if (insidePlot) continue;
            return candidate;
        }
        return null;
    }
}
