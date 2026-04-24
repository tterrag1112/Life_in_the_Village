package tterrag1112.life_in_the_village.Village.Roads.Terrain;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;

/**
 * Connected-component vegetation feature detection.
 *
 * <p>Given any block within a tree, mushroom, or bamboo cluster, returns the
 * complete set of positions belonging to that single feature. Used by
 * {@link TerrainClearer} to make "clear the whole tree or none of it" decisions.
 *
 * <h3>Safety caps</h3>
 * Returns an empty set if the feature exceeds 500 blocks (treated as too large
 * to clear automatically) or if the initial seed block is not a recognizable
 * vegetation type.
 */
public final class VegetationFeatureDetector {

    /** Maximum blocks per detected feature before bailing out. */
    private static final int MAX_FEATURE_BLOCKS = 500;
    /** Maximum log-count before a tree is classified MEGA_TREE. */
    private static final int MEGA_TREE_LOG_THRESHOLD = 40;
    /** Radius around each log to search for associated leaves. */
    private static final int LEAF_RADIUS = 5;
    /** Maximum downward search from a leaf seed to find the trunk. */
    private static final int LEAF_TO_TRUNK_SEARCH = 10;
    /** Maximum BFS depth from original seed (safety radius). */
    private static final int MAX_SEARCH_RADIUS = 32;

    public enum FeatureType {
        TREE,           // standard log + leaves
        MEGA_TREE,      // 2×2 or larger trunk (>MEGA_TREE_LOG_THRESHOLD logs)
        MUSHROOM,       // huge mushroom blocks (brown/red cap + stem)
        BAMBOO_CLUSTER, // vertical bamboo column(s)
        NONE
    }

    private VegetationFeatureDetector() {}

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Detects the complete set of blocks belonging to the vegetation feature
     * that contains {@code seed}. Returns an empty set if the seed is not part
     * of any recognizable feature, or if the feature exceeds the safety cap.
     */
    public static Set<BlockPos> detectFeature(ServerLevel level, BlockPos seed) {
        BlockState state = level.getBlockState(seed);

        if (state.is(BlockTags.LOGS)) {
            return detectFromLog(level, seed);
        }
        if (state.is(BlockTags.LEAVES)) {
            return detectFromLeaf(level, seed);
        }
        if (isMushroom(state)) {
            return detectMushroom(level, seed);
        }
        if (isSmallMushroom(state)) {
            return Set.of(seed.immutable());
        }
        if (state.is(Blocks.BAMBOO)) {
            return detectBamboo(level, seed);
        }
        if (state.is(Blocks.BAMBOO_SAPLING)) {
            return Set.of(seed.immutable());
        }
        return Collections.emptySet();
    }

