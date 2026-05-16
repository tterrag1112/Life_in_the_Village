package tterrag1112.life_in_the_village.Village.Planning.V2.Harness;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.DropReason;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.DroppedBuilding;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.PlacedBuilding;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.PlacementResult;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer4.RoadNetwork;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer4.RoadSegment;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer5.TerrainAdapter;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Track E1 — computes the seven metric groups from the planner's
 * existing in-memory output. All inputs are already produced by
 * Layers 1-5 of the planner; this class adds no instrumentation
 * inside the planner itself.
 *
 * <p>See the prompt for the precise definitions. Edge cases:
 * <ul>
 *   <li>An aborted run (placed.isEmpty() or !villageViable) produces
 *       {@code NaN} for geometric metrics and zero counts elsewhere
 *       so the diff renderer prints "-" rather than 0.</li>
 *   <li>Compactness with one building has zero bbox area; reported as
 *       1.0 (degenerate but stable).</li>
 *   <li>Per-type clustering coherence is computed only for types with
 *       count ≥ 2; others are absent from the map.</li>
 * </ul>
 */
public final class MetricsComputer {

    private MetricsComputer() {}

    public static RunMetrics compute(Battery.Run run,
                                     List<BuildingType> requestedSelection,
                                     PlacementResult placement,
                                     RoadNetwork roads,
                                     List<TerrainAdapter.AdaptationDecision> terrainDecisions,
                                     long elapsedMs) {
        Map<BuildingType, Integer> requested = countByType(requestedSelection);
        Map<BuildingType, Integer> placedCounts = new EnumMap<>(BuildingType.class);
        placedCounts.putAll(placement.placedCounts());
        boolean aborted = placement.placed().isEmpty() || !placement.villageViable();

        // 1. Placement rate (per-type and overall)
        double placedRate = requestedSelection.isEmpty()
                ? 0.0
                : placement.placed().size() / (double) requestedSelection.size();
        Map<BuildingType, Double> placedRatePerType = new EnumMap<>(BuildingType.class);
        for (Map.Entry<BuildingType, Integer> e : requested.entrySet()) {
            int req = e.getValue();
            int got = placedCounts.getOrDefault(e.getKey(), 0);
            placedRatePerType.put(e.getKey(), req == 0 ? 0.0 : got / (double) req);
        }

        // 2-6. Geometric metrics.
        double compactness = aborted ? Double.NaN : compactness(placement.placed());
        double[] frontage = aborted ? new double[]{Double.NaN, Double.NaN}
                : frontage(placement.placed(), roads);
        int[] conn = aborted ? new int[]{0, 0, 0}
                : connectivity(placement.placed(), roads);
        double connComp = (placement.placed().isEmpty() || conn[2] == 0)
                ? Double.NaN
                : conn[1] / (double) conn[2];

        double terrainViolence = aborted ? Double.NaN
                : terrainViolence(terrainDecisions, placement.placed().size());

        Map<BuildingType, Double> clustering = aborted
                ? new EnumMap<>(BuildingType.class)
                : clusteringCoherence(placement.placed());

        // 7. Drop histogram.
        Map<DropReason, Integer> drops = new EnumMap<>(DropReason.class);
        for (DroppedBuilding d : placement.dropped()) {
            drops.merge(d.reason(), 1, Integer::sum);
        }

        return new RunMetrics(
                run.shape().name(),
                run.config().label(),
                run.config().seed(),
                aborted,
                requestedSelection.size(),
                placement.placed().size(),
                placement.dropped().size(),
                placedRate,
                requested,
                placedCounts,
                placedRatePerType,
                compactness,
                frontage[0],
                frontage[1],
                conn[0],
                connComp,
                terrainViolence,
                0.0,
                clustering,
                drops,
                elapsedMs);
    }

    // =========================================================================
    // Metric 2 — compactness
    // =========================================================================

    private static double compactness(List<PlacedBuilding> placed) {
        if (placed.isEmpty()) return Double.NaN;
        long footprintSum = 0;
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (PlacedBuilding b : placed) {
            footprintSum += (long) b.footprint().width() * b.footprint().length();
            int x = b.centre().getX(), z = b.centre().getZ();
            if (x < minX) minX = x;
            if (x > maxX) maxX = x;
            if (z < minZ) minZ = z;
            if (z > maxZ) maxZ = z;
        }
        long bboxArea = (long)(maxX - minX) * (long)(maxZ - minZ);
        if (bboxArea <= 0) return 1.0;
        return Math.min(1.0, footprintSum / (double) bboxArea);
    }

