package tterrag1112.life_in_the_village.Village.Planning.V2.Harness;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.PlacedBuilding;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.PlacementResult;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer4.CrossStreet;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer4.RoadNetwork;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer4.RoadSegment;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer4.Skeleton;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer4.SpineSegment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Track E1 Phase A regression diagnostic — per-config connectivity
 * audit.
 *
 * <p>Mirrors {@code MetricsComputer.connectivity}'s union-find on
 * road-segment endpoints to compute the same "main component" the
 * {@code fracBuildingsOnMainComponent} metric is built on, then
 * exposes per-building attribution + a role/distance summary so we
 * can see WHY the metric regressed under the wider phase-4b band:
 *
 * <ul>
 *   <li>Per building: nearest segment id (S0.. for spine,
 *       X0.. for cross-street), role (spine / cross / stub /
 *       isolated), whether that segment is in the main component,
 *       and the perpendicular distance from the building's centre
 *       to the nearest SpineSegment centerline.</li>
 *   <li>Summary: role histogram, on-main vs off-main, and a
 *       perp-distance distribution (min/median/max) plus tail
 *       counts at the OLD band max (4) and NEW band max (7) so
 *       it's visible how many buildings landed in the freshly-
 *       admitted distance band.</li>
 * </ul>
 *
 * <p>Behavior-neutral — read-only against the planner's in-memory
 * result.
 */
public final class ConnectivityAudit {

    /** Length floor in blocks below which a CrossStreet in the main
     *  component is classified as a "stub" rather than a regular
     *  cross-street. Heuristic; documents the audit's split. */
    private static final double STUB_LENGTH = 5.0;

    public record BuildingRow(
            String buildingType,
            String segmentId,
            String role,
            boolean onMain,
            double perpDistanceToSpine,
            double distanceToNearestSegment) {}

    public record Summary(
            int buildings,
            Map<String, Integer> roleCounts,
            int onMain,
            int offMain,
            double perpMin,
            double perpMedian,
            double perpMax,
            int perpExceedsOldBand,   // > 4 blocks
            int perpExceedsNewBand) { // > 7 blocks
    }

    public record Result(List<BuildingRow> rows, Summary summary) {}

    private ConnectivityAudit() {}

    /** Empty result for aborted/empty configs. */
    public static Result empty() {
        return new Result(List.of(), new Summary(0,
                emptyRoleCounts(), 0, 0, 0, 0, 0, 0, 0));
    }