    /**
     * Classifies the feature at {@code seed} without collecting every block.
     * Fast path for callers that only need the type, not the full set.
     */
    public static FeatureType classifyFeature(ServerLevel level, BlockPos seed) {
        BlockState state = level.getBlockState(seed);
        if (state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES)) {
            // Count logs only to determine MEGA vs TREE
            BlockPos logSeed = state.is(BlockTags.LOGS) ? seed : findLog(level, seed);
            if (logSeed == null) return FeatureType.NONE;
            int logCount = countLogs(level, logSeed);
            if (logCount == 0) return FeatureType.NONE;
            return logCount >= MEGA_TREE_LOG_THRESHOLD ? FeatureType.MEGA_TREE : FeatureType.TREE;
        }
        if (isMushroom(state)) return FeatureType.MUSHROOM;
        if (state.is(Blocks.BAMBOO) || state.is(Blocks.BAMBOO_SAPLING)) return FeatureType.BAMBOO_CLUSTER;
        return FeatureType.NONE;
    }

    // =========================================================================
    // Tree detection
    // =========================================================================

    private static Set<BlockPos> detectFromLeaf(ServerLevel level, BlockPos leafSeed) {
        BlockPos log = findLog(level, leafSeed);
        if (log == null) {
            // Isolated leaf — include just this connected leaf cluster
            return floodFillLeaves(level, leafSeed);
        }
        return detectFromLog(level, log);
    }

    private static Set<BlockPos> detectFromLog(ServerLevel level, BlockPos logSeed) {
        // Phase 1: BFS to collect all connected logs
        Set<BlockPos> logs = new HashSet<>();
        Queue<BlockPos> queue = new ArrayDeque<>();
        queue.add(logSeed.immutable());
        logs.add(logSeed.immutable());
        BlockPos origin = logSeed.immutable();

        while (!queue.isEmpty()) {
            if (logs.size() >= MAX_FEATURE_BLOCKS) return Collections.emptySet();
            BlockPos cur = queue.poll();
            // Only follow log-to-log adjacency in the 26 surrounding blocks
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        BlockPos nb = cur.offset(dx, dy, dz);
                        if (logs.contains(nb)) continue;
                        if (distSq(nb, origin) > MAX_SEARCH_RADIUS * MAX_SEARCH_RADIUS) continue;
                        if (level.getBlockState(nb).is(BlockTags.LOGS)) {
                            logs.add(nb.immutable());
                            queue.add(nb.immutable());
                        }
                    }
                }
            }
        }

        // Phase 2: expand to natural leaves within LEAF_RADIUS of any log
        Set<BlockPos> result = new HashSet<>(logs);
        for (BlockPos log : logs) {
            for (int dx = -LEAF_RADIUS; dx <= LEAF_RADIUS; dx++) {
                for (int dy = -LEAF_RADIUS; dy <= LEAF_RADIUS; dy++) {
                    for (int dz = -LEAF_RADIUS; dz <= LEAF_RADIUS; dz++) {
                        if (result.size() >= MAX_FEATURE_BLOCKS) return Collections.emptySet();
                        BlockPos nb = log.offset(dx, dy, dz);
                        if (result.contains(nb)) continue;
                        BlockState s = level.getBlockState(nb);
                        if (!s.is(BlockTags.LEAVES)) continue;
                        // Only claim natural (non-persistent) leaves
                        if (s.hasProperty(BlockStateProperties.PERSISTENT)
                                && s.getValue(BlockStateProperties.PERSISTENT)) continue;
                        // Only claim this leaf if it's closer to one of OUR logs than to any other log
                        if (isClosestOwner(level, nb, logs)) {
                            result.add(nb.immutable());
                        }
                    }
                }
            }
        }
        return result;
    }

    /**
     * Returns true if the nearest log to {@code leaf} is in {@code ownerLogs}.
     * Prevents stealing leaves that belong to an adjacent tree.
     */
    private static boolean isClosestOwner(ServerLevel level, BlockPos leaf, Set<BlockPos> ownerLogs) {
        int ownBestDistSq = Integer.MAX_VALUE;
        for (BlockPos own : ownerLogs) {
            int d = distSq(leaf, own);
            if (d < ownBestDistSq) ownBestDistSq = d;
        }
        // Scan a small cube for any foreign log closer than our best
        int r = LEAF_RADIUS + 1;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos nb = leaf.offset(dx, dy, dz);
                    if (ownerLogs.contains(nb)) continue;
                    if (!level.getBlockState(nb).is(BlockTags.LOGS)) continue;
                    if (distSq(leaf, nb) < ownBestDistSq) return false;
                }
            }
        }
        return true;
    }

    /** Walk downward from a leaf up to LEAF_TO_TRUNK_SEARCH blocks to find a log. */
    private static BlockPos findLog(ServerLevel level, BlockPos leaf) {
        for (int dy = 0; dy >= -LEAF_TO_TRUNK_SEARCH; dy--) {
            BlockPos candidate = leaf.offset(0, dy, 0);
            if (level.getBlockState(candidate).is(BlockTags.LOGS)) return candidate;
        }
        // Also search in the small neighborhood of the leaf
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -LEAF_TO_TRUNK_SEARCH; dy <= 0; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    BlockPos candidate = leaf.offset(dx, dy, dz);
                    if (level.getBlockState(candidate).is(BlockTags.LOGS)) return candidate;
                }
            }
        }
        return null;
    }

    /** Flood-fill only leaves (for isolated leaf clusters without a reachable log). */
    private static Set<BlockPos> floodFillLeaves(ServerLevel level, BlockPos seed) {
        Set<BlockPos> result = new HashSet<>();
        Queue<BlockPos> queue = new ArrayDeque<>();
        queue.add(seed.immutable());
        result.add(seed.immutable());
        while (!queue.isEmpty()) {
            if (result.size() >= MAX_FEATURE_BLOCKS) return Collections.emptySet();
            BlockPos cur = queue.poll();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        BlockPos nb = cur.offset(dx, dy, dz);
                        if (result.contains(nb)) continue;
                        BlockState s = level.getBlockState(nb);
                        if (s.is(BlockTags.LEAVES)
                                && !(s.hasProperty(BlockStateProperties.PERSISTENT)
                                     && s.getValue(BlockStateProperties.PERSISTENT))) {
                            result.add(nb.immutable());
                            queue.add(nb.immutable());
                        }
                    }
                }
            }
        }
        return result;
    }

    /** Count only the connected logs from a log seed (for classification). */
    private static int countLogs(ServerLevel level, BlockPos logSeed) {
        Set<BlockPos> visited = new HashSet<>();
        Queue<BlockPos> queue = new ArrayDeque<>();
        queue.add(logSeed.immutable());
        visited.add(logSeed.immutable());
        while (!queue.isEmpty()) {
            if (visited.size() > MEGA_TREE_LOG_THRESHOLD + 1) return visited.size();
            BlockPos cur = queue.poll();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        BlockPos nb = cur.offset(dx, dy, dz);
                        if (visited.contains(nb)) continue;
                        if (level.getBlockState(nb).is(BlockTags.LOGS)) {
                            visited.add(nb.immutable());
                            queue.add(nb.immutable());
                        }
                    }
                }
            }
        }
        return visited.size();
    }

    // =========================================================================
    // Mushroom detection
    // =========================================================================

    private static Set<BlockPos> detectMushroom(ServerLevel level, BlockPos seed) {
        Set<BlockPos> result = new HashSet<>();
        Queue<BlockPos> queue = new ArrayDeque<>();
        queue.add(seed.immutable());
        result.add(seed.immutable());
        while (!queue.isEmpty()) {
            if (result.size() >= MAX_FEATURE_BLOCKS) return Collections.emptySet();
            BlockPos cur = queue.poll();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        BlockPos nb = cur.offset(dx, dy, dz);
                        if (result.contains(nb)) continue;
                        if (isMushroom(level.getBlockState(nb))) {
                            result.add(nb.immutable());
                            queue.add(nb.immutable());
                        }
                    }
                }
            }
        }
        return result;
    }

    // =========================================================================
    // Bamboo detection
    // =========================================================================

    private static Set<BlockPos> detectBamboo(ServerLevel level, BlockPos seed) {
        Set<BlockPos> result = new HashSet<>();
        // Walk upward
        BlockPos cur = seed.immutable();
        while (level.getBlockState(cur).is(Blocks.BAMBOO) && result.size() < MAX_FEATURE_BLOCKS) {
            result.add(cur);
            cur = cur.above();
        }
        // Walk downward from seed
        cur = seed.below();
        while (level.getBlockState(cur).is(Blocks.BAMBOO) && result.size() < MAX_FEATURE_BLOCKS) {
            result.add(cur.immutable());
            cur = cur.below();
        }
        return result;
    }

    // =========================================================================
    // Classification helpers
    // =========================================================================

    private static boolean isMushroom(BlockState s) {
        return s.is(Blocks.BROWN_MUSHROOM_BLOCK)
                || s.is(Blocks.RED_MUSHROOM_BLOCK)
                || s.is(Blocks.MUSHROOM_STEM);
    }

    private static boolean isSmallMushroom(BlockState s) {
        return s.is(Blocks.BROWN_MUSHROOM)
                || s.is(Blocks.RED_MUSHROOM);
    }

    // =========================================================================
    // Geometry helpers
    // =========================================================================

    private static int distSq(BlockPos a, BlockPos b) {
        int dx = a.getX() - b.getX();
        int dy = a.getY() - b.getY();
        int dz = a.getZ() - b.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    /** Approximate bounding box of a position set as [minX, minY, minZ, maxX, maxY, maxZ]. */
    public static int[] boundingBox(Set<BlockPos> positions) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos p : positions) {
            if (p.getX() < minX) minX = p.getX();
            if (p.getY() < minY) minY = p.getY();
            if (p.getZ() < minZ) minZ = p.getZ();
            if (p.getX() > maxX) maxX = p.getX();
            if (p.getY() > maxY) maxY = p.getY();
            if (p.getZ() > maxZ) maxZ = p.getZ();
        }
        return new int[]{minX, minY, minZ, maxX, maxY, maxZ};
    }

    /** Whether a feature set contains a log (as opposed to only leaves). */
    public static boolean hasTrunk(ServerLevel level, Set<BlockPos> feature) {
        for (BlockPos p : feature) {
            if (level.getBlockState(p).is(BlockTags.LOGS)) return true;
        }
        return false;
    }

    public static final int MAX_FEATURE_BLOCKS_PUBLIC = MAX_FEATURE_BLOCKS;
}
