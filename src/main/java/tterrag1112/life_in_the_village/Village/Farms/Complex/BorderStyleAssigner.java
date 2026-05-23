package tterrag1112.life_in_the_village.Village.Farms.Complex;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Utilities.Geometry.Polygon;
import tterrag1112.life_in_the_village.Village.Buildings.Complex.BorderStyleId;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Detour A — assigns a {@link BorderStyleId} to every plot edge.
 *
 * <p>Two-step:
 * <ol>
 *   <li>Per plot: pick one "primary" style from the spec's
 *       border-style pool via weighted-random (currently uniform
 *       — weights aren't specified yet).</li>
 *   <li>Per edge: if the edge is shared between two plots, use
 *       the lower-{@code plotIndex} plot's primary (deterministic
 *       tiebreak). Otherwise (outer perimeter or path crossing)
 *       use the plot's own primary.</li>
 * </ol>
 *
 * <p>Output: per-plot map from edge id (encoded as a packed
 * {@code (vertexIndexA, vertexIndexB)} into a long) to
 * {@link BorderStyleId}. Stage 4 / Prompt B turns these into
 * block recipes.
 *
 * <p>Gate detection (where a path crosses a plot edge) is
 * Prompt B's geometry concern; Stage 2 outputs only the style
 * map.
 *
 * <p>Pure function — same plots + pool + seed produce same
 * assignment.
 */
public final class BorderStyleAssigner {

    private BorderStyleAssigner() {}

    public record Input(List<PlotPlan> plots,
                         List<BorderStyleId> stylePool,
                         long seed) {}

    /** Per-plot result: {@code primary} is the plot's chosen style;
     *  {@code edgeStyles} maps each polygon edge (keyed by start-
     *  vertex index) to the resolved style after shared-edge
     *  tiebreak. */
    public record PlotBorders(int plotIndex,
                               BorderStyleId primary,
                               Map<Integer, BorderStyleId> edgeStyles) {}

    public record Result(List<PlotBorders> perPlot) {}

    public static Result run(Input in) {
        if (in.plots().isEmpty() || in.stylePool().isEmpty()) {
            return new Result(List.of());
        }
        Random rng = new Random(in.seed());

        // 1. Pick a primary per plot.
        Map<Integer, BorderStyleId> primary = new HashMap<>();
        for (PlotPlan p : in.plots()) {
            BorderStyleId pick = in.stylePool().get(
                    rng.nextInt(in.stylePool().size()));
            primary.put(p.plotIndex(), pick);
        }

        // 2. Resolve per-edge style with shared-edge tiebreak.
        // Build an index from edge geometry (start, end vertex
        // coords as a packed key) to the lowest plotIndex that
        // claims it. We only need ENDPOINT coords because polygon
        // edges between two plots share their endpoint coords on
        // both sides.
        Map<Long, Integer> edgeOwner = new HashMap<>();
        for (PlotPlan p : in.plots()) {
            List<BlockPos> verts = p.polygon().vertices();
            int n = verts.size();
            for (int i = 0; i < n; i++) {
                BlockPos a = verts.get(i);
                BlockPos b = verts.get((i + 1) % n);
                long key = edgeKey(a, b);
                edgeOwner.merge(key, p.plotIndex(),
                        (existing, candidate) -> Math.min(existing, candidate));
            }
        }

        List<PlotBorders> out = new java.util.ArrayList<>(in.plots().size());
        for (PlotPlan p : in.plots()) {
            BorderStyleId own = primary.get(p.plotIndex());
            Map<Integer, BorderStyleId> styles = new HashMap<>();
            List<BlockPos> verts = p.polygon().vertices();
            int n = verts.size();
            for (int i = 0; i < n; i++) {
                BlockPos a = verts.get(i);
                BlockPos b = verts.get((i + 1) % n);
                long key = edgeKey(a, b);
                int owner = edgeOwner.getOrDefault(key, p.plotIndex());
                BorderStyleId ownerStyle = primary.getOrDefault(owner, own);
                styles.put(i, ownerStyle);
            }
            out.add(new PlotBorders(p.plotIndex(), own, styles));
        }
        return new Result(out);
    }

    /** Order-independent edge key — same coords in either direction
     *  produce the same key. */
    private static long edgeKey(BlockPos a, BlockPos b) {
        long ka = ((long) a.getX() << 32) | (a.getZ() & 0xFFFFFFFFL);
        long kb = ((long) b.getX() << 32) | (b.getZ() & 0xFFFFFFFFL);
        long lo = Math.min(ka, kb);
        long hi = Math.max(ka, kb);
        return lo * 1_000_003L ^ hi;
    }
}
