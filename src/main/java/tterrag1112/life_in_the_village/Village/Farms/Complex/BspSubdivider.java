package tterrag1112.life_in_the_village.Village.Farms.Complex;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Utilities.Geometry.Polygon;
import tterrag1112.life_in_the_village.Village.Buildings.FarmPlot.CropType;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Detour A — BSP subdivision of the flood-fill region into plot
 * candidates.
 *
 * <p>Algorithm:
 * <ol>
 *   <li>Take the region polygon's axis-aligned bounding box.</li>
 *   <li>Recursive binary cut alternating X / Z axis. Cut position
 *       randomised within the middle 50% of the axis range so we
 *       never produce edge slivers.</li>
 *   <li>Stop when a node's area is at or below the target plot area
 *       ({@code regionArea / targetPlotCount * 1.5}), or below
 *       {@code minPlotSize * 2}.</li>
 *   <li>Each leaf rectangle is clipped against the region polygon
 *       and against {@code buildingBounds} (when non-null) by
 *       sampling cells; output cells form a per-leaf polygon via
 *       {@link CellPolygonizer}.</li>
 *   <li>Plots with final area below {@code minPlotSize} are dropped.</li>
 *   <li>Crop type assigned by weighted draw from the spec's
 *       {@code plotTypeMix}; adjacent plots prefer different types
 *       (greedy alternation within the loop).</li>
 * </ol>
 *
 * <p>Pure function. Deterministic per {@code (Input, seed)}.
 */
public final class BspSubdivider {

    private BspSubdivider() {}

    public record Input(
            Polygon region,
            Polygon buildingBounds,   // nullable
            int minPlotSize,
            int targetPlotCount,
            Map<CropType, Float> plotTypeMix,
            int cellSize,
            long seed,
            /** When true, BspSubdivider emits one log line at the
             *  start of {@link #run} showing the effective
             *  targetPlotCount, regionArea, stopArea, and
             *  minSideBlocks; plus a summary line at the end with
             *  leaf / plot / dropped counts. Off in production. */
            boolean verbose,
            /** Agriculture-ring stage 1 (design 13 ⚑2) — strip-aspect
             *  hint. When {@code > 0}, the recursion's cut-axis choice
             *  steers leaves toward this long:short ratio (cut the long
             *  axis when a rect is stringier than the hint, else cut the
             *  SHORT axis to elongate) so plots read as field STRIPS
             *  along the lane rather than squares. {@code 0} ⇒ the
             *  legacy alternating-axis behaviour, byte-identical. */
            double stripAspect) {

        /** Backward-compat ctor for callers that don't yet pass
         *  the verbose flag. */
        public Input(Polygon region, Polygon buildingBounds,
                     int minPlotSize, int targetPlotCount,
                     Map<CropType, Float> plotTypeMix,
                     int cellSize, long seed) {
            this(region, buildingBounds, minPlotSize, targetPlotCount,
                    plotTypeMix, cellSize, seed, false, 0);
        }

        /** Backward-compat ctor for callers passing verbose but no
         *  strip-aspect hint. */
        public Input(Polygon region, Polygon buildingBounds,
                     int minPlotSize, int targetPlotCount,
                     Map<CropType, Float> plotTypeMix,
                     int cellSize, long seed, boolean verbose) {
            this(region, buildingBounds, minPlotSize, targetPlotCount,
                    plotTypeMix, cellSize, seed, verbose, 0);
        }
    }

    public record Result(List<PlotPlan> plots, int droppedTooSmall) {}