    // =========================================================================
    // Metric 3 — frontage utilization
    // =========================================================================

    private static double[] frontage(List<PlacedBuilding> placed, RoadNetwork roads) {
        if (placed.isEmpty()) return new double[]{0.0, 0.0};
        List<RoadSegment> segs = roads.skeleton().allSegments();
        if (segs.isEmpty()) return new double[]{0.0, 0.0};

        // frac_buildings_on_network
        int on = 0;
        for (PlacedBuilding b : placed) {
            double setback = b.footprint().length() / 2.0 + 6.0;
            double minD = Double.POSITIVE_INFINITY;
            for (RoadSegment s : segs) {
                double d = distancePointToSegment(b.centre(), s.start(), s.end());
                if (d < minD) minD = d;
            }
            if (minD <= setback) on++;
        }
        double fracOnNetwork = on / (double) placed.size();

        // road_coverage: walk each segment at 1-block steps, count
        // how many sample points have any building within its setback.
        long totalSamples = 0;
        long coveredSamples = 0;
        for (RoadSegment s : segs) {
            int dx = s.end().getX() - s.start().getX();
            int dz = s.end().getZ() - s.start().getZ();
            int len = Math.max(1, (int) Math.ceil(Math.sqrt(dx*dx + dz*dz)));
            for (int t = 0; t <= len; t++) {
                double f = (double) t / len;
                double px = s.start().getX() + dx * f;
                double pz = s.start().getZ() + dz * f;
                boolean covered = false;
                for (PlacedBuilding b : placed) {
                    double setback = b.footprint().length() / 2.0 + 6.0;
                    double bx = b.centre().getX() - px;
                    double bz = b.centre().getZ() - pz;
                    if (Math.sqrt(bx*bx + bz*bz) <= setback) { covered = true; break; }
                }
                totalSamples++;
                if (covered) coveredSamples++;
            }
        }
        double roadCoverage = totalSamples == 0 ? 0.0
                : coveredSamples / (double) totalSamples;
        return new double[]{fracOnNetwork, roadCoverage};
    }

    private static double distancePointToSegment(BlockPos p, BlockPos a, BlockPos b) {
        double px = p.getX(), pz = p.getZ();
        double ax = a.getX(), az = a.getZ();
        double bx = b.getX(), bz = b.getZ();
        double dx = bx - ax, dz = bz - az;
        double l2 = dx*dx + dz*dz;
        if (l2 == 0.0) {
            double ex = px - ax, ez = pz - az;
            return Math.sqrt(ex*ex + ez*ez);
        }
        double t = ((px - ax) * dx + (pz - az) * dz) / l2;
        t = Math.max(0.0, Math.min(1.0, t));
        double cx = ax + t * dx, cz = az + t * dz;
        double ex = px - cx, ez = pz - cz;
        return Math.sqrt(ex*ex + ez*ez);
    }

    // =========================================================================
    // Metric 4 — connectivity (union-find over segment endpoints)
    // =========================================================================

