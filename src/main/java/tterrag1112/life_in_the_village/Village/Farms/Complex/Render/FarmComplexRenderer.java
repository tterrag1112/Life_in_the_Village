package tterrag1112.life_in_the_village.Village.Farms.Complex.Render;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import tterrag1112.life_in_the_village.Utilities.Geometry.Polygon;
import tterrag1112.life_in_the_village.Village.Buildings.Complex.BorderStyleId;
import tterrag1112.life_in_the_village.Village.Buildings.FarmPlot;
import tterrag1112.life_in_the_village.Village.Farms.Complex.FarmComplex;
import tterrag1112.life_in_the_village.Village.Farms.Complex.PathSegment;
import tterrag1112.life_in_the_village.Village.Farms.Complex.PlotBorders;
import tterrag1112.life_in_the_village.Village.Farms.Complex.PlotEntry;
import tterrag1112.life_in_the_village.Village.Farms.Complex.Render.Borders.BorderGenerator;
import tterrag1112.life_in_the_village.Village.Farms.Complex.Render.Borders.BorderGeneratorRegistry;
import tterrag1112.life_in_the_village.Village.Farms.Complex.Render.Borders.HedgeBorder;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Detour A Prompt B Stage G — composes the per-piece renderers
 * for one {@link FarmComplex} into a single render pass.
 *
 * <p>Order:
 * <ol>
 *   <li>Borders (with edge deduplication — shared edges between
 *       adjacent plots only paint once).</li>
 *   <li>Paths.</li>
 *   <li>Gates (punch fence-gates where branches cross borders).</li>
 *   <li>Plot interiors (path-wins rule keeps farmland from
 *       overrunning dirt-path cells).</li>
 *   <li>Tool shed (door faces the spine origin direction).</li>
 *   <li>Props scatter (region perimeter, outside plots).</li>
 * </ol>
 *
 * <p>Deterministic per complex id: same complex re-renders identically.
 *
 * <p>Pure block-placement side effects on {@code level}. No
 * SavedData writes; the orchestrator reads the persisted
 * complex + plots and turns them into blocks.
 */
public final class FarmComplexRenderer {

    private FarmComplexRenderer() {}

    /** Render result. Carries counts useful to the test command's
     *  dump (and to any future production telemetry). */
    public record Stats(int gatesPlaced, int gatesSkipped) {}

    public static Stats render(FarmComplex complex, List<FarmPlot> plots,
                                String culture, ServerLevel level) {
        return render(complex, plots, culture, level, false);
    }

    public static Stats render(FarmComplex complex, List<FarmPlot> plots,
                                String culture, ServerLevel level,
                                boolean verbose) {
        if (complex == null || plots == null || level == null) {
            return new Stats(0, 0);
        }
        if (complex.region() == null) return new Stats(0, 0);

        long seed = complex.id().getMostSignificantBits()
                ^ complex.id().getLeastSignificantBits();
        Random rng = new Random(seed);

        // 1. Paths FIRST. Path corridors get stamped before any
        //    fences are placed; borders then skip cells whose
        //    surface is dirt-path. Eliminates the "fence sitting
        //    on a path strip" visual bug.
        //
        //    Path material resolved from the culture's planningBias
        //    — same lookup the V2 RoadPainter uses, so farm paths
        //    match village roads at the boundary.
        var cultureObj = tterrag1112.life_in_the_village.Cultures
                .CultureRegistry.getOrDefault(culture);
        var roadMaterial = PathRenderer.resolveRoadMaterial(
                cultureObj.planningBias().roadMaterial());
        PathRenderer.render(complex.pathSegments(), roadMaterial, level);

        // 2. Borders — dedup shared edges across adjacent plots.
        //    The per-column placement in AbstractBorderGenerator
        //    skips cells where ground = dirt-path, leaving natural
        //    gaps where the path crosses a fence line.
        renderBorders(complex, plots, culture, level, rng);

        // 3. Gates — branches crossing border lines get fence-gate
        //    cuts. Runs after borders so there's something to swap.
        Stats gateStats = renderGates(complex, level, verbose);

        // 4. Plot interiors.
        for (FarmPlot plot : plots) {
            if (plot == null) continue;
            long plotSeed = seed ^ plot.getId().getMostSignificantBits();
            PlotInteriorRenderer.render(plot, level, new Random(plotSeed));
        }

        // 5. Tool shed.
        if (complex.toolShedPosition() != null) {
            Direction door = shedDoorDirection(complex);
            ToolShedRenderer.render(complex.toolShedPosition(), door, level, rng);
        }

        // 6. Props.
        List<Polygon> plotPolys = new java.util.ArrayList<>(plots.size());
        for (FarmPlot p : plots) {
            if (p != null && p.getPolygon() != null) plotPolys.add(p.getPolygon());
        }
        PropsScatterRenderer.render(complex.region(), plotPolys, level, rng);

        return gateStats;
    }

    // =========================================================================
    // Borders + edge dedup
    // =========================================================================