    public static Result run(Input in) {
        if (in.region() == null || in.targetPlotCount() <= 0) {
            return new Result(List.of(), 0);
        }
        Polygon.AABB bb = Polygon.boundingBox(in.region());
        double regionArea = Polygon.area(in.region());
        if (regionArea <= 0) return new Result(List.of(), 0);
        // stopArea works on RECT (bbox) area at recursion time, so
        // the target must also be derived from bbox area — otherwise
        // the rect-vs-polygon-area mismatch (bbox > polygon for
        // irregular shapes) leaves rects always "too big" and the
        // recursion descends one level too deep. Pre-fix used
        // polygon area and a 1.5× safety multiplier; produced 7+
        // plots for targetPlotCount=4 on typical 500-cell regions.
        double bboxArea = (double) (bb.maxX() - bb.minX())
                        * (double) (bb.maxZ() - bb.minZ());
        double targetPlotArea = bboxArea / Math.max(1, in.targetPlotCount());
        double stopArea = Math.max(targetPlotArea,
                in.minPlotSize() * (double) in.cellSize() * 2);
        // minSideBlocks: minimum width a CHILD rect must have to be
        // a viable plot. Computed as the side-length of a square
        // plot containing exactly minPlotSize cells.
        //   minPlotSize = 16 cells → √16 = 4 cells per side
        //   × cellSize (2 blocks) = 8 blocks per side
        // Cut eligibility requires the PARENT rect to be ≥ 2× this
        // (so each child clears the threshold). Pre-fix formula
        // (minPlotSize × cellSize = 32) required 64-wide rects and
        // gated BSP from cutting on typical 500-cell regions; smoke
        // test produced only 2 plots when target was 4.
        int minSideBlocks = (int) Math.ceil(Math.sqrt(in.minPlotSize()))
                * in.cellSize();

        if (in.verbose()) {
            org.slf4j.LoggerFactory.getLogger(BspSubdivider.class).info(
                    "BspSubdivider.run: targetPlotCount={} minPlotSize={} cellSize={} "
                            + "regionArea={} bboxArea={} bbox=[{},{}..{},{}] ({}x{}) "
                            + "targetPlotArea={} stopArea={} minSideBlocks={} "
                            + "(cut requires width≥{})",
                    in.targetPlotCount(), in.minPlotSize(), in.cellSize(),
                    (int) regionArea, (int) bboxArea,
                    bb.minX(), bb.minZ(), bb.maxX(), bb.maxZ(),
                    bb.maxX() - bb.minX(), bb.maxZ() - bb.minZ(),
                    (int) targetPlotArea, (int) stopArea, minSideBlocks,
                    2 * minSideBlocks);
        }

        Random rng = new Random(in.seed());

        // Recurse: each rect is [minX, minZ, maxX, maxZ].
        List<int[]> leaves = new ArrayList<>();
        recurse(new int[]{bb.minX(), bb.minZ(), bb.maxX(), bb.maxZ()},
                /*axisToggle*/ chooseFirstAxis(bb),
                stopArea, minSideBlocks, in.stripAspect(),
                /*depth*/ 0, rng, leaves);

        if (in.verbose()) {
            org.slf4j.LoggerFactory.getLogger(BspSubdivider.class).info(
                    "BspSubdivider.run: recursion produced {} leaf rect(s)",
                    leaves.size());
        }

        // Sample membership: cells inside region polygon AND outside
        // buildingBounds belong to that leaf. Cell coords are
        // world-space sampled at cellSize step inside the rect.
        int cellSize = in.cellSize();
        List<PlotPlan> plots = new ArrayList<>();
        int dropped = 0;
        int plotIndex = 0;
        for (int[] rect : leaves) {
            java.util.Set<Long> cells = new java.util.HashSet<>();
            int wMinX = rect[0], wMinZ = rect[1], wMaxX = rect[2], wMaxZ = rect[3];
            // Exclusive on the high end so adjacent BSP rects don't
            // both claim the shared-boundary cell. Pre-fix the loop
            // used `<=`, which caused plot A's east cell + plot B's
            // west cell to overlap at a shared rect boundary. The
            // resulting polygons traced edges 1 cell INSIDE each
            // rect (so adjacent plot fences ran 2 blocks apart with
            // a strip of doubled farmland between them — the
            // "doubled border" bug). With `<` the boundary cell
            // belongs only to the rect to its right; polygon edges
            // land exactly on the BSP boundary, identical between
            // adjacent plots, and the renderer's edgeKey dedup
            // catches them.
            for (int wx = wMinX; wx <  wMaxX; wx += cellSize) {
                for (int wz = wMinZ; wz <  wMaxZ; wz += cellSize) {
                    if (!Polygon.contains(in.region(), wx, wz)) continue;
                    if (in.buildingBounds() != null
                            && Polygon.contains(in.buildingBounds(), wx, wz)) continue;
                    // Pack on a CELL grid local to the rect's
                    // top-left; CellPolygonizer is grid-agnostic
                    // as long as keys are consistent.
                    int ci = (wx - wMinX) / cellSize;
                    int cj = (wz - wMinZ) / cellSize;
                    cells.add(CellPolygonizer.packCell(ci, cj));
                }
            }
            if (cells.size() < in.minPlotSize()) {
                dropped++;
                continue;
            }
            BlockPos rectOrigin = new BlockPos(wMinX,
                    pickVertexY(in.region()), wMinZ);
            Polygon plotPoly = CellPolygonizer.polygonize(cells, cellSize,
                    rectOrigin, pickVertexY(in.region()));
            if (plotPoly == null) {
                dropped++;
                continue;
            }
            BlockPos centroid = Polygon.centroid(plotPoly);
            plots.add(new PlotPlan(plotIndex++, plotPoly, centroid,
                    null, cells.size()));
        }

        // Assign crop types: weighted random with greedy alternation
        // — when the planned type matches a same-axis-adjacent
        // plot's type, re-roll once.
        List<PlotPlan> typed = assignCrops(plots, in.plotTypeMix(), rng);
        if (in.verbose()) {
            org.slf4j.LoggerFactory.getLogger(BspSubdivider.class).info(
                    "BspSubdivider.run: post-clip plots={} dropped(<minPlotSize)={}",
                    typed.size(), dropped);
        }
        return new Result(typed, dropped);
    }

