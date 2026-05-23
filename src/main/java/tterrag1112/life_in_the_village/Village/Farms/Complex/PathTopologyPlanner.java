package tterrag1112.life_in_the_village.Village.Farms.Complex;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Utilities.Geometry.Polygon;
import tterrag1112.life_in_the_village.Village.Buildings.Complex.PathTopology;

import java.util.ArrayList;
import java.util.List;

/**
 * Detour A — computes a SPINE_WITH_BRANCHES path graph inside a
 * flood-fill region.
 *
 * <p>Output is geometric only — segment list + per-plot entry
 * points + path width metadata. Block placement is deferred to
 * Prompt B's renderer.
 *
 * <p>Stage 2 implementation: straight-line spine from the
 * road-facing edge of the farmhouse to the farthest in-region
 * point from that origin, plus one straight branch per plot
 * connecting the closest spine point to the plot's centroid.
 * Path goodness (avoiding plot interiors, hugging boundaries) is
 * Prompt B's renderer's concern — Stage 2 just produces graph
 * geometry.
 *
 * <p>The spine extends {@link #SPINE_STUB_LENGTH} blocks past the
 * region boundary at its origin — placeholder for the eventual
 * village-road connection point.
 *
 * <p>Other {@link PathTopology} values ({@link PathTopology#RADIAL},
 * {@link PathTopology#MINIMUM_SPANNING_TREE}) throw — they're
 * declared in the spec enum for forward-extensibility but Detour A
 * only ships SPINE_WITH_BRANCHES.
 *
 * <p>Pure function.
 */
public final class PathTopologyPlanner {

    public static final int DEFAULT_PATH_WIDTH = 3;
    /** Branches are narrower than the spine — reads as "side
     *  alley off the main path". Matches the Prompt-B follow-up
     *  width hierarchy: spine 3, branch 2. */
    public static final int DEFAULT_BRANCH_WIDTH = 2;
    public static final int SPINE_STUB_LENGTH = 5;

    private PathTopologyPlanner() {}

    public record Input(
            Polygon region,
            BlockPos roadFacingOrigin,   // seed end of spine, just outside region
            List<PlotPlan> plots,
            PathTopology style) {}

    /** A single straight path segment. {@code isSpine} distinguishes
     *  the trunk from branches for the renderer (different surface,
     *  width, lighting). */
    public record Segment(BlockPos start, BlockPos end, int width, boolean isSpine) {}

    /** Per-plot connection point — the location where the branch
     *  meets the plot boundary; the renderer places a gate there. */
    public record PlotEntry(int plotIndex, BlockPos entry, BlockPos spineAttach) {}

    public record Result(List<Segment> segments,
                          List<PlotEntry> plotEntries,
                          int pathWidth) {}

    public static Result run(Input in) {
        if (in.style() != PathTopology.SPINE_WITH_BRANCHES) {
            throw new UnsupportedOperationException(
                    "Detour A Stage 2 only implements SPINE_WITH_BRANCHES; got " + in.style());
        }
        if (in.region() == null || in.region().vertices().isEmpty()) {
            return new Result(List.of(), List.of(), DEFAULT_PATH_WIDTH);
        }
        BlockPos origin = in.roadFacingOrigin();
        int vertexY = in.region().vertices().get(0).getY();

        // Spine destination: farthest region vertex from origin.
        // Approximation of "deepest into the complex" that doesn't
        // require A* in Stage 2. The renderer can curve later.
        BlockPos farthest = origin;
        double farthestD2 = -1;
        for (BlockPos v : in.region().vertices()) {
            double dx = v.getX() - origin.getX();
            double dz = v.getZ() - origin.getZ();
            double d2 = dx * dx + dz * dz;
            if (d2 > farthestD2) { farthestD2 = d2; farthest = v; }
        }

        // Stub: extend SPINE_STUB_LENGTH blocks in the OPPOSITE
        // direction from origin → farthest at the origin end, so
        // the spine starts outside the region.
        BlockPos stubStart = extendBy(origin, origin, farthest, -SPINE_STUB_LENGTH);

        List<Segment> segments = new ArrayList<>();
        segments.add(new Segment(stubStart, origin, DEFAULT_PATH_WIDTH, true));
        segments.add(new Segment(origin, new BlockPos(
                farthest.getX(), vertexY, farthest.getZ()),
                DEFAULT_PATH_WIDTH, true));

        // Per-plot branch: closest spine point → plot centroid.
        // "Closest spine point" approximated as the projection of
        // the centroid onto the spine segment (origin → farthest).
        List<PlotEntry> entries = new ArrayList<>(in.plots().size());
        for (PlotPlan p : in.plots()) {
            BlockPos c = p.centroid();
            if (c == null) continue;
            BlockPos attach = projectOntoSegment(c, origin, farthest, vertexY);
            BlockPos entry = new BlockPos(c.getX(), vertexY, c.getZ());
            segments.add(new Segment(attach, entry, DEFAULT_BRANCH_WIDTH, false));
            entries.add(new PlotEntry(p.plotIndex(), entry, attach));
        }
        return new Result(segments, entries, DEFAULT_PATH_WIDTH);
    }

    /** Linear extension: from {@code anchor} along direction
     *  (from → to), travel {@code distance} blocks (can be
     *  negative). */
    private static BlockPos extendBy(BlockPos anchor, BlockPos from, BlockPos to,
                                      int distance) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1e-9) return anchor;
        double ux = dx / len, uz = dz / len;
        return new BlockPos(
                anchor.getX() + (int) Math.round(ux * distance),
                anchor.getY(),
                anchor.getZ() + (int) Math.round(uz * distance));
    }

    private static BlockPos projectOntoSegment(BlockPos p, BlockPos a, BlockPos b,
                                                int vertexY) {
        double dx = b.getX() - a.getX();
        double dz = b.getZ() - a.getZ();
        double l2 = dx * dx + dz * dz;
        if (l2 < 1e-9) return new BlockPos(a.getX(), vertexY, a.getZ());
        double t = ((p.getX() - a.getX()) * dx + (p.getZ() - a.getZ()) * dz) / l2;
        t = Math.max(0.0, Math.min(1.0, t));
        int x = (int) Math.round(a.getX() + dx * t);
        int z = (int) Math.round(a.getZ() + dz * t);
        return new BlockPos(x, vertexY, z);
    }
}
