// src/main/java/tterrag1112/life_in_the_village/Village/Economy/Trade/RoadRouter.java
package tterrag1112.life_in_the_village.Village.Economy.Trade;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.*;

/**
 * Finds terrain-aware paths between two points using A* and places
 * road blocks along them.
 *
 * <h3>Y-coordinate convention</h3>
 * <p>{@code MOTION_BLOCKING_NO_LEAVES} heightmap returns the Y of the
 * first motion-blocking block from the top — the solid surface block itself,
 * NOT the air above it. Throughout this class:
 * <ul>
 *   <li>{@code surfaceY} / {@code centerY} = solid block Y (the block to replace)</li>
 *   <li>{@code surfaceY + 1} = the air block above = the "walkable" position
 *       stored in {@code placed}</li>
 *   <li>{@code placeRoadBlock(pos)} receives the solid block position directly</li>
 * </ul>
 *
 * <p>{@code placed} always contains the air-block-above-road positions.
 * {@code degradeRoad} and {@code repairRoad} call {@code pos.below()} to
 * reach the actual road block.
 */
public class RoadRouter {

    // ── Pathfinding ───────────────────────────────────────────────────────────
    private static final int MAX_NODES = 500_000;
    private static final int   ROAD_HALF_WIDTH = 2;      // 5 blocks wide total

    // ── Slope costs — exponential penalty keeps routes in valleys ────────────
    private static final int   MAX_SLOPE       = 1;
    private static final float COST_FLAT       = 1.0f;
    private static final float COST_GENTLE     = 8.0f;
    private static final float COST_STEEP      = 80.0f;
    private static final float COST_WATER      = 150.0f;
    private static final float COST_EXISTING   = 0.3f;

    // =========================================================================
    // A* pathfinding
    // =========================================================================

