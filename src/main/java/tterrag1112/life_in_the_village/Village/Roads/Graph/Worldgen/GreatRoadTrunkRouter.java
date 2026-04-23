package tterrag1112.life_in_the_village.Village.Roads.Graph.Worldgen;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Village.Economy.Trade.AtlasRouteRouter;
import tterrag1112.life_in_the_village.Village.Roads.Graph.Worldgen.GreatRoadAnchorSeeder.AnchorCandidate;
import tterrag1112.life_in_the_village.World.Atlas.WorldAtlas;

import java.util.*;

/**
 * Decides which anchor pairs to connect and routes the trunk between them.
 *
 * <h3>Pairing rules</h3>
 * Anchors on the dominant axis are paired with their nearest axis neighbour
 * within 12 000 blocks — ensuring the primary corridor always spans the world.
 * Off-axis anchors are each paired with their nearest 2 neighbours within
 * 8 000 blocks, producing a small but connected off-spine network.
 *
 * <h3>Routing</h3>
 * Each pair is routed via {@link AtlasRouteRouter#findGreatRoadRoute} which
 * uses a cost profile tuned for ancient roads: high steep penalty, low
 * river-adjacent cost, and a high node budget (12 000 cells).
 *
 * <h3>Separation of concerns</h3>
 * {@link #determinePairs} computes pairs without touching the atlas (suitable
 * for the planning step in {@code GreatRoadGenerationQueue}).
 * {@link #routePair} routes a single pre-determined pair (atlas must already
 * be filled along the corridor).
 * {@link #planTrunks} is a synchronous convenience wrapper that does both.
 */
public final class GreatRoadTrunkRouter {

    private GreatRoadTrunkRouter() {}

    private static final long MAX_AXIS_DIST_SQ    = 12_000L * 12_000L;
    private static final long MAX_OFF_AXIS_DIST_SQ =  8_000L *  8_000L;
    private static final int  OFF_AXIS_NEIGHBORS   = 2;
    private static final int  MIN_PATH_CELLS       = 3;

    // =========================================================================
    // Public records
    // =========================================================================

    public record AnchorPair(
            AnchorCandidate a,
            AnchorCandidate b,
            boolean onDominantAxis
    ) {}

    public record PlannedTrunk(
            AnchorCandidate anchorA,
            AnchorCandidate anchorB,
            List<Long> cellPath,
            float pathCost,
            boolean onDominantAxis
    ) {}

    // =========================================================================
    // Determine pairs (no atlas access required)
    // =========================================================================

    /**
     * Determines which anchor pairs should be connected by a great road trunk.
     * Does NOT perform any routing or atlas sampling.
     *
     * <p>The returned list has no duplicates and is sorted deterministically
     * (by anchorA position).
     */
    public static List<AnchorPair> determinePairs(List<AnchorCandidate> anchors,
                                                  long worldSeed) {
        if (anchors.isEmpty()) return Collections.emptyList();

        List<AnchorCandidate> sorted = new ArrayList<>(anchors);
        sorted.sort(Comparator.comparingInt((AnchorCandidate a) -> a.position().getX())
                .thenComparingInt(a -> a.position().getZ()));

        List<AnchorCandidate> axisAnchors = sorted.stream()
                .filter(AnchorCandidate::onDominantAxis)
                .toList();

        Set<String> seen = new HashSet<>();
        List<AnchorPair> pairs = new ArrayList<>();

        // ── Dominant-axis pairs: each axis anchor → nearest axis neighbour ────
        for (AnchorCandidate a : axisAnchors) {
            AnchorCandidate nearest = null;
            long nearestSq = Long.MAX_VALUE;

            for (AnchorCandidate b : axisAnchors) {
                if (b == a) continue;
                long dsq = distSq(a, b);
                if (dsq <= MAX_AXIS_DIST_SQ && dsq < nearestSq) {
                    nearest = b;
                    nearestSq = dsq;
                }
            }

            if (nearest != null) {
                String key = pairKey(a, nearest);
                if (seen.add(key)) {
                    pairs.add(new AnchorPair(a, nearest, true));
                }
            }
        }

        // ── Off-axis pairs: each anchor → nearest-N neighbours ─────────────
        for (AnchorCandidate a : sorted) {
            // Sort all other anchors by distance to a
            List<AnchorCandidate> others = new ArrayList<>(sorted);
            others.remove(a);
            others.sort(Comparator.comparingLong(b -> distSq(a, b)));

            int connected = 0;
            for (AnchorCandidate b : others) {
                if (connected >= OFF_AXIS_NEIGHBORS) break;
                if (distSq(a, b) > MAX_OFF_AXIS_DIST_SQ) break;

                String key = pairKey(a, b);
                if (seen.add(key)) {
                    boolean axisEdge = a.onDominantAxis() && b.onDominantAxis();
                    pairs.add(new AnchorPair(a, b, axisEdge));
                    connected++;
                }
            }
        }

        // Sort deterministically
        pairs.sort(Comparator.comparingInt((AnchorPair p) -> p.a().position().getX())
                .thenComparingInt(p -> p.a().position().getZ())
                .thenComparingInt(p -> p.b().position().getX())
                .thenComparingInt(p -> p.b().position().getZ()));

        return pairs;
    }

