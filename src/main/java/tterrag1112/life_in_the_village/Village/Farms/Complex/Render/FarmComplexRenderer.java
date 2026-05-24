package tterrag1112.life_in_the_village.Village.Farms.Complex.Render;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Utilities.Geometry.Polygon;
import tterrag1112.life_in_the_village.Village.Buildings.Complex.BorderStyleId;
import tterrag1112.life_in_the_village.Village.Buildings.FarmPlot;
import tterrag1112.life_in_the_village.Village.Farms.Complex.FarmComplex;
import tterrag1112.life_in_the_village.Village.Farms.Complex.PathSegment;
import tterrag1112.life_in_the_village.Village.Farms.Complex.PlotBorders;
import tterrag1112.life_in_the_village.Village.Farms.Complex.Render.Borders.BorderGenerator;
import tterrag1112.life_in_the_village.Village.Farms.Complex.Render.Borders.BorderGeneratorRegistry;
import tterrag1112.life_in_the_village.Village.Farms.Complex.Render.Borders.HedgeBorder;

import java.util.ArrayList;
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
 *   <li>Paths (stamps dirt-path corridors).</li>
 *   <li>Borders (one pass; plot polygons collectively cover the
 *       region perimeter — no separate region-perimeter pass).</li>
 *   <li>Plot interiors.</li>
 *   <li>Tool shed.</li>
 *   <li>Props.</li>
 * </ol>
 *
 * <p>Deterministic per complex id: same complex re-renders identically.
 *
 * <p>The path-skip behaviour for borders is fed by a precomputed
 * {@link #rasterizePathCells} set — every block position the path
 * renderer touches is marked, and borders that would land on those
 * positions are skipped. Cheap O(1) set lookup at placement time;
 * no block-state inspection, no polygon test, no order dependency
 * on render sequence.
 */
public final class FarmComplexRenderer {

    private FarmComplexRenderer() {}

    public static void render(FarmComplex complex, List<FarmPlot> plots,
                               String culture, ServerLevel level) {
        render(complex, plots, culture, level, false);
    }

    public static void render(FarmComplex complex, List<FarmPlot> plots,
                               String culture, ServerLevel level,
                               boolean verbose) {
        if (complex == null || plots == null || level == null) return;
        if (complex.region() == null) return;

        long seed = complex.id().getMostSignificantBits()
                ^ complex.id().getLeastSignificantBits();
        Random rng = new Random(seed);

        // 1. Paths — stamp dirt-path corridors first. Borders use
        //    the rasterized path-cell set below to skip placement
        //    where a path crosses, so render order between paths
        //    and borders doesn't matter for the skip logic; we
        //    just keep the existing visual order.
        var cultureObj = tterrag1112.life_in_the_village.Cultures
                .CultureRegistry.getOrDefault(culture);
        var roadMaterial = PathRenderer.resolveRoadMaterial(
                cultureObj.planningBias().roadMaterial());
        var pathPalette = PathPalette.single(roadMaterial,
                PathRenderer.DEFAULT_HEAD_CLEARANCE);
        PathRenderer.render(complex.pathSegments(), pathPalette, level);

        // 2. Borders. The path-cell set is built from the complex's
        //    PathSegment list (the registry the complex already
        //    maintains) — borders consult it via the BorderGenerator
        //    interface, no block-state lookup needed.
        Set<Long> pathCells = rasterizePathCells(complex.pathSegments());
        renderBorders(complex, plots, culture, level, rng, pathCells);

        // 3. Plot interiors.
        for (FarmPlot plot : plots) {
            if (plot == null) continue;
            long plotSeed = seed ^ plot.getId().getMostSignificantBits();
            PlotInteriorRenderer.render(plot, level, new Random(plotSeed));
        }

        // 4. Tool shed.
        if (complex.toolShedPosition() != null) {
            Direction door = shedDoorDirection(complex);
            ToolShedRenderer.render(complex.toolShedPosition(), door, level, rng);
        }

        // 5. Props.
        List<Polygon> plotPolys = new ArrayList<>(plots.size());
        for (FarmPlot p : plots) {
            if (p != null && p.getPolygon() != null) plotPolys.add(p.getPolygon());
        }
        PropsScatterRenderer.render(complex.region(), plotPolys, level, rng);
    }

    // =========================================================================
    // Path-cell registry
    // =========================================================================

    /** Pack an XZ pair into a long matching
     *  {@link tterrag1112.life_in_the_village.Village.Farms.Complex.CellPolygonizer#packCell}'s
     *  layout so any future cross-references stay consistent. */
    static long packXZ(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    /** Rasterize every {@link PathSegment} into the XZ block
     *  positions it occupies (centerline strip, width-aware) and
     *  collect them into a set the border renderer can probe.
     *
     *  <p>Border generators call back into
     *  {@link BorderGenerator}'s context to test
     *  {@code pathCells.contains(packXZ(x, z))} per column — O(1)
     *  per position, no block-state inspection. Works regardless
     *  of the order in which paths vs borders render, and survives
     *  future per-culture palette mixing (no palette-block check
     *  to keep up to date). */
    static Set<Long> rasterizePathCells(List<PathSegment> segments) {
        Set<Long> cells = new HashSet<>();
        if (segments == null) return cells;
        for (PathSegment seg : segments) {
            int x0 = seg.start().getX(), z0 = seg.start().getZ();
            int x1 = seg.end().getX(),   z1 = seg.end().getZ();
            int width = Math.max(1, seg.width());
            int half = width / 2;

            int dx = x1 - x0, dz = z1 - z0;
            int len = Math.max(1, (int) Math.round(Math.sqrt(dx * dx + dz * dz)));
            double pxu =  dz / (double) len;
            double pzu = -dx / (double) len;

            // Bresenham over the centerline, perpendicular strip
            // per step — mirrors PathRenderer's walk so the
            // rasterized positions match the actual path stamp.
            int ax = Math.abs(dx), az = Math.abs(dz);
            int sx = dx >= 0 ? 1 : -1;
            int sz = dz >= 0 ? 1 : -1;
            int err = ax - az;
            int cx = x0, cz = z0;
            int safety = 0;
            while (safety++ < 8192) {
                for (int off = -half; off <= half; off++) {
                    int wx = cx + (int) Math.round(pxu * off);
                    int wz = cz + (int) Math.round(pzu * off);
                    cells.add(packXZ(wx, wz));
                }
                if (cx == x1 && cz == z1) break;
                int e2 = 2 * err;
                if (e2 > -az) { err -= az; cx += sx; }
                if (e2 <  ax) { err += ax; cz += sz; }
            }
        }
        return cells;
    }

    // =========================================================================
    // Borders (single pass — no region-perimeter duplicate)
    // =========================================================================

    /** Three-phase per-cell border dispatch.
     *
     *  <p><b>Resolution rule:</b> first plot to rasterize a given
     *  XZ cell wins its border style for that cell. Plot iteration
     *  order is {@code plots} (insertion order by UUID, stable per
     *  save). Deterministic; seed-stable across re-renders of the
     *  same complex.
     *
     *  <p><b>Pre-fix bugs (E.bug.2.1):</b>
     *  <ol>
     *    <li><b>Multi-variant at same XZ.</b> Adjacent plot
     *        polygons can have extent mismatches at shared
     *        boundaries (region-polygon clip differences); their
     *        edges then have different vertex coords for the same
     *        physical x or z line, edgeKey-based dedup fails, both
     *        plots paint with their own styles along the overlap.
     *    <li><b>Vertical stacking.</b> Each border's
     *        {@code resolveGroundY} consulted a live WORLD_SURFACE
     *        heightmap, which sees previously-placed border
     *        blocks. Sequential placements stacked on each other.
     *  </ol>
     *
     *  <p><b>Fix:</b> three phases.
     *  <ol>
     *    <li>Rasterize every plot edge to its XZ cells via
     *        Bresenham. For each cell, {@code putIfAbsent} the
     *        plot's style and the edge's outward normal. First
     *        plot wins → single dispatch per XZ.
     *    <li>Snapshot ground Y for every assigned cell via
     *        {@code AbstractBorderGenerator.resolveGroundY}
     *        BEFORE any border block is placed.
     *    <li>Paint each cell once with its assigned style and
     *        snapshot ground Y. Path-cells skipped; out-of-bound
     *        cells skipped.
     *  </ol>
     */
    private static void renderBorders(FarmComplex complex, List<FarmPlot> plots,
                                       String culture,
                                       ServerLevel level, Random rng,
                                       Set<Long> pathCells) {
        Map<UUID, PlotBorders> bordersById = new HashMap<>();
        for (PlotBorders b : complex.plotBorders()) {
            bordersById.put(b.plotId(), b);
        }

        // Phase 1: rasterize edges into per-cell style + outward.
        // LinkedHashMap preserves first-plot-wins iteration order
        // for phase 3 determinism.
        java.util.LinkedHashMap<Long, BorderStyleId> cellStyles =
                new java.util.LinkedHashMap<>();
        Map<Long, Direction> cellOutward = new HashMap<>();
        for (FarmPlot plot : plots) {
            if (plot == null || plot.getPolygon() == null) continue;
            PlotBorders pb = bordersById.get(plot.getId());
            if (pb == null) continue;
            List<BlockPos> verts = plot.getPolygon().vertices();
            int n = verts.size();
            for (int i = 0; i < n; i++) {
                BlockPos a = verts.get(i);
                BlockPos b = verts.get((i + 1) % n);
                BorderStyleId style = styleForEdge(pb, i);
                Direction outward = outwardNormal(a, b);
                rasterizeEdge(a, b, style, outward, cellStyles, cellOutward);
            }
        }

        // Phase 2: pre-render heightmap snapshot. All reads happen
        // before any setBlock so subsequent placements can't see a
        // previous border as "ground".
        Map<Long, Integer> cellGroundY = new HashMap<>(cellStyles.size());
        for (Long cell : cellStyles.keySet()) {
            int x = (int) (cell >> 32);
            int z = (int) (long) cell;
            cellGroundY.put(cell,
                    tterrag1112.life_in_the_village.Village.Farms.Complex
                            .Render.Borders.AbstractBorderGenerator
                            .resolveGroundY(level, x, z));
        }

        // Phase 3: paint each cell once with its resolved style +
        // snapshot ground Y. Skip path-cells (the path renderer's
        // corridor stays clear) and out-of-bounds Y.
        int minY = level.getMinY();
        int maxY = tterrag1112.life_in_the_village.Village.Farms.Complex
                .Render.Borders.AbstractBorderGenerator.MAX_BUILD_Y;
        for (Map.Entry<Long, BorderStyleId> entry : cellStyles.entrySet()) {
            long cell = entry.getKey();
            if (pathCells.contains(cell)) continue;
            int gy = cellGroundY.get(cell);
            if (gy < minY || gy >= maxY) continue;
            int x = (int) (cell >> 32);
            int z = (int) (long) cell;
            BorderStyleId style = entry.getValue();
            Direction outward = cellOutward.getOrDefault(cell, Direction.SOUTH);
            BorderGenerator gen = BorderGeneratorRegistry
                    .get(culture, style)
                    .orElse(new HedgeBorder());
            gen.paintColumnAt(level, x, z, gy, outward, rng);
        }
    }

    /** Bresenham-walk the edge (a → b); for each cell, assign the
     *  given style + outward iff the cell hasn't been claimed
     *  already (first-plot-wins). */
    private static void rasterizeEdge(BlockPos a, BlockPos b,
                                       BorderStyleId style,
                                       Direction outward,
                                       Map<Long, BorderStyleId> cellStyles,
                                       Map<Long, Direction> cellOutward) {
        int x0 = a.getX(), z0 = a.getZ();
        int x1 = b.getX(), z1 = b.getZ();
        int dx = Math.abs(x1 - x0), dz = Math.abs(z1 - z0);
        int sx = x0 < x1 ? 1 : -1, sz = z0 < z1 ? 1 : -1;
        int err = dx - dz;
        int safety = 0;
        while (safety++ < 4096) {
            long key = ((long) x0 << 32) | (z0 & 0xFFFFFFFFL);
            cellStyles.putIfAbsent(key, style);
            cellOutward.putIfAbsent(key, outward);
            if (x0 == x1 && z0 == z1) break;
            int e2 = 2 * err;
            if (e2 > -dz) { err -= dz; x0 += sx; }
            if (e2 <  dx) { err += dx; z0 += sz; }
        }
    }

    private static BorderStyleId styleForEdge(PlotBorders pb, int edgeIndex) {
        for (PlotBorders.EdgeStyle es : pb.edgeStyles()) {
            if (es.edgeIndex() == edgeIndex) return es.style();
        }
        return pb.primary();
    }

    /** Right-hand perpendicular of (a → b), snapped to nearest
     *  cardinal. */
    private static Direction outwardNormal(BlockPos a, BlockPos b) {
        int dx = b.getX() - a.getX();
        int dz = b.getZ() - a.getZ();
        if (dx == 0 && dz == 0) return Direction.SOUTH;
        return Direction.getNearest(dz, 0, -dx, Direction.SOUTH);
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