    private static int[] connectivity(List<PlacedBuilding> placed, RoadNetwork roads) {
        List<RoadSegment> segs = roads.skeleton().allSegments();
        if (segs.isEmpty()) return new int[]{0, 0, placed.size()};

        // Union-find on endpoint tokens.
        Map<String, Integer> idx = new HashMap<>();
        int[] parent = new int[segs.size() * 2];
        for (int i = 0; i < parent.length; i++) parent[i] = i;
        int[] segRoot = new int[segs.size()];
        for (int i = 0; i < segs.size(); i++) {
            RoadSegment s = segs.get(i);
            int a = endpointIdx(idx, parent, key(s.start()));
            int b = endpointIdx(idx, parent, key(s.end()));
            union(parent, a, b);
            segRoot[i] = a;
        }

        // Count distinct components by collecting roots used.
        Map<Integer, Integer> compSize = new HashMap<>();
        int[] segComp = new int[segs.size()];
        for (int i = 0; i < segs.size(); i++) {
            int r = find(parent, segRoot[i]);
            segComp[i] = r;
            compSize.merge(r, 1, Integer::sum);
        }
        int components = compSize.size();

        // Main component = the largest-by-segment-count.
        int mainRoot = -1;
        int mainCount = -1;
        for (Map.Entry<Integer, Integer> e : compSize.entrySet()) {
            if (e.getValue() > mainCount) { mainCount = e.getValue(); mainRoot = e.getKey(); }
        }

        // For each placed building, find the nearest segment and check
        // whether it's in the main component.
        int onMain = 0;
        for (PlacedBuilding b : placed) {
            double minD = Double.POSITIVE_INFINITY;
            int nearest = -1;
            for (int i = 0; i < segs.size(); i++) {
                RoadSegment s = segs.get(i);
                double d = distancePointToSegment(b.centre(), s.start(), s.end());
                if (d < minD) { minD = d; nearest = i; }
            }
            if (nearest >= 0 && segComp[nearest] == mainRoot) onMain++;
        }
        return new int[]{components, onMain, placed.size()};
    }

    private static int endpointIdx(Map<String, Integer> idx, int[] parent, String k) {
        Integer existing = idx.get(k);
        if (existing != null) return existing;
        int i = idx.size();
        idx.put(k, i);
        return i;
    }

    private static String key(BlockPos p) { return p.getX() + ":" + p.getZ(); }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) { parent[i] = parent[parent[i]]; i = parent[i]; }
        return i;
    }

    private static void union(int[] parent, int a, int b) {
        int ra = find(parent, a), rb = find(parent, b);
        if (ra != rb) parent[ra] = rb;
    }

    // =========================================================================
    // Metric 5 — terrain violence
    // =========================================================================

    private static double terrainViolence(List<TerrainAdapter.AdaptationDecision> decisions,
                                          int placedCount) {
        if (placedCount == 0) return 0.0;
        int adapted = 0;
        for (TerrainAdapter.AdaptationDecision d : decisions) {
            if (d.mode() == TerrainAdapter.AdaptationMode.LEVEL
                    || d.mode() == TerrainAdapter.AdaptationMode.PLATFORM) {
                adapted++;
            }
        }
        return adapted / (double) placedCount;
    }

    // =========================================================================
    // Metric 6 — clustering coherence
    // =========================================================================

    private static Map<BuildingType, Double> clusteringCoherence(List<PlacedBuilding> placed) {
        Map<BuildingType, List<PlacedBuilding>> byType = new EnumMap<>(BuildingType.class);
        for (PlacedBuilding b : placed) {
            byType.computeIfAbsent(b.type(), k -> new ArrayList<>()).add(b);
        }
        Map<BuildingType, Double> out = new TreeMap<>();
        for (Map.Entry<BuildingType, List<PlacedBuilding>> e : byType.entrySet()) {
            List<PlacedBuilding> sameType = e.getValue();
            if (sameType.size() < 2) continue;
            double meanSame = meanNearestNeighbour(sameType, sameType);
            double meanAny  = meanNearestNeighbour(sameType, placed);
            if (meanAny <= 0.0) continue;
            out.put(e.getKey(), meanSame / meanAny);
        }
        return out;
    }

    private static double meanNearestNeighbour(List<PlacedBuilding> probes,
                                                List<PlacedBuilding> universe) {
        double sum = 0.0;
        int n = 0;
        for (PlacedBuilding p : probes) {
            double min = Double.POSITIVE_INFINITY;
            for (PlacedBuilding u : universe) {
                if (u == p) continue;
                double dx = p.centre().getX() - u.centre().getX();
                double dz = p.centre().getZ() - u.centre().getZ();
                double d = Math.sqrt(dx*dx + dz*dz);
                if (d < min) min = d;
            }
            if (min < Double.POSITIVE_INFINITY) { sum += min; n++; }
        }
        return n == 0 ? 0.0 : sum / n;
    }

    private static Map<BuildingType, Integer> countByType(List<BuildingType> selection) {
        Map<BuildingType, Integer> out = new EnumMap<>(BuildingType.class);
        for (BuildingType t : selection) out.merge(t, 1, Integer::sum);
        return out;
    }
}