    public static List<BlockPos> findRoad(ServerLevel level,
                                          BlockPos from, BlockPos to) {
        // Snap both endpoints to solid surface
        BlockPos start = solidSurface(level, from);
        BlockPos end   = solidSurface(level, to);

        PriorityQueue<Node> open = new PriorityQueue<>(
                Comparator.comparing(n -> n.f));
        Map<Long, Node> allNodes = new HashMap<>();
        Set<Long>       closed   = new HashSet<>();

        Node startNode = new Node(start, null, 0f, heuristic(level, start, end));
        open.add(startNode);
        allNodes.put(key(start), startNode);

        int  iterations = 0;
        Node bestSoFar  = startNode;

        while (!open.isEmpty() && iterations < MAX_NODES) {
            iterations++;
            Node current = open.poll();

            if (current.h < bestSoFar.h) bestSoFar = current;

            // Close enough to the goal — reconstruct and return
            if (current.pos.closerThan(end, 6)) {
                List<BlockPos> path = reconstructPath(current);
                path.add(end);
                return densify(path, level);
            }

            closed.add(key(current.pos));

            for (BlockPos nb : getNeighbors(current.pos)) {
                BlockPos ns    = solidSurface(level, nb);
                long     nsKey = key(ns);
                if (closed.contains(nsKey)) continue;

                float moveCost = movementCost(level, current.pos, ns);
                if (moveCost >= COST_WATER * 2f) continue;

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

        // Budget exhausted — return best partial path, no fallback straight line
        if (bestSoFar.parent != null) {
            System.out.println("RoadRouter: budget exhausted ("
                    + iterations + " nodes), partial path "
                    + (int)bestSoFar.h + " blocks short");
            return densify(reconstructPath(bestSoFar), level);
        }

        return Collections.emptyList();
    }

    public static List<BlockPos> findRoad(ServerLevel level,
                                          BlockPos from,
                                          BlockPos to,
                                          int maxNodes) {
        // Identical to findRoad(level, from, to) but uses maxNodes
        // instead of the MAX_NODES constant.
        // Copy the existing findRoad body here, replacing MAX_NODES with maxNodes.
        BlockPos start = solidSurface(level, from);
        BlockPos end   = solidSurface(level, to);

        PriorityQueue<Node> open = new PriorityQueue<>(
                Comparator.comparing(n -> n.f));
        Map<Long, Node> allNodes = new HashMap<>();
        Set<Long>       closed   = new HashSet<>();

        Node startNode = new Node(start, null, 0f, heuristic(level, start, end));
        open.add(startNode);
        allNodes.put(key(start), startNode);

        int  iterations = 0;
        Node bestSoFar  = startNode;

        while (!open.isEmpty() && iterations < maxNodes) {
            iterations++;
            Node current = open.poll();

            if (current.h < bestSoFar.h) bestSoFar = current;

            if (current.pos.closerThan(end, 6)) {
                List<BlockPos> path = reconstructPath(current);
                path.add(end);
                return densify(path, level);
            }

            closed.add(key(current.pos));

            for (BlockPos nb : getNeighbors(current.pos)) {
                BlockPos ns    = solidSurface(level, nb);
                long     nsKey = key(ns);
                if (closed.contains(nsKey)) continue;

                float moveCost = movementCost(level, current.pos, ns);
                if (moveCost >= COST_WATER * 2f) continue;

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

        if (bestSoFar.parent != null) {
            System.out.println("RoadRouter: budget exhausted ("
                    + iterations + " nodes), partial path "
                    + (int)bestSoFar.h + " blocks short");
            return densify(reconstructPath(bestSoFar), level);
        }

        return Collections.emptyList();
    }

    // =========================================================================
    // Road placement
    // =========================================================================

    /**
     * Places road blocks along {@code path} and returns the list of
     * <b>air-block-above-road</b> positions (the "surface" positions).
     *
     * <p>Three passes:
     * <ol>
     *   <li>Centre-line + perpendicular width — the main road surface</li>
     *   <li>Fill diagonal gaps between A* nodes</li>
     *   <li>Slab transitions where road steps up/down by 1 block</li>
     *   <li>Edge smoothing of adjacent terrain</li>
     * </ol>
     */
    public static List<BlockPos> placeRoad(ServerLevel level,
                                           List<BlockPos> path,
                                           RoadQuality quality) {
        if (path.isEmpty()) return Collections.emptyList();

        // Track placed surface (air) positions and their XZ for edge smoothing
        List<BlockPos> placed   = new ArrayList<>();
        Set<Long>      placedXZ = new HashSet<>();

        // ── Pass 1: centre-line + width ───────────────────────────────────────
        for (int i = 0; i < path.size(); i++) {
            BlockPos center = path.get(i);
            BlockPos prev   = i > 0             ? path.get(i - 1) : center;
            BlockPos next   = i < path.size()-1 ? path.get(i + 1) : center;

            int tx = next.getX() - prev.getX();
            int tz = next.getZ() - prev.getZ();

            // True 90° perpendicular
            int perpX = Integer.signum(-tz);
            int perpZ = Integer.signum(tx);
            if (perpX == 0 && perpZ == 0) perpX = 1;

            // centerY = solid block Y (MOTION_BLOCKING_NO_LEAVES convention)
            int centerY = center.getY();

            for (int offset = -ROAD_HALF_WIDTH; offset <= ROAD_HALF_WIDTH; offset++) {
                int wx = center.getX() + perpX * offset;
                int wz = center.getZ() + perpZ * offset;

                clearTreesAt(level, wx, wz, centerY);

                if (offset == 0) {
                    // Centre block — place directly at centerY (the solid block)
                    placeRoadBlock(level, new BlockPos(wx, centerY, wz), quality);
                    BlockPos surface = new BlockPos(wx, centerY + 1, wz);
                    placed.add(surface);
                    placedXZ.add(xzKey(wx, wz));
                } else {
                    // Side block — re-query surface at this XZ
                    int sideY = level.getHeight(
                            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, wx, wz);
                    int diff  = Math.abs(sideY - centerY);

                    if (diff <= 1) {
                        placeRoadBlock(level, new BlockPos(wx, sideY, wz), quality);
                        placed.add(new BlockPos(wx, sideY + 1, wz));
                        placedXZ.add(xzKey(wx, wz));
                    } else if (diff <= 3) {
                        if (sideY < centerY) {
                            // Fill ground up to match centre level
                            for (int y = sideY + 1; y < centerY; y++) {
                                BlockPos fill = new BlockPos(wx, y, wz);
                                if (level.getBlockState(fill).isAir()) {
                                    level.setBlock(fill,
                                            Blocks.DIRT.defaultBlockState(), 3);
                                }
                            }
                            placeRoadBlock(level,
                                    new BlockPos(wx, centerY, wz), quality);
                            placed.add(new BlockPos(wx, centerY + 1, wz));
                        } else {
                            // Carve down to centre level
                            for (int y = sideY; y > centerY; y--) {
                                BlockPos carve = new BlockPos(wx, y, wz);
                                BlockState cs  = level.getBlockState(carve);
                                if (cs.is(Blocks.GRASS_BLOCK) || cs.is(Blocks.DIRT)
                                        || cs.is(Blocks.COARSE_DIRT)
                                        || cs.is(BlockTags.REPLACEABLE)) {
                                    level.setBlock(carve,
                                            Blocks.AIR.defaultBlockState(), 3);
                                } else break;
                            }
                            placeRoadBlock(level,
                                    new BlockPos(wx, centerY, wz), quality);
                            placed.add(new BlockPos(wx, centerY + 1, wz));
                        }
                        placedXZ.add(xzKey(wx, wz));
                    }
                    // diff > 3 — too steep for side block, skip
                }
            }
        }

        // ── Pass 2: fill diagonal gaps between A* nodes ───────────────────────
        for (int i = 0; i < path.size() - 1; i++) {
            BlockPos cur  = path.get(i);
            BlockPos next = path.get(i + 1);
            int dx = next.getX() - cur.getX();
            int dz = next.getZ() - cur.getZ();
            int steps = Math.max(Math.abs(dx), Math.abs(dz));

            if (steps <= 1) {
                // Standard diagonal — fill the two cardinal neighbours
                if (Math.abs(dx) == 1 && Math.abs(dz) == 1) {
                    int y = cur.getY();
                    clearTreesAt(level, cur.getX() + dx, cur.getZ(), y);
                    clearTreesAt(level, cur.getX(), cur.getZ() + dz, y);
                    placeRoadBlock(level,
                            new BlockPos(cur.getX() + dx, y, cur.getZ()), quality);
                    placeRoadBlock(level,
                            new BlockPos(cur.getX(), y, cur.getZ() + dz), quality);
                }
                continue;
            }

            // Multi-step gap — interpolate
            for (int s = 1; s < steps; s++) {
                float t  = (float) s / steps;
                int ix   = (int)(cur.getX() + t * dx);
                int iz   = (int)(cur.getZ() + t * dz);
                int iy   = level.getHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, ix, iz);
                clearTreesAt(level, ix, iz, iy);
                if (Math.abs(iy - cur.getY()) <= 3) {
                    placeRoadBlock(level, new BlockPos(ix, iy, iz), quality);
                    placed.add(new BlockPos(ix, iy + 1, iz));
                    placedXZ.add(xzKey(ix, iz));
                }
            }
        }

        // ── Pass 3: slab transitions where surface steps by exactly 1 ────────
        placeSlabTransitions(level, placed, quality);

        // ── Pass 4: smooth terrain immediately adjacent to road ───────────────
        smoothRoadEdges(level, placed, placedXZ);

        return placed;
    }
    // =========================================================================
    // Bridge placement (Phase 7b)
    // =========================================================================

    /**
     * Places a simple plank deck bridge spanning from {@code entrance}
     * to {@code exit}. Both endpoints should be on dry land at the
     * shoreline of a water body.
     *
     * <h3>Bridge style</h3>
     * Phase 7b uses the simplest possible bridge: a single layer of
     * oak planks at a fixed deck height (one block above the higher
     * of the two endpoint Ys), spanning straight across the water,
     * width {@link #ROAD_HALF_WIDTH * 2 + 1} blocks. No railings, no
     * supports, no decoration. This is intentional placeholder code —
     * future phases will replace it with structure-based bridges
     * loaded from NBT files for visual variety.
     *
     * <p>The deck is placed even if the path passes over solid blocks
     * for part of the span; the only check is that the deck height is
     * above the water surface.
     *
     * @return list of placed deck positions (the air position above
     *         each plank, matching the convention used by
     *         {@link #placeRoad})
     */
    public static List<BlockPos> placeBridge(ServerLevel level,
                                             BlockPos entrance,
                                             BlockPos exit) {
        List<BlockPos> placed = new ArrayList<>();

        int deckY = Math.max(entrance.getY(), exit.getY());

        int dx = exit.getX() - entrance.getX();
        int dz = exit.getZ() - entrance.getZ();
        int steps = Math.max(Math.abs(dx), Math.abs(dz));
        if (steps == 0) return placed;

        // Determine perpendicular for width
        int tx = Integer.signum(dx);
        int tz = Integer.signum(dz);
        int perpX = -tz;
        int perpZ = tx;
        if (perpX == 0 && perpZ == 0) perpX = 1;

        BlockState plank = Blocks.OAK_PLANKS.defaultBlockState();

        for (int s = 0; s <= steps; s++) {
            float t = (float) s / steps;
            int cx = Math.round(entrance.getX() + t * dx);
            int cz = Math.round(entrance.getZ() + t * dz);

            for (int w = -ROAD_HALF_WIDTH; w <= ROAD_HALF_WIDTH; w++) {
                int x = cx + perpX * w;
                int z = cz + perpZ * w;
                BlockPos deckPos = new BlockPos(x, deckY, z);

                BlockState existing = level.getBlockState(deckPos);
                // Only place over air, water, or replaceable terrain
                if (existing.isAir() || existing.liquid()
                        || existing.is(BlockTags.REPLACEABLE)) {
                    level.setBlock(deckPos, plank, 3);
                }

                // Clear the air block above so caravans can walk through
                BlockPos above = deckPos.above();
                BlockState aboveState = level.getBlockState(above);
                if (aboveState.is(BlockTags.LEAVES)
                        || aboveState.is(BlockTags.REPLACEABLE)) {
                    level.setBlock(above, Blocks.AIR.defaultBlockState(), 3);
                }

                placed.add(deckPos.above()); // surface position convention
            }
        }

        System.out.println("RoadRouter: placed plank bridge from "
                + entrance.toShortString() + " to " + exit.toShortString()
                + " (" + (steps + 1) + " spans, deckY=" + deckY + ")");

        return placed;
    }
    public static List<BlockPos> findRoadWithMerge(
            ServerLevel level,
            BlockPos from,
            BlockPos to,
            tterrag1112.life_in_the_village.Networking.VillageSavedData data) {

        final int MERGE_SNAP_RADIUS = 120; // blocks — how close a road must be to trigger merge

        // ── Find nearest existing road block to the route midpoint ───────────
        int midX = (from.getX() + to.getX()) / 2;
        int midZ = (from.getZ() + to.getZ()) / 2;

        BlockPos bestMergePoint = null;
        double   bestMergeDist  = MERGE_SNAP_RADIUS * MERGE_SNAP_RADIUS;

        for (tterrag1112.life_in_the_village.Village.Economy.Trade.TradeRoad road
                : data.getAllTradeRoads()) {
            // Sample every 8th block for performance (roads can be thousands of blocks)
            List<BlockPos> blocks = road.getBlocks();
            for (int i = 0; i < blocks.size(); i += 8) {
                BlockPos b   = blocks.get(i);
                double   dx  = b.getX() - midX;
                double   dz  = b.getZ() - midZ;
                double   dSq = dx * dx + dz * dz;
                if (dSq < bestMergeDist) {
                    bestMergeDist  = dSq;
                    bestMergePoint = b;
                }
            }
        }

        if (bestMergePoint == null) {
            // No existing road nearby — plain A*
            return findRoad(level, from, to);
        }

        System.out.println("RoadRouter: merging route through existing road at "
                + bestMergePoint.toShortString()
                + " (dist=" + (int)Math.sqrt(bestMergeDist) + " blocks)");

        // ── Route in two segments ─────────────────────────────────────────────
        List<BlockPos> feeder = findRoad(level, from,           bestMergePoint);
        List<BlockPos> tail   = findRoad(level, bestMergePoint, to);

        if (feeder.isEmpty() || tail.isEmpty()) {
            // Merge point routing failed — fall back to direct
            return findRoad(level, from, to);
        }

        // Concatenate, removing the duplicate merge point at the join
        List<BlockPos> combined = new ArrayList<>(feeder);
        if (!tail.isEmpty()) {
            combined.addAll(tail.subList(1, tail.size())); // skip duplicate merge node
        }
        return combined;
    }

    // =========================================================================
    // Slab transitions
    // =========================================================================

    /**
     * Replaces the road block at the lower of two adjacent steps with a slab
     * so the height transition looks like a ramp.
     *
     * {@code placed} contains air-block positions (solid block + 1).
     * The road block is therefore at {@code pos.below()}.
     */
    private static void placeSlabTransitions(ServerLevel level,
                                             List<BlockPos> placed,
                                             RoadQuality quality) {
        if (placed.size() < 2) return;

        BlockState slabState = switch (quality) {
            case COBBLESTONE -> Blocks.COBBLESTONE_SLAB.defaultBlockState();
            case GRAVEL      -> Blocks.COBBLESTONE_SLAB.defaultBlockState();
            case DIRT        -> Blocks.OAK_SLAB.defaultBlockState();
        };

        for (int i = 1; i < placed.size(); i++) {
            BlockPos prev = placed.get(i - 1);
            BlockPos curr = placed.get(i);

            int dxz = Math.abs(curr.getX() - prev.getX())
                    + Math.abs(curr.getZ() - prev.getZ());
            if (dxz > 2) continue; // not adjacent

            int dy = curr.getY() - prev.getY();
            if (Math.abs(dy) != 1) continue;

            // Lower air position → road block is lower.below()
            BlockPos lowerAir  = dy > 0 ? prev : curr;
            BlockPos roadBlock = lowerAir.below(); // the actual solid road block

            BlockState existing = level.getBlockState(roadBlock);
            if (existing.is(Blocks.COBBLESTONE)
                    || existing.is(Blocks.GRAVEL)
                    || existing.is(Blocks.DIRT_PATH)) {
                level.setBlock(roadBlock, slabState, 3);
            }
        }
    }

    // =========================================================================
    // Edge smoothing
    // =========================================================================

    private static void smoothRoadEdges(ServerLevel level,
                                        List<BlockPos> placed,
                                        Set<Long> roadXZ) {
        for (BlockPos pos : placed) {
            // pos is the air block above the road; road is pos.below()
            int roadY = pos.getY() - 1; // solid road block Y

            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;

                    int nx = pos.getX() + dx;
                    int nz = pos.getZ() + dz;

                    if (roadXZ.contains(xzKey(nx, nz))) continue;

                    int neighborY = level.getHeight(
                            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, nx, nz);
                    int jump = neighborY - roadY;

                    if (jump > 1) {
                        // Neighbour higher — carve one block
                        BlockPos carve = new BlockPos(nx, neighborY, nz);
                        BlockState cs  = level.getBlockState(carve);
                        if (cs.is(Blocks.GRASS_BLOCK) || cs.is(Blocks.DIRT)
                                || cs.is(Blocks.COARSE_DIRT)
                                || cs.is(BlockTags.REPLACEABLE)) {
                            level.setBlock(carve,
                                    Blocks.AIR.defaultBlockState(), 3);
                            BlockPos below = carve.below();
                            if (level.getBlockState(below).is(Blocks.DIRT)) {
                                level.setBlock(below,
                                        Blocks.GRASS_BLOCK.defaultBlockState(), 3);
                            }
                        }
                    } else if (jump < -1) {
                        // Neighbour lower — fill one block
                        BlockPos fill = new BlockPos(nx, neighborY + 1, nz);
                        if (level.getBlockState(fill).isAir()) {
                            level.setBlock(fill,
                                    Blocks.GRASS_BLOCK.defaultBlockState(), 3);
                        }
                    }
                }
            }
        }
    }

    // =========================================================================
    // Road block placement
    // =========================================================================

    /**
     * Places a road block at {@code pos}, which must be the solid surface
     * block position (not the air above it).
     * Walks down up to 3 times to find the true solid surface in case the
     * heightmap value is slightly off due to recent block changes.
     */
    private static void placeRoadBlock(ServerLevel level,
                                       BlockPos pos,
                                       RoadQuality quality) {
        BlockPos roadPos = pos;
        for (int i = 0; i < 3; i++) {
            BlockState cur   = level.getBlockState(roadPos);
            BlockState above = level.getBlockState(roadPos.above());

            if (cur.isSolidRender()
                    && !cur.is(BlockTags.LOGS)
                    && !cur.is(BlockTags.LEAVES)
                    && (above.isAir() || above.is(BlockTags.REPLACEABLE))) {
                break;
            }
            if (cur.isAir() || cur.is(BlockTags.REPLACEABLE)
                    || cur.is(BlockTags.FLOWERS)
                    || cur.is(BlockTags.SMALL_FLOWERS)) {
                roadPos = roadPos.below();
                continue;
            }
            if (cur.is(Blocks.BEDROCK)
                    || cur.is(BlockTags.LOGS)
                    || cur.is(BlockTags.LEAVES)) {
                return;
            }
            break;
        }

        // ── Protection check ──────────────────────────────────────────────────
        // Never overwrite existing village-placed surface blocks:
        // paving, existing roads, slabs from the town square,
        // and any block placed by a structure (planks, stone bricks, etc.)
        BlockState existing = level.getBlockState(roadPos);
        if (isProtectedSurface(existing)) return;

        // Clear vegetation above
        BlockState above = level.getBlockState(roadPos.above());
        if (above.is(BlockTags.REPLACEABLE)
                || above.is(BlockTags.FLOWERS)
                || above.is(BlockTags.SMALL_FLOWERS)
                || above.is(BlockTags.LEAVES)) {
            level.setBlock(roadPos.above(), Blocks.AIR.defaultBlockState(), 3);
        }

        // Never overwrite hard blocks
        if (existing.is(BlockTags.LOGS)
                || existing.is(BlockTags.LEAVES)
                || existing.is(Blocks.BEDROCK)
                || existing.is(BlockTags.PLANKS)
                || existing.is(Blocks.OBSIDIAN)) return;

        level.setBlock(roadPos, switch (quality) {
            case COBBLESTONE -> Blocks.COBBLESTONE.defaultBlockState();
            case GRAVEL      -> Blocks.GRAVEL.defaultBlockState();
            case DIRT        -> Blocks.DIRT_PATH.defaultBlockState();
        }, 3);
    }

    /**
     * Returns true for blocks that represent intentional village construction
     * and must never be overwritten by a road placer.
     * This covers town square paving, existing street surfaces, building
     * floors, and any crafted/dressed stone that indicates player or
     * structure placement.
     */
    private static boolean isProtectedSurface(BlockState s) {
        return s.is(Blocks.DIRT_PATH)          // existing village path
                || s.is(Blocks.COBBLESTONE_SLAB)   // square inner border
                || s.is(Blocks.STONE_BRICKS)        // building floors
                || s.is(Blocks.STONE_BRICK_SLAB)    // building decoration
                || s.is(Blocks.CHISELED_STONE_BRICKS)
                || s.is(Blocks.SMOOTH_STONE)
                || s.is(Blocks.SMOOTH_STONE_SLAB)
                || s.is(BlockTags.PLANKS)           // building floors
                || s.is(BlockTags.WOODEN_SLABS)
                || s.is(BlockTags.WOOL_CARPETS)     // market stall carpets
                || s.is(Blocks.SANDSTONE)           // desert square paving
                || s.is(Blocks.SMOOTH_SANDSTONE)
                || s.is(Blocks.PACKED_ICE)          // snowy square paving
                || s.is(Blocks.MUD)                 // swamp paths
                // Explicitly allow overwriting natural terrain only:
                // grass, dirt, sand, gravel, coarse dirt
                // Everything else is assumed to be intentional placement.
                ;
    }

    // =========================================================================
    // Tree clearing
    // =========================================================================

    private static void clearTreesAt(ServerLevel level,
                                     int x, int z, int fromY) {
        for (int y = fromY + 20; y >= fromY - 1; y--) {
            BlockPos   pos   = new BlockPos(x, y, z);
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

    // =========================================================================
    // Road maintenance
    // =========================================================================

    /**
     * Degrades road blocks. {@code roadBlocks} should be the surface
     * (air-above-road) positions; the actual road block is {@code pos.below()}.
     */
    public static void degradeRoad(ServerLevel level,
                                   List<BlockPos> roadBlocks,
                                   int quality) {
        if (quality > 66) return;
        Random rng = new Random();
        float chance = quality > 33 ? 0.1f : 0.3f;

        for (BlockPos pos : roadBlocks) {
            if (rng.nextFloat() > chance) continue;
            BlockPos road = pos.below();
            BlockState state = level.getBlockState(road);
            if (state.is(Blocks.COBBLESTONE)) {
                level.setBlock(road, Blocks.GRAVEL.defaultBlockState(), 3);
            } else if (state.is(Blocks.GRAVEL)) {
                level.setBlock(road, Blocks.DIRT_PATH.defaultBlockState(), 3);
            } else if (state.is(Blocks.DIRT_PATH)) {
                level.setBlock(road, Blocks.DIRT.defaultBlockState(), 3);
            }
        }
    }

    /**
     * Repairs road blocks. {@code roadBlocks} should be the surface
     * (air-above-road) positions.
     */
    public static void repairRoad(ServerLevel level,
                                  List<BlockPos> roadBlocks) {
        for (BlockPos pos : roadBlocks) {
            BlockPos road = pos.below();
            BlockState state = level.getBlockState(road);
            if (state.is(Blocks.GRAVEL)
                    || state.is(Blocks.DIRT_PATH)
                    || state.is(Blocks.DIRT)) {
                level.setBlock(road,
                        Blocks.COBBLESTONE.defaultBlockState(), 3);
            }
        }
    }

    // =========================================================================
    // A* helpers
    // =========================================================================

    private static List<BlockPos> getNeighbors(BlockPos pos) {
        List<BlockPos> neighbors = new ArrayList<>(8);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                neighbors.add(new BlockPos(pos.getX() + dx, 0, pos.getZ() + dz));
            }
        }
        return neighbors;
    }

    private static float movementCost(ServerLevel level,
                                      BlockPos from, BlockPos to) {
        int dy = Math.abs(to.getY() - from.getY());

        BlockState toState = level.getBlockState(to);
        if (toState.liquid()) return COST_WATER;

        if (toState.is(Blocks.COBBLESTONE)
                || toState.is(Blocks.GRAVEL)
                || toState.is(Blocks.DIRT_PATH)) {
            return COST_EXISTING;
        }

        boolean diagonal = from.getX() != to.getX()
                && from.getZ() != to.getZ();
        float base = diagonal ? 1.414f : 1.0f;

        if (dy == 0) return base * COST_FLAT;
        if (dy > MAX_SLOPE) return base * COST_STEEP * (float) Math.pow(dy, 3);
        return base * COST_GENTLE * dy;
    }

    private static float heuristic(ServerLevel level,
                                   BlockPos from, BlockPos to) {
        float dx = Math.abs(from.getX() - to.getX());
        float dz = Math.abs(from.getZ() - to.getZ());
        float octile = Math.max(dx, dz)
                + (float)(Math.sqrt(2) - 1) * Math.min(dx, dz);

        // Bias away from terrain high points on the direct line
        int midX = (from.getX() + to.getX()) / 2;
        int midZ = (from.getZ() + to.getZ()) / 2;
        int midY = level.getHeight(
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, midX, midZ);
        int dy = Math.abs(midY - from.getY());

        return octile + dy * 4.0f;
    }

    private static List<BlockPos> reconstructPath(Node end) {
        LinkedList<BlockPos> path = new LinkedList<>();
        Node cur = end;
        while (cur != null) {
            path.addFirst(cur.pos);
            cur = cur.parent;
        }
        return new ArrayList<>(path);
    }

    /**
     * Expands the A* waypoint list into a dense step-by-step path so the
     * placement passes always have adjacent nodes to work from.
     * Unlike {@code smoothPath} (which removed nodes), this ADDS nodes between
     * any two waypoints that are more than 1 block apart in XZ.
     */
    private static List<BlockPos> densify(List<BlockPos> waypoints,
                                          ServerLevel level) {
        if (waypoints.size() <= 1) return waypoints;

        List<BlockPos> dense = new ArrayList<>();
        dense.add(waypoints.get(0));

        for (int i = 1; i < waypoints.size(); i++) {
            BlockPos a = waypoints.get(i - 1);
            BlockPos b = waypoints.get(i);
            int steps = Math.max(
                    Math.abs(b.getX() - a.getX()),
                    Math.abs(b.getZ() - a.getZ()));

            for (int s = 1; s <= steps; s++) {
                float t  = (float) s / steps;
                int ix   = Math.round(a.getX() + t * (b.getX() - a.getX()));
                int iz   = Math.round(a.getZ() + t * (b.getZ() - a.getZ()));
                int iy   = level.getHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, ix, iz);
                dense.add(new BlockPos(ix, iy, iz));
            }
        }

        return dense;
    }

