package tterrag1112.life_in_the_village.Village.Planning.V2.Harness;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer4.Junction;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer4.RoadSegment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Track E1 — epsilon-tolerant union-find over road-segment
 * endpoints. Shared by {@link MetricsComputer#connectivity} and
 * {@link ConnectivityAudit} so {@code fracBuildingsOnMainComponent},
 * {@code networkComponents}, and the per-config audit all use the
 * same component classification.
 *
 * <p><b>Why epsilon.</b> The exact-endpoint variant counts the
 * Haufendorf starburst's radial arms as "isolated" because each arm
 * attaches to the central ring at an off-by-one/off-by-two vertex
 * (e.g. arm endpoint at {@code (18,7)} vs ring vertex {@code (17,7)}).
 * The roads physically overlap at width 3 so the village is
 * connected on the ground, but the union-find sees distinct
 * endpoint coords and splits the network into ~7 components,
 * dragging {@code fracBuildingsOnMainComponent} down by ~0.30 even
 * when placement is fine. Epsilon-tolerant union closes that gap.
 *
 * <p><b>EPS = 4 blocks rationale.</b> One block above the road
 * width (3). At EPS=4 a road-width gap is comfortably absorbed —
 * the arm and ring overlap is always ≤ 2 blocks of vertex
 * disagreement in observed dumps — but networks ≥ 5 blocks apart
 * stay distinct, so we don't accidentally bridge unrelated
 * villages. {@link #EPS} can be tuned if a later run shows either
 * cascade.
 *
 * <p><b>Junction-based union.</b> When a {@link Junction#segments()}
 * list is non-degenerate (≥ 2 entries), all listed segments are
 * unioned regardless of endpoint proximity. The current planner's
 * junction array is mostly empty / single-entry, so this is
 * defensive. If a future change populates it, the junction union
 * carries even when EPS would not.
 *
 * <p><b>Behavior.</b> Pure function over the segment list. Reads
 * nothing from the planner / world. The harness-side metric change
 * is the only thing that moves; the planner is unchanged.
 */
public final class ConnectivityUnionFind {

    /** Epsilon threshold in blocks. Two endpoints with Euclidean
     *  distance {@code ≤ EPS} are treated as the same vertex and
     *  their owning segments get unioned. See class javadoc for
     *  rationale on the magnitude. */
    public static final double EPS = 4.0;

    private ConnectivityUnionFind() {}

    /** Per-segment component root array; total component count; root
     *  of the largest-by-segment-count component (-1 if no segments). */
    public record Result(int[] segComp, int components, int mainRoot) {}

    /** Production call: epsilon-tolerant, junction-aware. */
    public static Result compute(List<RoadSegment> segs, List<Junction> junctions) {
        return compute(segs, junctions, EPS);
    }

    /** Strict call: exact endpoint match, no junction unions. Used by
     *  {@link ConnectivityAudit} only — to flag segments that join
     *  main via epsilon as "arm" in the role taxonomy. Not used by
     *  any gated metric. */
    public static Result computeStrict(List<RoadSegment> segs) {
        return compute(segs, /*junctions*/ null, /*eps*/ 0.0);
    }

    public static Result compute(List<RoadSegment> segs, List<Junction> junctions,
                                  double eps) {
        if (segs == null || segs.isEmpty()) {
            return new Result(new int[0], 0, -1);
        }
        int n = segs.size();
        // Endpoint index space: 2*i = start of segs[i], 2*i+1 = end.
        int[] parent = new int[n * 2];
        for (int i = 0; i < parent.length; i++) parent[i] = i;

        // 1. Each segment's two endpoints belong to the same segment.
        for (int i = 0; i < n; i++) union(parent, 2 * i, 2 * i + 1);

        // 2. Cross-segment union by endpoint proximity within EPS.
        BlockPos[] eps_endpoints = new BlockPos[n * 2];
        for (int i = 0; i < n; i++) {
            eps_endpoints[2 * i]     = segs.get(i).start();
            eps_endpoints[2 * i + 1] = segs.get(i).end();
        }
        // O(n²) pairwise. n ≈ 30 in observed dumps; ~1800 comparisons
        // dominated by Math.sqrt — negligible vs the building scan.
        for (int i = 0; i < eps_endpoints.length; i++) {
            for (int j = i + 1; j < eps_endpoints.length; j++) {
                if (within(eps_endpoints[i], eps_endpoints[j], eps)) {
                    union(parent, i, j);
                }
            }
        }

        // 3. Defensive junction-based union — when a Junction lists
        // multiple segments, union them all regardless of endpoint
        // proximity. Currently the planner emits junctions with
        // mostly empty segments lists; this branch is a no-op then.
        if (junctions != null) {
            Map<RoadSegment, Integer> segIdx = new HashMap<>(n * 2);
            for (int i = 0; i < n; i++) segIdx.put(segs.get(i), i);
            for (Junction j : junctions) {
                List<RoadSegment> jSegs = j.segments();
                if (jSegs == null || jSegs.size() < 2) continue;
                Integer first = null;
                for (RoadSegment js : jSegs) {
                    Integer iidx = segIdx.get(js);
                    if (iidx == null) continue;
                    if (first == null) { first = iidx; continue; }
                    union(parent, 2 * first, 2 * iidx);
                }
            }
        }

        // Resolve roots + main-component identity.
        int[] segComp = new int[n];
        Map<Integer, Integer> compSize = new HashMap<>();
        for (int i = 0; i < n; i++) {
            int r = find(parent, 2 * i);
            segComp[i] = r;
            compSize.merge(r, 1, Integer::sum);
        }
        int mainRoot = -1;
        int mainCount = -1;
        for (Map.Entry<Integer, Integer> e : compSize.entrySet()) {
            if (e.getValue() > mainCount) {
                mainCount = e.getValue();
                mainRoot = e.getKey();
            }
        }
        return new Result(segComp, compSize.size(), mainRoot);
    }

    private static boolean within(BlockPos a, BlockPos b, double eps) {
        if (eps <= 0.0) {
            return a.getX() == b.getX() && a.getZ() == b.getZ();
        }
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return (dx * dx + dz * dz) <= eps * eps;
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) { parent[i] = parent[parent[i]]; i = parent[i]; }
        return i;
    }

    private static void union(int[] parent, int a, int b) {
        int ra = find(parent, a), rb = find(parent, b);
        if (ra != rb) parent[ra] = rb;
    }
}