    /** Walks every plot's polygon edges; collects each edge once
     *  via an order-independent key; resolves the edge's style
     *  from the deterministic {@link PlotBorders} assignment;
     *  hands off to the registered {@link BorderGenerator}.
     *
     *  <p>The plot region is bounded by plot polygons; the complex
     *  region's outer perimeter is also painted by iterating
     *  region edges with the lowest-id plot's primary style as the
     *  default (gives the complex a coherent outer fence). */
    private static void renderBorders(FarmComplex complex, List<FarmPlot> plots,
                                       String culture,
                                       ServerLevel level, Random rng) {
        // Map plotId → its PlotBorders entry for style lookup.
        Map<UUID, PlotBorders> bordersById = new HashMap<>();
        for (PlotBorders b : complex.plotBorders()) {
            bordersById.put(b.plotId(), b);
        }

        Set<Long> seenEdges = new HashSet<>();
        // Outer perimeter — use the FIRST plot's primary style as
        // the complex-wide fallback (the BorderStyleAssigner already
        // picked diverse styles per plot; the lowest-index one
        // stamps the perimeter consistently).
        BorderStyleId perimeterStyle = null;
        if (!plots.isEmpty() && bordersById.get(plots.get(0).getId()) != null) {
            perimeterStyle = bordersById.get(plots.get(0).getId()).primary();
        }

        // 1) Plot edges first — these are interior boundaries when
        //    shared between adjacent plots.
        for (FarmPlot plot : plots) {
            if (plot == null || plot.getPolygon() == null) continue;
            PlotBorders pb = bordersById.get(plot.getId());
            if (pb == null) continue;
            List<BlockPos> verts = plot.getPolygon().vertices();
            int n = verts.size();
            for (int i = 0; i < n; i++) {
                BlockPos a = verts.get(i);
                BlockPos b = verts.get((i + 1) % n);
                long key = edgeKey(a, b);
                if (!seenEdges.add(key)) continue;
                BorderStyleId style = styleForEdge(pb, i);
                paintEdge(a, b, style, culture, level, rng);
            }
        }

        // 2) Outer region perimeter — only if not already covered
        //    by a plot edge (the polygon-side dedup above will skip
        //    any segment that matches a plot edge).
        if (perimeterStyle != null) {
            List<BlockPos> regionVerts = complex.region().vertices();
            int rn = regionVerts.size();
            for (int i = 0; i < rn; i++) {
                BlockPos a = regionVerts.get(i);
                BlockPos b = regionVerts.get((i + 1) % rn);
                long key = edgeKey(a, b);
                if (!seenEdges.add(key)) continue;
                paintEdge(a, b, perimeterStyle, culture, level, rng);
            }
        }
    }

    private static void paintEdge(BlockPos a, BlockPos b, BorderStyleId style,
                                   String culture, ServerLevel level, Random rng) {
        BorderGenerator gen = BorderGeneratorRegistry
                .get(culture, style)
                .orElse(new HedgeBorder());  // final fallback
        Direction outward = outwardNormal(a, b);
        gen.renderEdge(a, b, outward, level, rng);
    }

    private static BorderStyleId styleForEdge(PlotBorders pb, int edgeIndex) {
        for (PlotBorders.EdgeStyle es : pb.edgeStyles()) {
            if (es.edgeIndex() == edgeIndex) return es.style();
        }
        return pb.primary();
    }

    /** Order-independent edge key (matches BorderStyleAssigner's
     *  pattern so dedup intersects correctly). */
    private static long edgeKey(BlockPos a, BlockPos b) {
        long ka = ((long) a.getX() << 32) | (a.getZ() & 0xFFFFFFFFL);
        long kb = ((long) b.getX() << 32) | (b.getZ() & 0xFFFFFFFFL);
        long lo = Math.min(ka, kb);
        long hi = Math.max(ka, kb);
        return lo * 1_000_003L ^ hi;
    }

    /** Right-hand perpendicular of (a → b), snapped to nearest
     *  cardinal. Returned vector points AWAY from a clockwise-
     *  wound polygon's interior. Used by gate-aware styles. */
    private static Direction outwardNormal(BlockPos a, BlockPos b) {
        int dx = b.getX() - a.getX();
        int dz = b.getZ() - a.getZ();
        if (dx == 0 && dz == 0) return Direction.SOUTH;
        return Direction.getNearest(dz, 0, -dx, Direction.SOUTH);
    }

    // =========================================================================
    // Gates
    // =========================================================================

    /** Standalone gate placement at each branch's plot-side
     *  endpoint. The gate stands as a threshold marker — no
     *  border-finding required (previous walk-and-probe strategy
     *  consistently returned NO_BORDER_FOUND because the border
     *  path-skip is wider than the probe range).
     *
     *  <p>Orient the gate perpendicular to the branch direction
     *  (the gate "swings" across the branch line). Place at
     *  groundY+1; clear that slot first. */
    private static Stats renderGates(FarmComplex complex, ServerLevel level,
                                       boolean verbose) {
        int placed = 0, skipped = 0;
        for (PlotEntry pe : complex.plotEntries()) {
            if (placeGateAt(pe.spineAttach(), pe.entry(),
                    pe.plotId(), level, verbose)) {
                placed++;
            } else {
                skipped++;
            }
        }
        if (verbose) {
            org.slf4j.LoggerFactory.getLogger(FarmComplexRenderer.class).info(
                    "renderGates summary: placed={} skipped={}", placed, skipped);
        }
        return new Stats(placed, skipped);
    }

