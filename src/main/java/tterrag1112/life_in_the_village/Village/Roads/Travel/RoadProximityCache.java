package tterrag1112.life_in_the_village.Village.Roads.Travel;

import net.minecraft.world.level.ChunkPos;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadEdge;
import tterrag1112.life_in_the_village.Village.Roads.Graph.WorldRoadGraph;
import tterrag1112.life_in_the_village.World.Atlas.AtlasCell;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Chunk-level cache for mob-spawn fast-reject: "does any maintained road
 * pass near this chunk?"
 *
 * <h3>Design</h3>
 * The cache is built lazily from the road graph on first use (triggered by
 * the first post-cache-clear spawn check), and is invalidated by
 * {@link tterrag1112.life_in_the_village.Events.RoadUpkeepSystem} at the
 * end of each daily maintenance cycle so that newly decayed or improved roads
 * are reflected promptly.
 *
 * <p>Cell paths (not block paths) are used as the spatial source. Each atlas
 * cell is 64×64 blocks; the cache marks every chunk within
 * {@link #CACHE_CHUNK_RADIUS} chunks of the cell centre, which conservatively
 * covers the widest corridor (GREAT_ROAD = 12 blocks) plus the half-cell
 * radius (32 blocks). This produces no false negatives and a small number of
 * false positives in fringe chunks — the in-depth {@link RoadProximityChecker}
 * call resolves those.
 *
 * <h3>Thread safety</h3>
 * All mutations are guarded by {@code LOCK}. Reads use a volatile reference
 * so they never observe a torn write. In practice the server runs single-
 * threaded for game logic, but the guard future-proofs against async I/O paths.
 */
public final class RoadProximityCache {

    /** Max corridor width across all tiers (GREAT_ROAD = 12 blocks). */
    static final int MAX_CORRIDOR_BLOCKS = 12;

    /**
     * Chunk radius marked around each cell centre.
     * ceil((CELL_HALF + MAX_CORRIDOR) / 16) + 1 = ceil((32+12)/16) + 1 = 4.
     */
    private static final int CACHE_CHUNK_RADIUS = 4;

    private static final Object    LOCK     = new Object();
    private static volatile Set<Long> chunks = Collections.emptySet();
    private static volatile boolean   built  = false;

    private RoadProximityCache() {}

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Returns {@code true} if any maintained road may pass near this chunk.
     *
     * <p>A {@code false} result is definitive — no road block is within
     * {@link #MAX_CORRIDOR_BLOCKS} of any position in the chunk, so spawn
     * suppression need not run. A {@code true} result requires the caller to
     * do a precise in-depth check via {@link RoadProximityChecker}.
     *
     * <p>If the cache has not yet been built, returns {@code true}
     * conservatively so no spawns are incorrectly allowed through.
     */
    public static boolean couldChunkBeNearRoad(ChunkPos chunk) {
        if (!built) return true;
        return chunks.contains(packChunk(chunk.x, chunk.z));
    }

    /**
     * Builds (or rebuilds) the cache from the given road graph.
     * Called lazily on the first spawn check after the cache is cleared.
     */
    public static void rebuild(WorldRoadGraph graph) {
        Set<Long> next = new HashSet<>();

        for (RoadEdge edge : graph.allEdges()) {
            if (!isMaintained(edge)) continue;
            if (!edge.isRealized()) continue;

            for (long cellKey : edge.getCellPath()) {
                int cellX = AtlasCell.unpackX(cellKey);
                int cellZ = AtlasCell.unpackZ(cellKey);
                // Block position of cell centre
                int blockCX = (cellX << AtlasCell.CELL_SHIFT) + AtlasCell.CELL_HALF;
                int blockCZ = (cellZ << AtlasCell.CELL_SHIFT) + AtlasCell.CELL_HALF;
                // Chunk containing the cell centre
                int chunkCX = blockCX >> 4;
                int chunkCZ = blockCZ >> 4;
                for (int dx = -CACHE_CHUNK_RADIUS; dx <= CACHE_CHUNK_RADIUS; dx++) {
                    for (int dz = -CACHE_CHUNK_RADIUS; dz <= CACHE_CHUNK_RADIUS; dz++) {
                        next.add(packChunk(chunkCX + dx, chunkCZ + dz));
                    }
                }
            }
        }

        synchronized (LOCK) {
            chunks = next;
            built  = true;
        }
    }

    /**
     * Marks the cache as stale. The next {@link #couldChunkBeNearRoad} call
     * returns {@code true} conservatively; the cache is rebuilt on the next
     * spawn check that reaches the in-depth path.
     *
     * <p>Called by {@code RoadUpkeepSystem.runCycle} after every maintenance
     * cycle so decayed and repaired roads are reflected in the next day's
     * spawn suppression.
     */
    public static void invalidate() {
        synchronized (LOCK) {
            chunks = Collections.emptySet();
            built  = false;
        }
    }

    /** Returns whether the cache has been populated at least once. */
    public static boolean isBuilt() { return built; }

    /** Number of chunk positions marked as road-adjacent (diagnostic). */
    public static int size() { return chunks.size(); }

    // =========================================================================
    // Helpers
    // =========================================================================

    static boolean isMaintained(RoadEdge edge) {
        return edge.getTier() == RoadEdge.EdgeTier.GREAT_ROAD
                || edge.getMaintenance() >= 30;
    }

    private static long packChunk(int cx, int cz) {
        return ((long) cx & 0xFFFFFFFFL) | (((long) cz & 0xFFFFFFFFL) << 32);
    }
}