    // =========================================================================
    // Route a single pair (atlas must be pre-filled along the corridor)
    // =========================================================================

    /**
     * Routes a single anchor pair. The atlas must already be sufficiently
     * filled along the A→B corridor before this is called.
     *
     * @return a planned trunk, or {@code null} if routing failed or the
     *         resulting path is degenerate
     */
    public static PlannedTrunk routePair(AnchorCandidate a, AnchorCandidate b,
                                         WorldAtlas atlas, long worldSeed) {
        BlockPos posA = a.position();
        BlockPos posB = b.position();

        List<Long> cellPath = AtlasRouteRouter.findGreatRoadRoute(atlas, posA, posB, worldSeed);

        if (cellPath.isEmpty()) {
            System.out.println("[GreatRoadRouter] No route found: "
                    + posA.toShortString() + " → " + posB.toShortString());
            return null;
        }
        if (cellPath.size() < MIN_PATH_CELLS) {
            System.out.println("[GreatRoadRouter] Degenerate path (" + cellPath.size()
                    + " cells): " + posA.toShortString() + " → " + posB.toShortString());
            return null;
        }

        boolean onAxis = a.onDominantAxis() && b.onDominantAxis();
        // Path cost is approximated as cell count (each cell ≈ 1.5 units on average)
        float pathCost = cellPath.size() * 1.5f;

        return new PlannedTrunk(a, b, cellPath, pathCost, onAxis);
    }

    // =========================================================================
    // Synchronous convenience wrapper (for testing / command use)
    // =========================================================================

    /**
     * Plans all trunks synchronously. Fills the atlas corridor for each pair
     * before routing (one attempt per pair, up to 100 ms).
     *
     * <p>Prefer the cross-tick queue
     * ({@code GreatRoadGenerationQueue}) for production use — this method
     * may cause brief lag spikes when called from the tick loop with many
     * anchor pairs.
     */
    public static List<PlannedTrunk> planTrunks(List<AnchorCandidate> anchors,
                                                 WorldAtlas atlas,
                                                 net.minecraft.server.level.ServerLevel level,
                                                 long worldSeed) {
        List<AnchorPair> pairs = determinePairs(anchors, worldSeed);
        List<PlannedTrunk> trunks = new ArrayList<>();

        for (AnchorPair pair : pairs) {
            // Best-effort atlas fill for the corridor (100 ms budget total)
            fillCorridor(pair.a().position(), pair.b().position(), atlas, level);
            PlannedTrunk trunk = routePair(pair.a(), pair.b(), atlas, worldSeed);
            if (trunk != null) trunks.add(trunk);
        }

        trunks.sort(Comparator.comparingInt((PlannedTrunk t) -> t.anchorA().position().getX())
                .thenComparingInt(t -> t.anchorA().position().getZ()));
        return trunks;
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    /**
     * Synchronously fills the atlas corridor between two positions.
     * Uses the midpoint as the fill centre with radius = half-distance + 500.
     * Retries up to a 100 ms wall-clock deadline.
     */
    static void fillCorridor(BlockPos a, BlockPos b,
                              WorldAtlas atlas,
                              net.minecraft.server.level.ServerLevel level) {
        int mx = (a.getX() + b.getX()) / 2;
        int mz = (a.getZ() + b.getZ()) / 2;
        long dx = b.getX() - a.getX();
        long dz = b.getZ() - a.getZ();
        int halfDist = (int)(Math.sqrt((double)(dx * dx + dz * dz)) / 2.0);
        int radius   = Math.min(halfDist + 500, 8_000);

        long deadline = System.nanoTime() + 100_000_000L; // 100 ms
        while (!atlas.ensureRegionFilled(level, mx, mz, radius, 50_000_000L)
                && System.nanoTime() < deadline) {
            // keep retrying until filled or deadline
        }
    }

    static long distSq(AnchorCandidate a, AnchorCandidate b) {
        long dx = a.position().getX() - b.position().getX();
        long dz = a.position().getZ() - b.position().getZ();
        return dx * dx + dz * dz;
    }

    static String pairKey(AnchorCandidate a, AnchorCandidate b) {
        BlockPos pa = a.position();
        BlockPos pb = b.position();
        if (pa.getX() < pb.getX()
                || (pa.getX() == pb.getX() && pa.getZ() <= pb.getZ())) {
            return pa.getX() + ":" + pa.getZ() + ":" + pb.getX() + ":" + pb.getZ();
        }
        return pb.getX() + ":" + pb.getZ() + ":" + pa.getX() + ":" + pa.getZ();
    }
}