    private static Map<String, Integer> emptyRoleCounts() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("spine", 0);
        m.put("arm", 0);
        m.put("cross", 0);
        m.put("stub", 0);
        m.put("isolated", 0);
        return m;
    }

    public static Result compute(PlacementResult placement, RoadNetwork roads) {
        if (placement == null || roads == null
                || placement.placed().isEmpty()) {
            return empty();
        }
        Skeleton skeleton = roads.skeleton();
        if (skeleton == null) return empty();
        List<RoadSegment> segs = new ArrayList<>(skeleton.allSegments());
        if (segs.isEmpty()) {
            Map<String, Integer> isolatedOnly = emptyRoleCounts();
            isolatedOnly.put("isolated", placement.placed().size());
            return new Result(List.of(),
                    new Summary(placement.placed().size(),
                            isolatedOnly,
                            0, placement.placed().size(),
                            0, 0, 0, 0, 0));
        }

        // Assign IDs in declaration order: spine segments → S0..,
        // cross-streets → X0.. Same order MetricsComputer iterates.
        String[] segIds = new String[segs.size()];
        int spineIdx = 0, crossIdx = 0;
        for (int i = 0; i < segs.size(); i++) {
            RoadSegment s = segs.get(i);
            if (s instanceof SpineSegment) {
                segIds[i] = "S" + (spineIdx++);
            } else if (s instanceof CrossStreet) {
                segIds[i] = "X" + (crossIdx++);
            } else {
                segIds[i] = s.getClass().getSimpleName().substring(0, 1).toUpperCase()
                        + i;
            }
        }

        // Track E1 — epsilon-tolerant union-find shared with
        // MetricsComputer.connectivity. Per-building on-main split
        // matches the gated metric exactly. Strict union is computed
        // alongside so segments that join main only via epsilon get
        // a distinct "arm" role in the audit.
        ConnectivityUnionFind.Result uf = ConnectivityUnionFind.compute(
                segs, skeleton.junctions());
        int[] segComp = uf.segComp();
        int mainRoot = uf.mainRoot();

        ConnectivityUnionFind.Result strict = ConnectivityUnionFind.computeStrict(segs);
        int[] segCompStrict = strict.segComp();
        int mainRootStrict = strict.mainRoot();

        // Spine segment indices for the perp-to-spine distance.
        List<Integer> spineIndices = new ArrayList<>();
        for (int i = 0; i < segs.size(); i++) {
            if (segs.get(i) instanceof SpineSegment) spineIndices.add(i);
        }

        Map<String, Integer> roleCounts = emptyRoleCounts();

        List<BuildingRow> rows = new ArrayList<>(placement.placed().size());
        double[] perps = new double[placement.placed().size()];
        int onMain = 0, offMain = 0;
        int exceedsOld = 0, exceedsNew = 0;
        int ri = 0;
        for (PlacedBuilding pb : placement.placed()) {
            BlockPos c = pb.centre();
            int nearest = -1;
            double minD = Double.POSITIVE_INFINITY;
            for (int i = 0; i < segs.size(); i++) {
                RoadSegment s = segs.get(i);
                double d = distancePointToSegment(c, s.start(), s.end());
                if (d < minD) { minD = d; nearest = i; }
            }
            boolean main = nearest >= 0 && segComp[nearest] == mainRoot;
            if (main) onMain++; else offMain++;

            // Track E1 — five-way role split. "arm" surfaces the
            // segments that reach the main component only via the
            // epsilon-tolerant union: under strict endpoint match
            // they would have shown as "isolated", but the off-by-N
            // arm-attachment they exhibit physically overlaps the
            // ring at road-width tolerance. Counting these as
            // off-main was the source of the 27/36
            // fracBuildingsOnMainComponent regression.
            String role;
            if (!main) {
                role = "isolated";
            } else if (nearest >= 0
                    && segCompStrict[nearest] != mainRootStrict) {
                role = "arm";
            } else if (segs.get(nearest) instanceof SpineSegment) {
                role = "spine";
            } else if (segs.get(nearest) instanceof CrossStreet) {
                double len = segmentLength(segs.get(nearest));
                role = len < STUB_LENGTH ? "stub" : "cross";
            } else {
                role = "cross";
            }
            roleCounts.merge(role, 1, Integer::sum);

            double perp = Double.POSITIVE_INFINITY;
            for (int si : spineIndices) {
                RoadSegment s = segs.get(si);
                double d = distancePointToSegment(c, s.start(), s.end());
                if (d < perp) perp = d;
            }
            if (Double.isInfinite(perp)) perp = 0.0;
            perps[ri++] = perp;
            if (perp > 4.0) exceedsOld++;
            if (perp > 7.0) exceedsNew++;

            rows.add(new BuildingRow(
                    pb.type().name(),
                    nearest >= 0 ? segIds[nearest] : "-",
                    role,
                    main,
                    perp,
                    minD));
        }

        double[] sorted = Arrays.copyOf(perps, ri);
        Arrays.sort(sorted);
        double pMin = sorted.length == 0 ? 0 : sorted[0];
        double pMax = sorted.length == 0 ? 0 : sorted[sorted.length - 1];
        double pMed = sorted.length == 0 ? 0
                : sorted.length % 2 == 1
                        ? sorted[sorted.length / 2]
                        : (sorted[sorted.length / 2 - 1]
                                + sorted[sorted.length / 2]) / 2.0;

        Summary summary = new Summary(placement.placed().size(),
                roleCounts, onMain, offMain, pMin, pMed, pMax,
                exceedsOld, exceedsNew);
        return new Result(rows, summary);
    }

    private static double segmentLength(RoadSegment s) {
        return distance(s.start(), s.end());
    }

    private static double distance(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static double distancePointToSegment(BlockPos p, BlockPos a, BlockPos b) {
        double px = p.getX(), pz = p.getZ();
        double ax = a.getX(), az = a.getZ();
        double bx = b.getX(), bz = b.getZ();
        double dx = bx - ax, dz = bz - az;
        double l2 = dx * dx + dz * dz;
        if (l2 == 0.0) {
            double ex = px - ax, ez = pz - az;
            return Math.sqrt(ex * ex + ez * ez);
        }
        double t = ((px - ax) * dx + (pz - az) * dz) / l2;
        t = Math.max(0.0, Math.min(1.0, t));
        double cx = ax + t * dx, cz = az + t * dz;
        double ex = px - cx, ez = pz - cz;
        return Math.sqrt(ex * ex + ez * ez);
    }

    /** Render the audit as a fixed-width text block for the per-config
     *  .txt file. Designed to be paste-friendly. */
    public static String render(Result audit) {
        StringBuilder out = new StringBuilder();
        out.append("=== CONNECTIVITY AUDIT ===\n");
        Summary s = audit.summary();
        out.append(String.format("buildings    = %d (on-main=%d off-main=%d)%n",
                s.buildings(), s.onMain(), s.offMain()));
        out.append("roles        = ").append(formatRoleCounts(s.roleCounts())).append('\n');
        out.append(String.format(
                "perpToSpine  = min=%.1f median=%.1f max=%.1f%n",
                s.perpMin(), s.perpMedian(), s.perpMax()));
        out.append(String.format(
                "perpExceeds  = >4 (old band): %d   >7 (new band): %d%n",
                s.perpExceedsOldBand(), s.perpExceedsNewBand()));
        out.append('\n');
        if (audit.rows().isEmpty()) {
            out.append("(no placed buildings)\n");
            return out.toString();
        }
        out.append(String.format(
                "%-14s %-4s %-9s %-6s %-7s %-7s%n",
                "TYPE", "seg", "role", "onMain", "perpSp", "dNear"));
        for (BuildingRow r : audit.rows()) {
            out.append(String.format(
                    "%-14s %-4s %-9s %-6s %-7s %-7s%n",
                    r.buildingType(),
                    r.segmentId(),
                    r.role(),
                    r.onMain() ? "T" : "F",
                    String.format("%.1f", r.perpDistanceToSpine()),
                    String.format("%.1f", r.distanceToNearestSegment())));
        }
        return out.toString();
    }

    private static String formatRoleCounts(Map<String, Integer> roleCounts) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Integer> e : roleCounts.entrySet()) {
            if (!first) sb.append(", ");
            sb.append(e.getKey()).append("=").append(e.getValue());
            first = false;
        }
        return sb.append("}").toString();
    }
}
