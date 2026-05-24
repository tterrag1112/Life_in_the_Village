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
            boolean verbose,
            /** Naming prefix for the {@code FarmPlot} records this
             *  planner mints (e.g. the village name for a real spawn,
             *  {@code "test_complex"} for the debug harness). Null is
             *  treated as {@code "complex"} — fallback used by
             *  backward-compat ctors. */
            String namePrefix) {

        /** Backward-compat ctor for callers that don't yet pass an
         *  exclusion list, verbosity flag, or naming prefix. */
        public Input(BlockPos farmhouseOrigin, Direction complexExtendsToward,
                     int footprintHalfX, int footprintHalfZ,
                     UUID villageId, UUID farmhouseId, String culture,
                     BuildingType buildingType, V2FeatureMap fmap,
                     FloodFillRegionClaim.BiomeBlockedPredicate biomeCheck,
                     long seed) {
            this(farmhouseOrigin, complexExtendsToward,
                    footprintHalfX, footprintHalfZ,
                    villageId, farmhouseId, culture, buildingType,
                    fmap, biomeCheck, seed, java.util.List.of(), false, null);
        }

        /** Backward-compat ctor for callers passing exclusion but no
         *  verbosity / naming prefix. */
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
                    fmap, biomeCheck, seed, excludedPolygons, false, null);
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
        // The approach apron joins caller-supplied exclusions so the
        // flood-fill carves a notch around the path's exit point in
        // front of the farmhouse. Without this the spine origin
        // would otherwise be admitted and plots could be placed
        // flush against the building wall.
        Polygon apronPoly = buildApronPolygon(in);
        java.util.List<Polygon> floodExclusions =
                new ArrayList<>(in.excludedPolygons() == null
                        ? java.util.List.of() : in.excludedPolygons());
        floodExclusions.add(apronPoly);
        FloodFillRegionClaim.Result fill = FloodFillRegionClaim.run(
                new FloodFillRegionClaim.Input(
                        seed,
                        maxRadius,
                        spec.blockBudget(),
                        spec.floodFillSlopeLimit(),
                        ArableScoring.DEFAULT_THRESHOLD,
                        in.fmap(),
                        in.biomeCheck(),
                        floodExclusions,
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

        // ── 4. Footprint+apron polygon (for BSP exclusion) ─────────────
        // BSP takes a single exclusion polygon. Union the footprint
        // and the apron into one axis-aligned bounding rectangle so
        // plot subdivision skips both regions; the small over-include
        // along the building's side strips is harmless (those cells
        // are BUILT-category anyway, so they'd never be admitted).
        Polygon footprintPoly = unionFootprintWithApron(
                buildFootprintPolygon(in), apronPoly);

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
        // E.bug.3 — origin Y comes from the V2FeatureMap's per-cell
        // elevation at the plot centroid (+ 1 to land at the "surface
        // where you stand", matching the legacy FarmPlotPlacer's
        // medianFootprintY convention). The pre-fix code used
        // pp.centroid().getY() which inherits from the polygon's
        // first-vertex Y == farmhouseOrigin.Y == pad Y == one BELOW
        // the building's floor; that put plot.origin.Y one below
        // village.getBounds().minY, so /farmplot list filtered every
        // complex plot out.
        UUID complexId = new UUID(in.seed() ^ 0xC0_C0_C0L,
                in.farmhouseId().getLeastSignificantBits());
        String namePrefix = in.namePrefix() != null
                ? in.namePrefix() : "complex";
        List<FarmPlot> newPlots = new ArrayList<>(bsp.plots().size());
        for (PlotPlan pp : bsp.plots()) {
            UUID plotId = UUID.randomUUID();
            FarmPlot.CropType crop = pp.crop() == null
                    ? FarmPlot.CropType.WHEAT : pp.crop();
            FarmPlot.PlotSubtype subtype = crop == FarmPlot.CropType.PASTURE
                    ? FarmPlot.PlotSubtype.ANIMAL_PEN
                    : FarmPlot.PlotSubtype.CROP_FIELD;
            String name = namePrefix + "_" + (pp.plotIndex() + 1)
                    + "_" + subtype.name().toLowerCase();
            BlockPos plotOrigin = plotOriginAtSurface(
                    pp.centroid(), in.fmap());
            FarmPlot plot = new FarmPlot(plotId, name, plotOrigin,
                    radiusFromPolygon(pp.polygon()),
                    crop, subtype);
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
                newPlots, apronPoly,
                new java.util.Random(in.seed() ^ 0xCAFE_BABE_L),
                in.verbose());
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

    /** Prompt B follow-up — "approach apron" polygon. A small strip
     *  in the {@code complexExtendsToward} direction just past the
     *  farmhouse footprint, reserved as open ground so plot
     *  subdivision + flood-fill leave a clear corridor between the
     *  building's complex-facing edge and the path spine.
     *
     *  <p>Geometry: full farmhouse width on the perpendicular axis,
     *  {@link #APRON_DEPTH} blocks deep along {@code extendsToward},
     *  abutting the farmhouse footprint with no gap. */
    private static final int APRON_DEPTH = 4;

    private static Polygon buildApronPolygon(Input in) {
        BlockPos o = in.farmhouseOrigin();
        int hx = in.footprintHalfX();
        int hz = in.footprintHalfZ();
        int y = o.getY();
        var d = in.complexExtendsToward();
        // Apron starts at the footprint's far edge (in extendsToward
        // direction) and extends APRON_DEPTH further. The
        // perpendicular extent matches footprint half-dim plus 1
        // block of margin so the corner cells of the path corridor
        // also stay clear.
        int margin = 1;
        if (d.getStepX() != 0) {
            int sx = d.getStepX();
            int nearX = o.getX() + sx * hx;
            int farX  = o.getX() + sx * (hx + APRON_DEPTH);
            int minX = Math.min(nearX, farX);
            int maxX = Math.max(nearX, farX);
            int minZ = o.getZ() - hz - margin;
            int maxZ = o.getZ() + hz + margin;
            return new Polygon(List.of(
                    new BlockPos(minX, y, minZ),
                    new BlockPos(maxX, y, minZ),
                    new BlockPos(maxX, y, maxZ),
                    new BlockPos(minX, y, maxZ)));
        } else {
            int sz = d.getStepZ();
            int nearZ = o.getZ() + sz * hz;
            int farZ  = o.getZ() + sz * (hz + APRON_DEPTH);
            int minZ = Math.min(nearZ, farZ);
            int maxZ = Math.max(nearZ, farZ);
            int minX = o.getX() - hx - margin;
            int maxX = o.getX() + hx + margin;
            return new Polygon(List.of(
                    new BlockPos(minX, y, minZ),
                    new BlockPos(maxX, y, minZ),
                    new BlockPos(maxX, y, maxZ),
                    new BlockPos(minX, y, maxZ)));
        }
    }

    /** Combine two axis-aligned polygons into a single bounding
     *  rectangle covering both. Used to feed footprint+apron into
     *  BSP's single buildingBounds slot. */
    private static Polygon unionFootprintWithApron(Polygon footprint, Polygon apron) {
        var fb = Polygon.boundingBox(footprint);
        var ab = Polygon.boundingBox(apron);
        int minX = Math.min(fb.minX(), ab.minX());
        int minZ = Math.min(fb.minZ(), ab.minZ());
        int maxX = Math.max(fb.maxX(), ab.maxX());
        int maxZ = Math.max(fb.maxZ(), ab.maxZ());
        int y = footprint.vertices().get(0).getY();
        return new Polygon(List.of(
                new BlockPos(minX, y, minZ),
                new BlockPos(maxX, y, minZ),
                new BlockPos(maxX, y, maxZ),
                new BlockPos(minX, y, maxZ)));
    }

    /** Circumscribed-circle radius: max XZ distance from the
     *  polygon centroid to any vertex. Stored on FarmPlot so the
     *  legacy circle-based {@code FarmPlot.contains} fallback
     *  encloses the entire polygon. NPCs / commands that hit the
     *  polygon-aware path use {@link FarmPlot#getPolygon} directly;
     *  radius is only the fallback. */
    private static int radiusFromPolygon(Polygon p) {
        BlockPos c = Polygon.centroid(p);
        double maxDistSq = 0;
        for (BlockPos v : p.vertices()) {
            double dx = v.getX() - c.getX();
            double dz = v.getZ() - c.getZ();
            double d2 = dx * dx + dz * dz;
            if (d2 > maxDistSq) maxDistSq = d2;
        }
        return Math.max(4, (int) Math.ceil(Math.sqrt(maxDistSq)));
    }

    /** Resolve the plot's persisted {@code origin.Y} from the
     *  feature-map's cell elevation at the centroid XZ.
     *
     *  <p>Returns {@code elevationY + 1} — the "surface where you
     *  stand" Y, matching the legacy
     *  {@code FarmPlotPlacer.medianFootprintY} convention used for
     *  the legacy {@code flatCentre} origin. This Y lands inside
     *  the village's building-AABB (whose minY is the building
     *  floor block, also at {@code groundY + 1}), so
     *  {@code /farmplot list} returns complex plots.
     *
     *  <p>If the centroid happens to land outside the scanned grid
     *  (rare, large complex on a small map), falls back to the
     *  input centroid's Y as a sentinel — better than NaN. */
    private static BlockPos plotOriginAtSurface(BlockPos centroid, V2FeatureMap fmap) {
        if (!fmap.inBounds(centroid.getX(), centroid.getZ())) {
            return centroid;
        }
        var cell = fmap.cellAt(centroid.getX(), centroid.getZ());
        int y = cell.elevationY() + 1;
        return new BlockPos(centroid.getX(), y, centroid.getZ());
    }

    /** Pick a point near the spine, offset perpendicular, inside
     *  the region and outside every plot polygon. Two phases:
     *  <ol>
     *    <li>{@link #TOOL_SHED_PROBE_ATTEMPTS} spine-perpendicular
     *        probes with randomised offset 3-6 along varied along-
     *        spine positions, alternating sides.</li>
     *    <li>Apron fallback — if no spine probe lands, the apron
     *        polygon's centroid is tried; the apron is always
     *        cleared open ground so the shed lands here in the
     *        worst case (close to the farmhouse but at least
     *        rendered).</li>
     *  </ol>
     *  Returns null only if both phases fail; an INFO log line
     *  fires on final exhaustion. */
    private static final int TOOL_SHED_PROBE_ATTEMPTS = 16;
    private static final int TOOL_SHED_OFFSET_MIN = 3;
    private static final int TOOL_SHED_OFFSET_MAX = 6;

    private static BlockPos pickToolShedPosition(Polygon region,
                                                  PathTopologyPlanner.Result paths,
                                                  List<FarmPlot> plots,
                                                  Polygon apron,
                                                  java.util.Random rng,
                                                  boolean verbose) {
        // Phase 1: spine-perpendicular probes.
        PathTopologyPlanner.Segment anchorSeg = null;
        for (PathTopologyPlanner.Segment s : paths.segments()) {
            if (s.isSpine()) { anchorSeg = s; }
        }
        if (anchorSeg != null) {
            BlockPos a = anchorSeg.start();
            BlockPos b = anchorSeg.end();
            double dx = b.getX() - a.getX();
            double dz = b.getZ() - a.getZ();
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len >= 1e-9) {
                double pxu =  dz / len;
                double pzu = -dx / len;
                int y = a.getY();
                for (int attempt = 0; attempt < TOOL_SHED_PROBE_ATTEMPTS; attempt++) {
                    int side = (attempt % 2 == 0) ? +1 : -1;
                    // Spread along-spine positions across the
                    // first 80% of the spine; the random offset
                    // makes each attempt distinct even at the
                    // same t.
                    double t = 0.1 + 0.05 * (attempt / 2);
                    int offset = TOOL_SHED_OFFSET_MIN
                            + rng.nextInt(TOOL_SHED_OFFSET_MAX
                                    - TOOL_SHED_OFFSET_MIN + 1);
                    double cx = a.getX() + dx * t + side * pxu * offset;
                    double cz = a.getZ() + dz * t + side * pzu * offset;
                    BlockPos candidate = new BlockPos((int) cx, y, (int) cz);
                    if (!Polygon.contains(region, candidate)) continue;
                    if (insideAnyPlot(plots, candidate)) continue;
                    return candidate;
                }
            }
        }
        // Phase 2: apron fallback.
        if (apron != null && !apron.vertices().isEmpty()) {
            BlockPos apronCentre = Polygon.centroid(apron);
            if (apronCentre != null) {
                if (verbose) {
                    org.slf4j.LoggerFactory.getLogger(FarmComplexPlanner.class)
                            .info("tool shed: spine probes exhausted; using "
                                    + "apron centroid @ ({}, {}, {})",
                                    apronCentre.getX(), apronCentre.getY(),
                                    apronCentre.getZ());
                }
                return apronCentre;
            }
        }
        if (verbose) {
            org.slf4j.LoggerFactory.getLogger(FarmComplexPlanner.class)
                    .info("tool shed probe exhausted — no viable position "
                            + "in spine corridors or apron fallback");
        }
        return null;
    }

    private static boolean insideAnyPlot(List<FarmPlot> plots, BlockPos pos) {
        for (FarmPlot plot : plots) {
            if (plot.getPolygon() != null
                    && Polygon.contains(plot.getPolygon(), pos)) {
                return true;
            }
        }
        return false;
    }
}