    // ── BSP recursion ──────────────────────────────────────────────────

    private static int chooseFirstAxis(Polygon.AABB bb) {
        // Cut on the longer dimension first so the first split
        // halves the wider side; otherwise BSP can produce long
        // thin strips early.
        return (bb.maxX() - bb.minX()) >= (bb.maxZ() - bb.minZ()) ? 0 : 1;
    }

    /** Hard recursion-depth backstop. A binary subdivision of any
     *  sane farm bbox terminates in well under this many levels
     *  (each viable cut at least halves an axis, and bbox sides are
     *  bounded by the spec radius). If we ever hit this ceiling the
     *  region shape has defeated the cut logic; rather than overflow
     *  the stack and crash the server during worldgen we log ONE WARN
     *  with the offending rect and emit it as a single plot. */
    private static final int MAX_DEPTH = 32;
    private static volatile boolean LOGGED_DEPTH_CAP = false;

    private static void recurse(int[] rect, int axis, double stopArea,
                                 int minSideBlocks, double stripAspect,
                                 int depth, Random rng, List<int[]> out) {
        int w = rect[2] - rect[0];
        int h = rect[3] - rect[1];
        double area = (double) w * h;
        if (area <= stopArea) { out.add(rect); return; }
        // Backstop: depth ceiling. Worldgen must never crash the server
        // on a weird (concave / wedge / sliver) claim — emit the rect
        // whole and warn once. The strict-shrink guards below should
        // make this unreachable in practice; it exists so a future
        // regression degrades to "one big plot" instead of a
        // StackOverflowError (crash-2026-06-12: stripAspect recompute
        // defeated the axis-fallback and recursed forever on a rect
        // narrow on its steered axis but wide on the other).
        if (depth >= MAX_DEPTH) {
            if (!LOGGED_DEPTH_CAP) {
                org.slf4j.LoggerFactory.getLogger(BspSubdivider.class).warn(
                        "BspSubdivider.recurse: hit MAX_DEPTH={} on rect "
                                + "[{}, {}]..[{}, {}] ({}x{}); emitting as a single "
                                + "plot. Region shape likely concave/sliver; not a crash.",
                        MAX_DEPTH, rect[0], rect[1], rect[2], rect[3], w, h);
                LOGGED_DEPTH_CAP = true;
            }
            out.add(rect);
            return;
        }
        // Agriculture-ring stage 1 — strip-aspect steering. With a hint,
        // the cut axis is chosen per node instead of alternating: a rect
        // stringier than the hint cuts its LONG axis (rein the string in);
        // anything else cuts its SHORT axis (elongate toward the hint).
        // Leaves oscillate around the target long:short ratio, so plots
        // read as strips. Hint 0 keeps the legacy alternation exactly.
        if (stripAspect > 0) {
            int longDim = Math.max(w, h);
            int shortDim = Math.max(1, Math.min(w, h));
            boolean cutLong = (double) longDim / shortDim > stripAspect;
            boolean xIsLong = w >= h;
            axis = cutLong == xIsLong ? 0 : 1;
        }
        // Cut eligibility per axis: a child must clear minSideBlocks, so
        // the parent must be ≥ 2× along the axis we cut. Decide the cut
        // axis HERE (not by recursing with a different axis arg): the
        // pre-fix fallback re-called recurse(rect, otherAxis, …), but the
        // strip-aspect block above re-derives `axis` from the unchanged
        // rect dims on re-entry and clobbers that argument, so the rect
        // never shrank → infinite self-recursion (the StackOverflow). We
        // resolve the eligible axis inline and only recurse on genuinely
        // smaller children; if neither axis can be cut, emit the rect.
        boolean canCutX = w >= 2 * minSideBlocks;
        boolean canCutZ = h >= 2 * minSideBlocks;
        if (axis == 0 && !canCutX) axis = 1;       // steered X infeasible → Z
        else if (axis == 1 && !canCutZ) axis = 0;  // steered Z infeasible → X
        if ((axis == 0 && !canCutX) || (axis == 1 && !canCutZ)) {
            // Neither axis admits a viable cut — terminal leaf.
            out.add(rect);
            return;
        }
        // E.bug.4 — Leave a {@link #FOOTPATH_BLOCKS} gap between
        // children so adjacent plots never share a border edge.
        // The gap cells become footpath territory: they're inside
        // the region polygon, not in any plot polygon, and
        // FarmComplexRenderer rasterizes them into pathCells at
        // render time so paths cross there and borders skip them.
        // Cut at a random position in the middle 50% of the axis.
        int cut;
        int gapHalf = FOOTPATH_BLOCKS / 2;
        if (axis == 0) {
            int lo = rect[0] + w / 4;
            int hi = rect[2] - w / 4;
            cut = lo + rng.nextInt(Math.max(1, hi - lo + 1));
            int[] left  = new int[]{rect[0],          rect[1],
                                     cut - gapHalf,    rect[3]};
            int[] right = new int[]{cut + gapHalf,    rect[1],
                                     rect[2],          rect[3]};
            // Strict-shrink invariant: every child must be dimensionally
            // smaller than the parent on the cut axis. The cut lands in
            // the middle 50% and the gap only narrows children further,
            // so each child's width < parent width by construction. If a
            // degenerate cut ever failed to shrink a child, emit the
            // parent rather than recurse on an equal-or-larger box.
            if (childShrank(left, rect, 0) && childShrank(right, rect, 0)) {
                recurse(left,  1, stopArea, minSideBlocks, stripAspect, depth + 1, rng, out);
                recurse(right, 1, stopArea, minSideBlocks, stripAspect, depth + 1, rng, out);
            } else {
                out.add(rect);
            }
        } else {
            int lo = rect[1] + h / 4;
            int hi = rect[3] - h / 4;
            cut = lo + rng.nextInt(Math.max(1, hi - lo + 1));
            int[] top    = new int[]{rect[0],          rect[1],
                                     rect[2],          cut - gapHalf};
            int[] bottom = new int[]{rect[0],          cut + gapHalf,
                                     rect[2],          rect[3]};
            if (childShrank(top, rect, 1) && childShrank(bottom, rect, 1)) {
                recurse(top,    0, stopArea, minSideBlocks, stripAspect, depth + 1, rng, out);
                recurse(bottom, 0, stopArea, minSideBlocks, stripAspect, depth + 1, rng, out);
            } else {
                out.add(rect);
            }
        }
    }

