package tterrag1112.life_in_the_village.Village.Roads.Planning;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Village.Economy.Trade.AtlasRouteRouter;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadEdge;
import tterrag1112.life_in_the_village.Village.Roads.Graph.WorldRoadGraph;

import java.util.*;

/**
 * Pure detection of spatially parallel road edge pairs.
 *
 * <p>Two edges are parallel when ≥ {@value #MIN_RUN_LENGTH} consecutive
 * cell-sampled points on edge A each have a nearest sample on edge B
 * closer than {@value #MAX_PARALLEL_DIST_BLOCKS} blocks, forming a
 * contiguous corridor of duplication. GREAT_ROAD edges are excluded.
 */
public final class ParallelismDetector {

    /** Sample one point per this many cells along each edge. */
    private static final int SAMPLE_STRIDE = 4;

    /** Minimum consecutive close-sample pairs to declare parallelism. */
    private static final int MIN_RUN_LENGTH = 6;

    /** Distance threshold (blocks) for a sample pair to be considered "close". */
    private static final int MAX_PARALLEL_DIST_BLOCKS = 120;

    /** Blocks per atlas cell (AtlasCell.CELL_SIZE = 1 << 6). */
    private static final int CELL_BLOCKS = 64;

    private ParallelismDetector() {}

    // =========================================================================
    // Public API
    // =========================================================================

    public record ParallelPair(
            UUID edgeAId,
            UUID edgeBId,
            int overlapStartIndexA,
            int overlapEndIndexA,
            int overlapStartIndexB,
            int overlapEndIndexB,
            int sustainedLengthBlocks
    ) {}

    /**
     * Checks whether two specific edges are parallel to each other.
     *
     * @return the detected pair, or empty if the edges are not parallel or cannot be found
     */
    public static Optional<ParallelPair> findPairBetween(WorldRoadGraph graph,
                                                          UUID edgeAId, UUID edgeBId) {
        RoadEdge edgeA = graph.getEdge(edgeAId);
        RoadEdge edgeB = graph.getEdge(edgeBId);
        if (edgeA == null || edgeB == null) return Optional.empty();
        if (edgeA.getTier() == RoadEdge.EdgeTier.GREAT_ROAD) return Optional.empty();
        if (edgeB.getTier() == RoadEdge.EdgeTier.GREAT_ROAD) return Optional.empty();
        if (edgeA.getCellPath().isEmpty() || edgeB.getCellPath().isEmpty()) return Optional.empty();

        int[][] samplesA = buildSamples(edgeA.getCellPath());
        int[][] samplesB = buildSamples(edgeB.getCellPath());
        if (samplesA.length < MIN_RUN_LENGTH || samplesB.length < MIN_RUN_LENGTH)
            return Optional.empty();

        return Optional.ofNullable(detectParallel(edgeA, edgeB, samplesA, samplesB));
    }

    /**
     * Scans the graph for parallel edge pairs.
     *
     * @param graph           the road graph
     * @param scanRadiusBlocks ignored when {@code scanCenter} is null
     * @param scanCenter      when non-null, only edges near this point are
     *                        examined (uses the graph's spatial index);
     *                        when null the entire graph is scanned
     * @return deduplicated pairs sorted by {@link ParallelPair#sustainedLengthBlocks} descending
     */
    public static List<ParallelPair> findParallelPairs(
            WorldRoadGraph graph,
            int scanRadiusBlocks,
            BlockPos scanCenter) {

        Collection<RoadEdge> candidates = scanCenter == null
                ? graph.allEdges()
                : graph.edgesNear(scanCenter.getX(), scanCenter.getZ(), scanRadiusBlocks);

        List<RoadEdge> filtered = new ArrayList<>();
        for (RoadEdge e : candidates) {
            if (e.getTier() == RoadEdge.EdgeTier.GREAT_ROAD) continue;
            if (e.getCellPath().isEmpty()) continue;
            filtered.add(e);
        }

        List<ParallelPair> results = new ArrayList<>();
        // processedA prevents reporting (A,B) and (B,A) as separate pairs
        Set<UUID> processedA = new HashSet<>();

        for (int i = 0; i < filtered.size(); i++) {
            RoadEdge edgeA = filtered.get(i);
            processedA.add(edgeA.getEdgeId());

            int[][] samplesA = buildSamples(edgeA.getCellPath());
            if (samplesA.length < MIN_RUN_LENGTH) continue;

            for (int j = 0; j < filtered.size(); j++) {
                if (i == j) continue;
                RoadEdge edgeB = filtered.get(j);
                if (processedA.contains(edgeB.getEdgeId())) continue;

                int[][] samplesB = buildSamples(edgeB.getCellPath());
                if (samplesB.length < MIN_RUN_LENGTH) continue;

                ParallelPair pair = detectParallel(edgeA, edgeB, samplesA, samplesB);
                if (pair != null) results.add(pair);
            }
        }

        results.sort(Comparator.comparingInt(ParallelPair::sustainedLengthBlocks).reversed());
        return results;
    }

