package tterrag1112.life_in_the_village.Village.Roads.Graph;

import tterrag1112.life_in_the_village.World.Atlas.AtlasCell;

import java.util.*;

/**
 * Coarse 256-block spatial hash for O(1) "edges near point" queries.
 *
 * <h3>Bucket key encoding</h3>
 * One bucket covers a 256×256 block area (4×4 atlas cells, since
 * {@link AtlasCell#CELL_SHIFT} = 6 → 64 blocks per cell):
 * <pre>
 *   bucketX = blockX &gt;&gt; 8
 *   bucketZ = blockZ &gt;&gt; 8
 *   key = (bucketX &amp; 0xFFFFFFFFL) | ((bucketZ &amp; 0xFFFFFFFFL) &lt;&lt; 32)
 * </pre>
 * X is packed in the low 32 bits; Z in the high 32 bits. This mirrors
 * {@link AtlasCell#packKey} for consistency.
 *
 * <h3>Not persisted</h3>
 * The index is rebuilt from the edge list on every load.
 * {@link WorldRoadGraph#fromCodec} calls {@link WorldRoadGraph#rebuildSpatialIndex}
 * after deserializing edges.
 */
public class EdgeGridIndex {

    static final int BUCKET_SHIFT = 8; // 256 blocks per bucket side

    private final Map<Long, Set<UUID>> bucketToEdges = new HashMap<>();

    // ── Mutation ─────────────────────────────────────────────────────────────

    /**
     * Registers all buckets that this edge's cell path passes through.
     * Idempotent per (bucket, edgeId) pair.
     */
    public void addEdge(RoadEdge edge) {
        for (long cellKey : edge.getCellPath()) {
            long bucket = cellKeyToBucketKey(cellKey);
            bucketToEdges.computeIfAbsent(bucket, k -> new HashSet<>()).add(edge.getEdgeId());
        }
    }

    /**
     * Removes an edge from every bucket it was registered in. O(B) where B
     * is the number of populated buckets — acceptable since removal is rare.
     */
    public void removeEdge(UUID edgeId) {
        for (Set<UUID> set : bucketToEdges.values()) {
            set.remove(edgeId);
        }
    }

    /** Removes all entries. Called before a full index rebuild. */
    public void clear() {
        bucketToEdges.clear();
    }

    // ── Query ────────────────────────────────────────────────────────────────

    /**
     * Returns the UUIDs of all edges whose cell path passes through any
     * bucket that overlaps the circle described by (blockX, blockZ, radiusBlocks).
     * The result is a superset — callers that need exact distance filtering
     * must filter further.
     */
    public Set<UUID> edgesNear(int blockX, int blockZ, int radiusBlocks) {
        int centerBX = blockX >> BUCKET_SHIFT;
        int centerBZ = blockZ >> BUCKET_SHIFT;
        // +1 ensures we always include the bucket the point sits in plus neighbours
        int bucketRadius = (radiusBlocks >> BUCKET_SHIFT) + 1;

        Set<UUID> result = new HashSet<>();
        for (int dx = -bucketRadius; dx <= bucketRadius; dx++) {
            for (int dz = -bucketRadius; dz <= bucketRadius; dz++) {
                long key = bucketKey(centerBX + dx, centerBZ + dz);
                Set<UUID> set = bucketToEdges.get(key);
                if (set != null) result.addAll(set);
            }
        }
        return result;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Converts an {@link AtlasCell#packKey} cell key to the bucket key that
     * contains the centre of that cell.
     */
    private static long cellKeyToBucketKey(long cellKey) {
        int cellX = AtlasCell.unpackX(cellKey);
        int cellZ = AtlasCell.unpackZ(cellKey);
        int blockX = (cellX << AtlasCell.CELL_SHIFT) + AtlasCell.CELL_HALF;
        int blockZ = (cellZ << AtlasCell.CELL_SHIFT) + AtlasCell.CELL_HALF;
        return bucketKey(blockX >> BUCKET_SHIFT, blockZ >> BUCKET_SHIFT);
    }

    private static long bucketKey(int bucketX, int bucketZ) {
        return ((long) bucketX & 0xFFFFFFFFL)
                | (((long) bucketZ & 0xFFFFFFFFL) << 32);
    }
}
