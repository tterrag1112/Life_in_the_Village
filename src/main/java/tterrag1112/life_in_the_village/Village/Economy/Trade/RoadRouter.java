package tterrag1112.life_in_the_village.Village.Economy.Trade;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.*;

public class RoadRouter {

    private static final int   MAX_ITERATIONS  = 100000;
    private static final int   ROAD_HALF_WIDTH  = 2;
    private static final int   SMOOTH_RADIUS    = 2;
    private static final int MAX_PATH_LENGTH    = 10000;
    private static final int MAX_NODES = 200000;  // was 50000

    // Movement costs
    private static final int   MAX_SLOPE        = 1;   // was 2 — tighter
    private static final float COST_FLAT        = 1.0f;
    private static final float COST_GENTLE      = 8.0f;   // was 4 — gentle slope much costlier
    private static final float COST_STEEP       = 80.0f;  // was 20 — steep is very expensive
    private static final float COST_WATER       = 150.0f; // was 80
    private static final float COST_EXISTING    = 0.3f;

    // -------------------------------------------------------------------------
    // Main entry point
    // -------------------------------------------------------------------------

    public static List<BlockPos> findRoad(ServerLevel level,
                                          BlockPos from, BlockPos to) {
        BlockPos start = new BlockPos(from.getX(),
                level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        from.getX(), from.getZ()), from.getZ());
        BlockPos end   = new BlockPos(to.getX(),
                level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        to.getX(), to.getZ()), to.getZ());

        PriorityQueue<Node> open = new PriorityQueue<>(
                Comparator.comparingDouble(n -> n.f));
        Map<Long, Node> allNodes = new HashMap<>();
        Set<Long>       closed   = new HashSet<>();

        Node startNode = new Node(start, null, 0, heuristic(level, start, end));
        open.add(startNode);
        allNodes.put(key(start), startNode);

        int iterations = 0;
        Node bestSoFar = startNode; // track closest node reached

        while (!open.isEmpty() && iterations < MAX_NODES) {
            iterations++;
            Node current = open.poll();

            // Track the node closest to the destination (by heuristic)
            if (current.h < bestSoFar.h) bestSoFar = current;

            if (current.pos.closerThan(end, 8)) {
                List<BlockPos> path = reconstructPath(current);
                // Append the actual end point so the road reaches exactly
                path.add(end);
                return smoothPath(path);
            }

            closed.add(key(current.pos));

            for (BlockPos neighbor : getNeighbors(current.pos)) {
                int surfY = level.getHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        neighbor.getX(), neighbor.getZ());
                BlockPos ns      = new BlockPos(neighbor.getX(), surfY, neighbor.getZ());
                long     nsKey   = key(ns);
                if (closed.contains(nsKey)) continue;

                float moveCost = movementCost(level, current.pos, ns);
                if (moveCost >= COST_WATER * 2) continue;

                float g = current.g + moveCost;
                float h = heuristic(level, ns, end);
                Node existing = allNodes.get(nsKey);
                if (existing == null || g < existing.g) {
                    Node node = new Node(ns, current, g, h);
                    allNodes.put(nsKey, node);
                    open.add(node);
                }
            }
        }

        // Budget exhausted — return the best partial path we found.
        // This reaches as far as the A* got without leaving trenches
        // from an attempted-but-incomplete placement.
        if (bestSoFar.parent != null) {
            System.out.println("RoadRouter: budget exhausted after "
                    + iterations + " nodes — returning partial path ("
                    + bestSoFar.h + " blocks from goal)");
            return smoothPath(reconstructPath(bestSoFar));
        }

        // Absolute fallback — only if we never left the start node
        return fallbackStraightLine(level, start, end);
    }

    // -------------------------------------------------------------------------
    // Road placement
    // -------------------------------------------------------------------------

    public static List<BlockPos> placeRoad(ServerLevel level,
                                           List<BlockPos> path,
                                           RoadQuality quality) {
        List<BlockPos> placed = new ArrayList<>();

        // ── First pass: centre-line + width ──────────────────────────────────
        for (int i = 0; i < path.size(); i++) {
            BlockPos center = path.get(i);
            BlockPos prev   = i > 0               ? path.get(i - 1) : center;
            BlockPos next   = i < path.size() - 1 ? path.get(i + 1) : center;

            // Travel direction (unnormalised)
            int tx = next.getX() - prev.getX();
            int tz = next.getZ() - prev.getZ();

            // True perpendicular: rotate travel 90° → (-tz, tx), then
            // normalise to unit steps so every offset is exactly 1 block.
            // We clamp to [-1, 0, 1] since path nodes are always adjacent.
            int perpX = Integer.signum(-tz);
            int perpZ = Integer.signum(tx);

            // Edge case: if both perp components are 0 (shouldn't happen
            // with a valid path, but guard anyway) default to X axis.
            if (perpX == 0 && perpZ == 0) perpX = 1;

            int centerY = center.getY();

            for (int offset = -ROAD_HALF_WIDTH; offset <= ROAD_HALF_WIDTH; offset++) {
                int wx = center.getX() + perpX * offset;
                int wz = center.getZ() + perpZ * offset;

                clearTreesAt(level, wx, wz, centerY);

                if (offset == 0) {
                    placeRoadBlock(level,
                            new BlockPos(wx, centerY - 1, wz), quality);
                    placed.add(new BlockPos(wx, centerY, wz));
                } else {
                    int sideY = level.getHeight(
                            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, wx, wz);
                    int diff  = Math.abs(sideY - centerY);

                    if (diff <= 1) {
                        placeRoadBlock(level,
                                new BlockPos(wx, centerY - 1, wz), quality);
                        placed.add(new BlockPos(wx, centerY, wz));
                    } else if (diff <= 3) {
                        if (sideY < centerY) {
                            for (int y = sideY; y < centerY; y++) {
                                BlockPos fill = new BlockPos(wx, y, wz);
                                if (level.getBlockState(fill).isAir())
                                    level.setBlock(fill,
                                            Blocks.DIRT.defaultBlockState(), 3);
                            }
                        } else {
                            for (int y = sideY - 1; y >= centerY; y--) {
                                BlockPos carve = new BlockPos(wx, y, wz);
                                BlockState cs  = level.getBlockState(carve);
                                if (cs.is(Blocks.GRASS_BLOCK) || cs.is(Blocks.DIRT)
                                        || cs.is(BlockTags.REPLACEABLE))
                                    level.setBlock(carve,
                                            Blocks.AIR.defaultBlockState(), 3);
                                else break;
                            }
                        }
                        placeRoadBlock(level,
                                new BlockPos(wx, centerY - 1, wz), quality);
                        placed.add(new BlockPos(wx, centerY, wz));
                    }
                    // diff > 3 — too steep, skip this side block
                }
            }
        }

        // ── Second pass: fill diagonal gaps ──────────────────────────────────
        for (int i = 0; i < path.size() - 1; i++) {
            BlockPos current = path.get(i);
            BlockPos next    = path.get(i + 1);
            int dx      = next.getX() - current.getX();
            int dz      = next.getZ() - current.getZ();
            int centerY = current.getY();
            int steps   = Math.max(Math.abs(dx), Math.abs(dz));

            if (steps <= 1) {
                if (Math.abs(dx) == 1 && Math.abs(dz) == 1) {
                    placeRoadBlock(level,
                            new BlockPos(current.getX() + dx, centerY - 1, current.getZ()),
                            quality);
                    placeRoadBlock(level,
                            new BlockPos(current.getX(), centerY - 1, current.getZ() + dz),
                            quality);
                }
                continue;
            }

            for (int s = 1; s < steps; s++) {
                float t  = (float) s / steps;
                int ix   = (int)(current.getX() + t * dx);
                int iz   = (int)(current.getZ() + t * dz);
                int iy   = level.getHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, ix, iz);
                clearTreesAt(level, ix, iz, iy);
                if (Math.abs(iy - centerY) <= 2) {
                    placeRoadBlock(level,
                            new BlockPos(ix, centerY - 1, iz), quality);
                    placed.add(new BlockPos(ix, centerY, iz));
                }
            }
        }

        // ── Third pass: slab transitions where Y changes ─────────────────────
        placeSlabTransitions(level, placed, quality);

        // ── Fourth pass: smooth edges (XZ-protected) ─────────────────────────
        smoothRoadEdges(level, placed);

        return placed;
    }

    /**
     * Where consecutive path nodes change Y by exactly 1, place a half-slab
     * on the uphill side so the transition looks like a ramp rather than a step.
     */
    private static void placeSlabTransitions(ServerLevel level,
                                             List<BlockPos> placed,
                                             RoadQuality quality) {
        if (placed.size() < 2) return;

        BlockState slabState = switch (quality) {
            case COBBLESTONE -> Blocks.COBBLESTONE_SLAB.defaultBlockState();
            case GRAVEL      -> Blocks.COBBLESTONE_SLAB.defaultBlockState(); // no gravel slab
            case DIRT        -> Blocks.OAK_SLAB.defaultBlockState();
        };

        // Build a fast XZ→Y lookup of placed blocks
        Map<Long, Integer> xzToY = new HashMap<>();
        for (BlockPos p : placed) {
            xzToY.put(xzKey(p.getX(), p.getZ()), p.getY());
        }

        for (int i = 1; i < placed.size(); i++) {
            BlockPos prev = placed.get(i - 1);
            BlockPos curr = placed.get(i);

            // Only handle unit steps (adjacent blocks)
            int manhattanXZ = Math.abs(curr.getX() - prev.getX())
                    + Math.abs(curr.getZ() - prev.getZ());
            if (manhattanXZ > 2) continue;

            int dy = curr.getY() - prev.getY();
            if (Math.abs(dy) != 1) continue;

            // The lower block gets the slab — it smooths the step upward
            BlockPos lower   = dy > 0 ? prev : curr;
            // The road block itself is one below the surface pos
            BlockPos roadPos = lower.below();

            BlockState existing = level.getBlockState(roadPos);
            // Only place if the block is already a road material
            if (existing.is(Blocks.COBBLESTONE)
                    || existing.is(Blocks.GRAVEL)
                    || existing.is(Blocks.DIRT_PATH)) {
                level.setBlock(roadPos, slabState, 3);
            }
        }
    }

    // ── Clears logs, leaves, and tall plants in a column ─────────────────────
    private static void clearTreesAt(ServerLevel level,
                                     int x, int z, int fromY) {
        for (int y = fromY + 6; y >= fromY - 1; y--) {
            BlockPos pos   = new BlockPos(x, y, z);
            BlockState state = level.getBlockState(pos);
            if (state.is(BlockTags.LOGS)
                    || state.is(BlockTags.LEAVES)
                    || state.is(BlockTags.REPLACEABLE)
                    || state.is(BlockTags.FLOWERS)
                    || state.is(BlockTags.SMALL_FLOWERS)) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            }
        }
    }

    private static void placeRoadBlock(ServerLevel level,
                                       BlockPos pos,
                                       RoadQuality quality) {
        // Walk down from pos until we find the actual surface
        // in case the heightmap returned a value that's off
        BlockPos roadPos = pos;
        for (int i = 0; i < 4; i++) {
            BlockState current = level.getBlockState(roadPos);
            BlockState above   = level.getBlockState(
                    roadPos.above());

            // Found the right spot — solid block with air above
            if (current.isSolidRender()
                    && !current.is(BlockTags.LOGS)
                    && !current.is(BlockTags.LEAVES)
                    && above.isAir()) {
                break;
            }

            // Air or replaceable — go down one
            if (current.isAir()
                    || current.is(BlockTags.REPLACEABLE)
                    || current.is(BlockTags.FLOWERS)
                    || current.is(BlockTags.SMALL_FLOWERS)) {
                roadPos = roadPos.below();
                continue;
            }

            // Solid but not suitable (log, leaves etc) — skip
            if (current.is(BlockTags.LOGS)
                    || current.is(BlockTags.LEAVES)
                    || current.is(Blocks.BEDROCK)) {
                return;
            }

            break;
        }

        // Clear vegetation one block above
        BlockState above = level.getBlockState(roadPos.above());
        if (above.is(BlockTags.REPLACEABLE)
                || above.is(BlockTags.FLOWERS)
                || above.is(BlockTags.SMALL_FLOWERS)
                || above.is(BlockTags.LEAVES)){
            level.setBlock(roadPos.above(),
                    Blocks.AIR.defaultBlockState(), 3);
        }

        // Never overwrite non-terrain blocks
        BlockState existing = level.getBlockState(roadPos);
        if (existing.is(BlockTags.LOGS)
                || existing.is(BlockTags.LEAVES)
                || existing.is(Blocks.BEDROCK)
                || existing.is(BlockTags.PLANKS)) return;

        BlockState roadState = switch (quality) {
            case COBBLESTONE -> Blocks.COBBLESTONE
                    .defaultBlockState();
            case GRAVEL      -> Blocks.GRAVEL
                    .defaultBlockState();
            case DIRT        -> Blocks.DIRT_PATH
                    .defaultBlockState();
        };

        level.setBlock(roadPos, roadState, 3);
    }
    private static long xzKey(int x, int z) {
        return ((long)(x + 30000000)) << 32 | (z + 30000000);
    }

    private static void smoothRoadEdges(ServerLevel level,
                                        List<BlockPos> roadSurface) {
        // Build a fast lookup of every road-surface XZ coordinate
        // keyed only on X/Z so we can check any Y at that column.
        Set<Long> roadXZ = new HashSet<>();
        for (BlockPos p : roadSurface) {
            roadXZ.add(xzKey(p.getX(), p.getZ()));
        }

        for (BlockPos pos : roadSurface) {
            int roadY = pos.getY();

            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;

                    int nx = pos.getX() + dx;
                    int nz = pos.getZ() + dz;

                    // Never touch a column that is itself part of the road
                    if (roadXZ.contains(xzKey(nx, nz))) continue;

                    int neighborY = level.getHeight(
                            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                            nx, nz);

                    int jump = neighborY - roadY;

                    if (jump > 1) {
                        // Neighbour is higher — carve the top natural block only
                        BlockPos carve = new BlockPos(nx, neighborY - 1, nz);
                        BlockState cs  = level.getBlockState(carve);
                        if (cs.is(Blocks.GRASS_BLOCK)
                                || cs.is(Blocks.DIRT)
                                || cs.is(Blocks.COARSE_DIRT)
                                || cs.is(BlockTags.REPLACEABLE)) {
                            level.setBlock(carve,
                                    Blocks.AIR.defaultBlockState(), 3);
                            // Cap the newly exposed block with grass if it's dirt
                            BlockPos newTop = carve.below();
                            if (level.getBlockState(newTop).is(Blocks.DIRT)) {
                                level.setBlock(newTop,
                                        Blocks.GRASS_BLOCK.defaultBlockState(), 3);
                            }
                        }
                    } else if (jump < -1) {
                        // Neighbour is lower — fill the gap with a single dirt block
                        BlockPos fill = new BlockPos(nx, neighborY, nz);
                        if (level.getBlockState(fill).isAir()) {
                            level.setBlock(fill,
                                    Blocks.GRASS_BLOCK.defaultBlockState(), 3);
                        }
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Road degradation and repair
    // -------------------------------------------------------------------------

    public static void degradeRoad(ServerLevel level,
                                   List<BlockPos> roadBlocks,
                                   int quality) {
        if (quality > 66) return;

        Random rng = new Random();
        float degradeChance = quality > 33 ? 0.1f : 0.3f;

        for (BlockPos pos : roadBlocks) {
            if (rng.nextFloat() > degradeChance) continue;

            BlockState state = level.getBlockState(pos);
            if (state.is(Blocks.COBBLESTONE)) {
                level.setBlock(pos,
                        Blocks.GRAVEL.defaultBlockState(), 3);
            } else if (state.is(Blocks.GRAVEL)) {
                level.setBlock(pos,
                        Blocks.DIRT_PATH.defaultBlockState(),
                        3);
            } else if (state.is(Blocks.DIRT_PATH)) {
                level.setBlock(pos,
                        Blocks.DIRT.defaultBlockState(), 3);
            }
        }
    }

    public static void repairRoad(ServerLevel level,
                                  List<BlockPos> roadBlocks) {
        for (BlockPos pos : roadBlocks) {
            BlockState state = level.getBlockState(pos);
            if (state.is(Blocks.GRAVEL)
                    || state.is(Blocks.DIRT_PATH)
                    || state.is(Blocks.DIRT)) {
                level.setBlock(pos,
                        Blocks.COBBLESTONE.defaultBlockState(),
                        3);
            }
        }
    }

    // -------------------------------------------------------------------------
    // A* helpers
    // -------------------------------------------------------------------------

    private static List<BlockPos> getNeighbors(BlockPos pos) {
        List<BlockPos> neighbors = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                // Y doesn't matter here — always overwritten
                // by heightmap lookup in findRoad
                neighbors.add(new BlockPos(
                        pos.getX() + dx, 0,
                        pos.getZ() + dz));
            }
        }
        return neighbors;
    }

    private static float movementCost(ServerLevel level,
                                      BlockPos from, BlockPos to) {
        int dy = Math.abs(to.getY() - from.getY());

        BlockState toState = level.getBlockState(to.below());
        if (toState.liquid()) return COST_WATER;

        if (toState.is(Blocks.COBBLESTONE)
                || toState.is(Blocks.GRAVEL)
                || toState.is(Blocks.DIRT_PATH)) {
            return COST_EXISTING;
        }

        boolean diagonal = from.getX() != to.getX()
                && from.getZ() != to.getZ();
        float baseCost = diagonal ? 1.414f : 1.0f;

        if (dy == 0)    return baseCost * COST_FLAT;

        // Exponential penalty for every block of rise: hugs valleys
        if (dy > MAX_SLOPE) {
            return baseCost * COST_STEEP * (float)Math.pow(dy, 3);
        }

        return baseCost * COST_GENTLE * dy;
    }

    private static float heuristic(ServerLevel level,
                                   BlockPos from,
                                   BlockPos to) {
        float dx = Math.abs(from.getX() - to.getX());
        float dz = Math.abs(from.getZ() - to.getZ());

        // Octile distance
        float octile = Math.max(dx, dz)
                + (float)(Math.sqrt(2) - 1)
                * Math.min(dx, dz);

        // Sample midpoint terrain to bias away from mountains
        int midX = (from.getX() + to.getX()) / 2;
        int midZ = (from.getZ() + to.getZ()) / 2;
        int midY = level.getHeight(
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, midX, midZ);
        int dy = Math.abs(midY - from.getY());

        return octile + dy * 4.0f;
    }

    private static List<BlockPos> reconstructPath(Node end) {
        List<BlockPos> path = new ArrayList<>();
        Node current = end;
        while (current != null) {
            path.add(0, current.pos);
            current = current.parent;
        }
        return path;
    }

    private static List<BlockPos> smoothPath(
            List<BlockPos> path) {
        if (path.size() <= 2) return path;

        List<BlockPos> result = new ArrayList<>();
        result.add(path.get(0));

        for (int i = 1; i < path.size() - 1; i++) {
            BlockPos prev = result.get(result.size() - 1);
            BlockPos curr = path.get(i);
            BlockPos next = path.get(i + 1);

            int dxIn  = Integer.signum(
                    curr.getX() - prev.getX());
            int dzIn  = Integer.signum(
                    curr.getZ() - prev.getZ());
            int dxOut = Integer.signum(
                    next.getX() - curr.getX());
            int dzOut = Integer.signum(
                    next.getZ() - curr.getZ());
            int dyIn  = curr.getY() - prev.getY();
            int dyOut = next.getY() - curr.getY();

            // Keep node if direction or slope changes
            if (dxIn != dxOut || dzIn != dzOut
                    || Math.abs(dyIn - dyOut) > 1) {
                result.add(curr);
            }
        }

        result.add(path.get(path.size() - 1));
        return result;
    }

    private static List<BlockPos> fallbackStraightLine(
            ServerLevel level, BlockPos from, BlockPos to) {
        List<BlockPos> path = new ArrayList<>();
        int steps = Math.max(
                Math.abs(to.getX() - from.getX()),
                Math.abs(to.getZ() - from.getZ()));
        if (steps == 0) return path;

        for (int i = 0; i <= steps; i++) {
            float t = (float) i / steps;
            int x = (int)(from.getX()
                    + t * (to.getX() - from.getX()));
            int z = (int)(from.getZ()
                    + t * (to.getZ() - from.getZ()));
            path.add(surfacePos(level,
                    new BlockPos(x, 64, z)));
        }
        return path;
    }

    private static BlockPos surfacePos(ServerLevel level,
                                       BlockPos pos) {
        int y = level.getHeight(
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                pos.getX(), pos.getZ());
        return new BlockPos(pos.getX(), y, pos.getZ());
    }

    private static long key(BlockPos pos) {
        return ((long)(pos.getX() + 30000000)) << 32
                | (pos.getZ() + 30000000);
    }

    // -------------------------------------------------------------------------
    // Supporting types
    // -------------------------------------------------------------------------

    private static class Node {
        final BlockPos pos;
        final Node parent;
        final float g;
        final float h;
        final float f;

        Node(BlockPos pos, Node parent, float g, float h) {
            this.pos    = pos;
            this.parent = parent;
            this.g      = g;
            this.h      = h;
            this.f      = g + h;
        }
    }

    public enum RoadQuality {
        COBBLESTONE,
        GRAVEL,
        DIRT
    }
}