    /** True iff {@code child} is strictly smaller than {@code parent}
     *  along {@code axis} (0 = X width, 1 = Z height) AND non-degenerate
     *  (positive extent) on that axis. The strict-shrink guard that
     *  guarantees recursion terminates: a child that didn't shrink (or
     *  inverted to zero/negative size) is never recursed into. */
    private static boolean childShrank(int[] child, int[] parent, int axis) {
        int childExtent  = axis == 0 ? child[2]  - child[0]  : child[3]  - child[1];
        int parentExtent = axis == 0 ? parent[2] - parent[0] : parent[3] - parent[1];
        return childExtent > 0 && childExtent < parentExtent;
    }

    /** E.bug.4 — width of the footpath inserted between adjacent
     *  BSP plots, in blocks. Each child rect shrinks by half this
     *  amount along the cut axis, so the gap between two adjacent
     *  plots is exactly this many blocks. The cells in the gap
     *  fall inside the region polygon but outside every plot
     *  polygon → derived as footpath cells at render time.
     *  Tunable per visual feedback. */
    private static final int FOOTPATH_BLOCKS = 2;

    // ── Crop assignment (size-aware) ──────────────────────────────────

    /** Weighted assignment using
     *  {@link CropSizePreferences#fitScore} × spec.plotTypeMix[crop].
     *  Plots are processed in a randomly-shuffled order so the
     *  highest-scoring plot doesn't always claim its best fit
     *  first — gives the size-matching a chance to spread crops
     *  organically across the complex.
     *
     *  <p>Tiebreak: highest plotTypeMix weight, then crop enum
     *  ordinal (deterministic). Each plot picks the crop with
     *  highest combined score for its cell count. */
    private static List<PlotPlan> assignCrops(List<PlotPlan> plots,
                                               Map<CropType, Float> mix,
                                               Random rng) {
        if (plots.isEmpty()) return plots;
        if (mix == null || mix.isEmpty()) return plots;

        EnumMap<CropType, Float> normMix = new EnumMap<>(CropType.class);
        for (Map.Entry<CropType, Float> e : mix.entrySet()) {
            if (e.getValue() > 0) normMix.put(e.getKey(), e.getValue());
        }
        if (normMix.isEmpty()) return plots;

        // Process plots in shuffled order so leaf 0 doesn't
        // always claim its best fit first.
        Integer[] order = new Integer[plots.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        // Fisher-Yates via the seeded RNG (Collections.shuffle
        // would also work but this keeps the dependency surface
        // smaller).
        for (int i = order.length - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            Integer tmp = order[i]; order[i] = order[j]; order[j] = tmp;
        }

        PlotPlan[] typed = new PlotPlan[plots.size()];
        for (int idx : order) {
            PlotPlan plot = plots.get(idx);
            CropType chosen = pickByFit(normMix, plot.cellCount());
            typed[idx] = new PlotPlan(plot.plotIndex(), plot.polygon(),
                    plot.centroid(), chosen, plot.cellCount());
        }
        return java.util.Arrays.asList(typed);
    }

    /** Pick the crop with the highest combined score
     *  (mixWeight × fitScore). Tiebreak on raw mix weight then
     *  enum ordinal. */
    private static CropType pickByFit(EnumMap<CropType, Float> normMix,
                                       int cellCount) {
        CropType best = null;
        double bestScore = -1;
        for (Map.Entry<CropType, Float> e : normMix.entrySet()) {
            CropType crop = e.getKey();
            double fit = CropSizePreferences.fitScore(crop, cellCount,
                    CropSizePreferences.DEFAULT_FLOOR);
            double score = e.getValue() * fit;
            if (score > bestScore
                    || (score == bestScore
                        && (best == null
                            || (normMix.getOrDefault(crop, 0f)
                                    > normMix.getOrDefault(best, 0f))
                            || (normMix.getOrDefault(crop, 0f).equals(
                                    normMix.getOrDefault(best, 0f))
                                && crop.ordinal() < best.ordinal())))) {
                best = crop;
                bestScore = score;
            }
        }
        return best;
    }

    private static int pickVertexY(Polygon region) {
        // All region vertices share the same Y (set by the flood-
        // fill caller). Return the first one.
        return region.vertices().isEmpty() ? 0
                : region.vertices().get(0).getY();
    }
}