    private static BlockPos solidSurface(ServerLevel level, BlockPos pos) {
        int y = level.getHeight(
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                pos.getX(), pos.getZ());
        return new BlockPos(pos.getX(), y, pos.getZ());
    }

    private static long key(BlockPos pos) {
        return ((long)(pos.getX() + 30_000_000)) << 32
                | (pos.getZ() + 30_000_000);
    }

    private static long xzKey(int x, int z) {
        return ((long)(x + 30_000_000)) << 32 | (z + 30_000_000);
    }
    public static BlockPos nearestRoadBlock(
            BlockPos pos,
            int radiusBlocks,
            tterrag1112.life_in_the_village.Networking.VillageSavedData data) {

        BlockPos best    = null;
        long     bestSq  = (long) radiusBlocks * radiusBlocks;

        for (tterrag1112.life_in_the_village.Village.Economy.Trade.TradeRoad road
                : data.getAllTradeRoads()) {
            List<BlockPos> blocks = road.getBlocks();
            // Sample every 4th block
            for (int i = 0; i < blocks.size(); i += 4) {
                BlockPos b  = blocks.get(i);
                long     dx = b.getX() - pos.getX();
                long     dz = b.getZ() - pos.getZ();
                long     sq = dx * dx + dz * dz;
                if (sq < bestSq) { bestSq = sq; best = b; }
            }
        }
        return best;
    }

    // =========================================================================
    // Supporting types
    // =========================================================================

    private static final class Node {
        final BlockPos pos;
        final Node     parent;
        final float    g, h, f;

        Node(BlockPos pos, Node parent, float g, float h) {
            this.pos    = pos;
            this.parent = parent;
            this.g      = g;
            this.h      = h;
            this.f      = g + h;
        }
    }

    public enum RoadQuality { COBBLESTONE, GRAVEL, DIRT }
}