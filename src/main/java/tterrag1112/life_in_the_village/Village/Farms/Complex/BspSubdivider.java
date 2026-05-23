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
            boolean verbose) {

        /** Backward-compat ctor for callers that don't yet pass
         *  the verbose flag. */
        public Input(Polygon region, Polygon buildingBounds,
                     int minPlotSize, int targetPlotCount,
                     Map<CropType, Float> plotTypeMix,
                     int cellSize, long seed) {
            this(region, buildingBounds, minPlotSize, targetPlotCount,
                    plotTypeMix, cellSize, seed, false);
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
        double targetPlotArea = (regionArea / in.targetPlotCount()) * 1.5;
        double stopArea = Math.max(targetPlotArea,
                in.minPlotSize() * (double) in.cellSize() * 2);
        int minSideBlocks = in.minPlotSize() * in.cellSize();

        if (in.verbose()) {
            org.slf4j.LoggerFactory.getLogger(BspSubdivider.class).info(
                    "BspSubdivider.run: targetPlotCount={} minPlotSize={} cellSize={} "
                            + "regionArea={} bbox=[{},{}..{},{}] ({}x{}) "
                            + "targetPlotArea={} stopArea={} minSideBlocks={} "
                            + "(cut requires width≥{})",
                    in.targetPlotCount(), in.minPlotSize(), in.cellSize(),
                    (int) regionArea,
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
                stopArea, minSideBlocks,
                rng, leaves);

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
            for (int wx = wMinX; wx <= wMaxX; wx += cellSize) {
                for (int wz = wMinZ; wz <= wMaxZ; wz += cellSize) {
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
            plots.add(new PlotPlan(plotIndex++, plotPoly, centroid, null));
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

    private static void recurse(int[] rect, int axis, double stopArea,
                                 int minSideBlocks, Random rng,
                                 List<int[]> out) {
        int w = rect[2] - rect[0];
        int h = rect[3] - rect[1];
        double area = (double) w * h;
        if (area <= stopArea) { out.add(rect); return; }
        if (axis == 0 && w < 2 * minSideBlocks) {
            // X cut would produce too-narrow children; try Z.
            if (h >= 2 * minSideBlocks) {
                recurse(rect, 1, stopArea, minSideBlocks, rng, out);
                return;
            }
            out.add(rect);
            return;
        }
        if (axis == 1 && h < 2 * minSideBlocks) {
            if (w >= 2 * minSideBlocks) {
                recurse(rect, 0, stopArea, minSideBlocks, rng, out);
                return;
            }
            out.add(rect);
            return;
        }
        // Cut at a random position in the middle 50% of the axis.
        int cut;
        if (axis == 0) {
            int lo = rect[0] + w / 4;
            int hi = rect[2] - w / 4;
            cut = lo + rng.nextInt(Math.max(1, hi - lo + 1));
            int[] left  = new int[]{rect[0], rect[1], cut,     rect[3]};
            int[] right = new int[]{cut,     rect[1], rect[2], rect[3]};
            recurse(left,  1, stopArea, minSideBlocks, rng, out);
            recurse(right, 1, stopArea, minSideBlocks, rng, out);
        } else {
            int lo = rect[1] + h / 4;
            int hi = rect[3] - h / 4;
            cut = lo + rng.nextInt(Math.max(1, hi - lo + 1));
            int[] top    = new int[]{rect[0], rect[1], rect[2], cut};
            int[] bottom = new int[]{rect[0], cut,     rect[2], rect[3]};
            recurse(top,    0, stopArea, minSideBlocks, rng, out);
            recurse(bottom, 0, stopArea, minSideBlocks, rng, out);
        }
    }

    // ── Crop assignment ────────────────────────────────────────────────

    private static List<PlotPlan> assignCrops(List<PlotPlan> plots,
                                               Map<CropType, Float> mix,
                                               Random rng) {
        if (plots.isEmpty()) return plots;
        if (mix == null || mix.isEmpty()) {
            // No mix → leave nulls; caller treats as undefined.
            return plots;
        }
        EnumMap<CropType, Float> normMix = new EnumMap<>(CropType.class);
        float total = 0f;
        for (Map.Entry<CropType, Float> e : mix.entrySet()) {
            if (e.getValue() > 0) {
                normMix.put(e.getKey(), e.getValue());
                total += e.getValue();
            }
        }
        if (total <= 0f) return plots;

        List<PlotPlan> out = new ArrayList<>(plots.size());
        CropType prev = null;
        for (PlotPlan p : plots) {
            CropType chosen = pickWeighted(normMix, total, rng);
            // Greedy alternation — re-roll once if same as prev.
            if (chosen == prev && normMix.size() > 1) {
                chosen = pickWeighted(normMix, total, rng);
            }
            out.add(new PlotPlan(p.plotIndex(), p.polygon(), p.centroid(), chosen));
            prev = chosen;
        }
        return out;
    }

    private static CropType pickWeighted(EnumMap<CropType, Float> mix,
                                          float total, Random rng) {
        float pick = rng.nextFloat() * total;
        float cum = 0f;
        CropType last = null;
        for (Map.Entry<CropType, Float> e : mix.entrySet()) {
            last = e.getKey();
            cum += e.getValue();
            if (pick < cum) return last;
        }
        return last;
    }

    private static int pickVertexY(Polygon region) {
        // All region vertices share the same Y (set by the flood-
        // fill caller). Return the first one.
        return region.vertices().isEmpty() ? 0
                : region.vertices().get(0).getY();
    }
}