    // =========================================================================
    // Sampling
    // =========================================================================

    private static int[][] buildSamples(List<Long> cellPath) {
        int count = (cellPath.size() + SAMPLE_STRIDE - 1) / SAMPLE_STRIDE;
        int[][] samples = new int[count][2];
        for (int i = 0; i < count; i++) {
            int cellIdx = Math.min(i * SAMPLE_STRIDE, cellPath.size() - 1);
            BlockPos center = AtlasRouteRouter.cellKeyToBlockCenter(cellPath.get(cellIdx));
            samples[i][0] = center.getX();
            samples[i][1] = center.getZ();
        }
        return samples;
    }

    // =========================================================================
    // Parallel detection for one edge pair
    // =========================================================================

    private static ParallelPair detectParallel(RoadEdge edgeA, RoadEdge edgeB,
                                               int[][] samplesA, int[][] samplesB) {
        int bestRunLength = 0;
        int bestRunStart  = -1;
        int bestRunEnd    = -1;
        int bestBStart    = -1;
        int bestBEnd      = -1;

        int runStart  = -1;
        int runBStart = Integer.MAX_VALUE;
        int runBEnd   = -1;

        for (int ai = 0; ai < samplesA.length; ai++) {
            int nearestBi = nearestSample(samplesA[ai][0], samplesA[ai][1], samplesB);
            long dSq = distSq(samplesA[ai][0], samplesA[ai][1],
                    samplesB[nearestBi][0], samplesB[nearestBi][1]);

            if (dSq < (long) MAX_PARALLEL_DIST_BLOCKS * MAX_PARALLEL_DIST_BLOCKS) {
                if (runStart < 0) {
                    runStart  = ai;
                    runBStart = nearestBi;
                    runBEnd   = nearestBi;
                } else {
                    if (nearestBi < runBStart) runBStart = nearestBi;
                    if (nearestBi > runBEnd)   runBEnd   = nearestBi;
                }
            } else {
                if (runStart >= 0) {
                    int runLen = ai - runStart;
                    if (runLen >= MIN_RUN_LENGTH && runLen > bestRunLength) {
                        bestRunLength = runLen;
                        bestRunStart  = runStart;
                        bestRunEnd    = ai - 1;
                        bestBStart    = runBStart;
                        bestBEnd      = runBEnd;
                    }
                    runStart  = -1;
                    runBStart = Integer.MAX_VALUE;
                    runBEnd   = -1;
                }
            }
        }

        // flush final open run
        if (runStart >= 0) {
            int runLen = samplesA.length - runStart;
            if (runLen >= MIN_RUN_LENGTH && runLen > bestRunLength) {
                bestRunLength = runLen;
                bestRunStart  = runStart;
                bestRunEnd    = samplesA.length - 1;
                bestBStart    = runBStart;
                bestBEnd      = runBEnd;
            }
        }

        if (bestRunLength < MIN_RUN_LENGTH) return null;

        int startCellA   = bestRunStart * SAMPLE_STRIDE;
        int endCellA     = Math.min(bestRunEnd   * SAMPLE_STRIDE, edgeA.getCellPath().size() - 1);
        int startCellB   = bestBStart   * SAMPLE_STRIDE;
        int endCellB     = Math.min(bestBEnd     * SAMPLE_STRIDE, edgeB.getCellPath().size() - 1);
        int lengthBlocks = (endCellA - startCellA) * CELL_BLOCKS;

        return new ParallelPair(
                edgeA.getEdgeId(), edgeB.getEdgeId(),
                startCellA, endCellA,
                startCellB, endCellB,
                lengthBlocks);
    }

    // =========================================================================
    // Geometry helpers
    // =========================================================================

    private static int nearestSample(int x, int z, int[][] samples) {
        int best = 0;
        long bestDist = Long.MAX_VALUE;
        for (int i = 0; i < samples.length; i++) {
            long d = distSq(x, z, samples[i][0], samples[i][1]);
            if (d < bestDist) { bestDist = d; best = i; }
        }
        return best;
    }

    private static long distSq(int x1, int z1, int x2, int z2) {
        long dx = x1 - x2, dz = z1 - z2;
        return dx * dx + dz * dz;
    }
}