    private static boolean placeGateAt(BlockPos spineAttach, BlockPos entry,
                                         UUID plotId,
                                         ServerLevel level,
                                         boolean verbose) {
        int ex = entry.getX(), ez = entry.getZ();
        int ax = spineAttach.getX(), az = spineAttach.getZ();
        // Branch direction (cardinal). Fence-gate's
        // HORIZONTAL_FACING points along the direction you walk
        // through it; we want that to match the branch's walk
        // direction so the gate's panel sits perpendicular to
        // the branch (i.e. blocks/opens across it).
        Direction branchDir;
        if (Math.abs(ex - ax) >= Math.abs(ez - az)) {
            branchDir = ex >= ax ? Direction.EAST : Direction.WEST;
        } else {
            branchDir = ez >= az ? Direction.SOUTH : Direction.NORTH;
        }

        int gy = level.getHeight(net.minecraft.world.level.levelgen
                .Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, ex, ez) - 1;
        if (gy <= 0) {
            if (verbose) {
                org.slf4j.LoggerFactory.getLogger(FarmComplexRenderer.class).info(
                        "gate plot={} entry=({},{}) SKIPPED reason=NO_GROUND",
                        shortId(plotId), ex, ez);
            }
            return false;
        }
        BlockPos here = new BlockPos(ex, gy + 1, ez);
        BlockState floor = level.getBlockState(new BlockPos(ex, gy, ez));
        // The branch endpoint is the plot centroid, which the
        // PathRenderer might have stamped if the branch routed
        // through (rare with axis-aligned branches but possible).
        // Don't double-place on a path cell; the path already
        // creates the visual opening.
        String skipReason = null;
        if (floor.is(Blocks.DIRT_PATH)
                || floor.is(Blocks.GRAVEL)
                || floor.is(Blocks.COARSE_DIRT)
                || floor.is(Blocks.SMOOTH_STONE)
                || floor.is(Blocks.STONE_BRICKS)) {
            // Path surface here means the gate would sit ON the
            // spine/branch surface, not at the plot threshold.
            // That's still a valid threshold marker — log it but
            // proceed with placement.
            // (Allow placement; skipReason stays null.)
        }
        if (floor.is(Blocks.FARMLAND)) {
            skipReason = "FARMLAND_AT_FLOOR";
        }
        if (skipReason != null) {
            if (verbose) {
                org.slf4j.LoggerFactory.getLogger(FarmComplexRenderer.class).info(
                        "gate plot={} entry=({},{}) SKIPPED reason={}",
                        shortId(plotId), ex, ez, skipReason);
            }
            return false;
        }

        // Single-block head clearance — gate is 1 tall.
        BlockState atGate = level.getBlockState(here);
        if (!atGate.isAir() && !atGate.canBeReplaced()) {
            // Refuse to overwrite a building wall / existing
            // border. Just log and skip; renderer is robust to
            // missing gates.
            if (verbose) {
                org.slf4j.LoggerFactory.getLogger(FarmComplexRenderer.class).info(
                        "gate plot={} entry=({},{}) SKIPPED reason=OCCUPIED ({})",
                        shortId(plotId), ex, ez, atGate.getBlock());
            }
            return false;
        }
        // Place the gate.
        BlockState gate = Blocks.OAK_FENCE_GATE.defaultBlockState();
        if (gate.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            gate = gate.setValue(BlockStateProperties.HORIZONTAL_FACING, branchDir);
        }
        if (gate.hasProperty(BlockStateProperties.OPEN)) {
            gate = gate.setValue(BlockStateProperties.OPEN, true);
        }
        level.setBlock(here, gate, 3);
        if (verbose) {
            org.slf4j.LoggerFactory.getLogger(FarmComplexRenderer.class).info(
                    "gate plot={} entry=({},{},{}) facing={} PLACED",
                    shortId(plotId), here.getX(), here.getY(), here.getZ(),
                    branchDir);
        }
        return true;
    }

    private static String shortId(UUID id) {
        return id == null ? "?" : id.toString().substring(0, 8);
    }

    // =========================================================================
    // Shed door direction
    // =========================================================================

    /** Picks the door direction toward the spine path. Approximated
     *  as the cardinal closest to the vector from the shed
     *  position to the spine's road-facing origin (the first
     *  spine segment's start endpoint). */
    private static Direction shedDoorDirection(FarmComplex complex) {
        BlockPos shed = complex.toolShedPosition();
        if (shed == null) return Direction.SOUTH;
        PathSegment firstSpine = null;
        for (PathSegment s : complex.pathSegments()) {
            if (s.isSpine()) { firstSpine = s; break; }
        }
        if (firstSpine == null) return Direction.SOUTH;
        int dx = firstSpine.start().getX() - shed.getX();
        int dz = firstSpine.start().getZ() - shed.getZ();
        return Direction.getNearest(dx, 0, dz, Direction.SOUTH);
    }
}
