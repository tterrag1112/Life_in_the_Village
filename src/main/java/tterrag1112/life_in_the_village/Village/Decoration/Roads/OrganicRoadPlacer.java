package tterrag1112.life_in_the_village.Village.Decoration.Roads;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import tterrag1112.life_in_the_village.Village.Planning.BuildingFootprint;

import java.util.*;

/**
 * Places road segments with organic edges and weighted block materials.
 *
 * <h3>Design</h3>
 * A road segment is a list of centerline positions (from hub to building
 * entrance). For each centerline block, the placer expands perpendicular
 * to the road direction and places blocks in three zones:
 * <ul>
 *   <li><b>Core</b> — always the primary material, forms the walkable center</li>
 *   <li><b>Inner</b> — mostly primary with slight variation</li>
 *   <li><b>Edge</b> — organic blend using position-seeded noise, creating
 *       the "grass creeping in" effect</li>
 * </ul>
 *
 * <h3>Centerline routing</h3>
 * This placer does NOT do pathfinding. It receives an already-computed
 * centerline (from the road network's straight-line or L-shaped routing)
 * and paints the surface around it. Pathfinding is the caller's job.
 *
 * <h3>Terrain handling</h3>
 * Each road block is placed at the MOTION_BLOCKING_NO_LEAVES surface.
 * The block below the surface is set to the road material, and the block
 * at surface+1 is cleared to air (removing flowers, grass, etc.).
 * This means roads cut through tall grass and flowers naturally.
 *
 * <h3>Building avoidance</h3>
 * The placer receives a {@link BuildingFootprint} grid and never places
 * road blocks on positions marked as building footprint. This prevents
 * roads from overwriting building walls.
 */
public class OrganicRoadPlacer {

    // =========================================================================
    // Result
    // =========================================================================

    /**
     * Result of placing a road segment.
     */
    public record PlacementResult(
            /** All positions where road blocks were placed (for network registration). */
            List<BlockPos> placedBlocks,
            /** The centerline positions (for road reservation in footprint grid). */
            List<BlockPos> centerline,
            /** Number of blocks skipped due to building collision. */
            int skippedCount
    ) {}

    // =========================================================================
    // Entry point
    // =========================================================================

    /**
     * Places a road segment along the given centerline.
     *
     * @param level      server level
     * @param centerline ordered list of center positions (from start to end)
     * @param material   the weighted block material to use
     * @param tier       road width tier
     * @param footprint  building collision grid (road blocks are skipped where
     *                   buildings exist)
     * @param random     random source for block sampling
     * @return placement result with all placed blocks
     */
    public static PlacementResult place(ServerLevel level,
                                        List<BlockPos> centerline,
                                        PathMaterial material,
                                        RoadShape.RoadTier tier,
                                        BuildingFootprint footprint,
                                        RandomSource random) {
        if (centerline.isEmpty()) {
            return new PlacementResult(List.of(), List.of(), 0);
        }

        List<BlockPos> placed = new ArrayList<>();
        int skipped = 0;
        int halfWidth = tier.placedHalfWidth();

        for (int i = 0; i < centerline.size(); i++) {
            BlockPos center = centerline.get(i);

            // Determine perpendicular direction from road heading
            int[] perp = computePerpendicular(centerline, i);

            // Place blocks across the road width
            for (int d = -halfWidth; d <= halfWidth; d++) {
                int wx = center.getX() + perp[0] * d;
                int wz = center.getZ() + perp[1] * d;

                // Skip if inside a building
                if (footprint != null && footprint.isBuildingAt(wx, wz)) {
                    skipped++;
                    continue;
                }

                int absDist = Math.abs(d);
                RoadShape.Zone zone = RoadShape.classify(absDist, tier);
                if (zone == RoadShape.Zone.OUTSIDE) continue;

                // Edge zone: organic noise determines if block is placed
                if (zone == RoadShape.Zone.EDGE) {
                    if (!RoadShape.shouldPlaceEdge(wx, wz, absDist, tier)) {
                        continue;
                    }
                }

                // Determine block material based on zone
                BlockState state = pickBlock(zone, material, wx, wz, random);

                // Place at terrain surface
                int surfY = level.getHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, wx, wz);
                BlockPos placePos = new BlockPos(wx, surfY - 1, wz);

                // Don't place road on water or existing building materials
                BlockState existing = level.getBlockState(placePos);
                if (existing.liquid()) continue;
                if (isProtectedSurface(existing)) continue;

                // Place road surface block
                level.setBlock(placePos, state, 3);

                // Clear vegetation above (tall grass, flowers, etc.)
                BlockPos above = placePos.above();
                BlockState aboveState = level.getBlockState(above);
                if (isVegetation(aboveState)) {
                    level.setBlock(above, Blocks.AIR.defaultBlockState(), 3);
                }

                // Also clear 1 more above for tall plants
                BlockPos above2 = above.above();
                BlockState above2State = level.getBlockState(above2);
                if (isVegetation(above2State)) {
                    level.setBlock(above2, Blocks.AIR.defaultBlockState(), 3);
                }

                placed.add(new BlockPos(wx, surfY, wz));
            }
        }

        return new PlacementResult(placed, centerline, skipped);
    }

    // =========================================================================
    // Upgrade — replaces surface blocks on existing road positions
    // =========================================================================

    /**
     * Upgrades an existing road segment to a new material and tier.
     * Reuses the stored block positions from VillagePath, expanding
     * width if the new tier is wider.
     *
     * @param level     server level
     * @param path      the existing village path (positions stored)
     * @param material  new material
     * @param newTier   new road tier
     * @param footprint collision grid
     * @param random    random source
     * @return updated placement result
     */
    public static PlacementResult upgrade(ServerLevel level,
                                          VillagePath path,
                                          PathMaterial material,
                                          RoadShape.RoadTier newTier,
                                          BuildingFootprint footprint,
                                          RandomSource random) {
        // Reconstruct centerline from the path's stored center positions
        List<BlockPos> centerline = path.getCenterline();
        if (centerline.isEmpty()) {
            // Fallback: use all blocks as approximate centerline
            centerline = path.getBlocks();
        }
        return place(level, centerline, material, newTier, footprint, random);
    }

    // =========================================================================
    // Block selection
    // =========================================================================

    private static BlockState pickBlock(RoadShape.Zone zone,
                                        PathMaterial material,
                                        int worldX, int worldZ,
                                        RandomSource random) {
        return switch (zone) {
            case CORE -> material.sampleCore(random);
            case INNER -> RoadShape.isInnerCore(worldX, worldZ)
                    ? material.sampleCore(random)
                    : material.sampleEdge(random);
            case EDGE -> material.sampleEdge(random);
            case OUTSIDE -> Blocks.GRASS_BLOCK.defaultBlockState();
        };
    }

    // =========================================================================
    // Perpendicular computation
    // =========================================================================

    /**
     * Computes the perpendicular direction at a point along the centerline.
     * Returns {perpX, perpZ} where each is -1, 0, or 1.
     *
     * Uses the heading between adjacent centerline points. For the first
     * and last positions, uses the heading of the only available neighbor.
     */
    private static int[] computePerpendicular(List<BlockPos> centerline, int index) {
        BlockPos prev = index > 0
                ? centerline.get(index - 1) : centerline.get(index);
        BlockPos next = index < centerline.size() - 1
                ? centerline.get(index + 1) : centerline.get(index);

        int dx = next.getX() - prev.getX();
        int dz = next.getZ() - prev.getZ();

        // Normalize to -1/0/1
        int headX = Integer.signum(dx);
        int headZ = Integer.signum(dz);

        // Perpendicular: rotate 90 degrees
        // heading (1,0) → perp (0,1)
        // heading (0,1) → perp (-1,0)
        // heading (1,1) → perp (-1,1) — diagonal, we pick one axis
        if (headX != 0 && headZ != 0) {
            // Diagonal heading — alternate perpendicular based on position
            // to avoid gaps
            if ((index & 1) == 0) {
                return new int[]{0, 1};
            } else {
                return new int[]{1, 0};
            }
        }

        return new int[]{-headZ, headX};
    }

    // =========================================================================
    // Vegetation detection
    // =========================================================================

    private static boolean isProtectedSurface(BlockState s) {
        return s.is(Blocks.DIRT_PATH)
                || s.is(Blocks.COBBLESTONE_SLAB)
                || s.is(Blocks.STONE_BRICKS)
                || s.is(Blocks.STONE_BRICK_SLAB)
                || s.is(Blocks.CHISELED_STONE_BRICKS)
                || s.is(Blocks.SMOOTH_STONE)
                || s.is(Blocks.SMOOTH_STONE_SLAB)
                || s.is(BlockTags.PLANKS)
                || s.is(BlockTags.WOODEN_SLABS)
                || s.is(BlockTags.WOOL_CARPETS)
                || s.is(Blocks.SANDSTONE)
                || s.is(Blocks.SMOOTH_SANDSTONE)
                || s.is(Blocks.PACKED_ICE)
                || s.is(Blocks.MUD)
                || s.is(BlockTags.LOGS); // includes stripped logs (building columns)
    }

    private static boolean isVegetation(BlockState state) {
        return state.is(Blocks.SHORT_GRASS)
                || state.is(Blocks.TALL_GRASS)
                || state.is(Blocks.FERN)
                || state.is(Blocks.LARGE_FERN)
                || state.is(Blocks.POPPY)
                || state.is(Blocks.DANDELION)
                || state.is(Blocks.CORNFLOWER)
                || state.is(Blocks.AZURE_BLUET)
                || state.is(Blocks.OXEYE_DAISY)
                || state.is(Blocks.BLUE_ORCHID)
                || state.is(Blocks.ALLIUM)
                || state.is(Blocks.RED_TULIP)
                || state.is(Blocks.ORANGE_TULIP)
                || state.is(Blocks.WHITE_TULIP)
                || state.is(Blocks.PINK_TULIP)
                || state.is(Blocks.LILY_OF_THE_VALLEY)
                || state.is(Blocks.DEAD_BUSH)
                || state.is(Blocks.SWEET_BERRY_BUSH);
    }

    // =========================================================================
    // Simple centerline routing (straight + L-bend)
    // =========================================================================

    /**
     * Creates a centerline from point A to point B using a straight line
     * with at most one L-shaped bend. This avoids A* pathfinding for
     * intra-village roads where simple geometry works better.
     *
     * @param from start position
     * @param to   end position
     * @param level server level (for surface snapping)
     * @return ordered list of centerline positions
     */
    public static List<BlockPos> routeCenterline(BlockPos from, BlockPos to,
                                                 ServerLevel level) {
        List<BlockPos> line = new ArrayList<>();

        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();

        // If mostly X-aligned, go X first then Z
        // If mostly Z-aligned, go Z first then X
        boolean xFirst = Math.abs(dx) >= Math.abs(dz);

        int cx = from.getX(), cz = from.getZ();

        if (xFirst) {
            // X leg
            int stepX = Integer.signum(dx);
            for (int i = 0; i != dx; i += stepX) {
                int y = level.getHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cx, cz);
                line.add(new BlockPos(cx, y, cz));
                cx += stepX;
            }
            // Z leg
            int stepZ = Integer.signum(dz);
            for (int i = 0; i != dz; i += stepZ) {
                int y = level.getHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cx, cz);
                line.add(new BlockPos(cx, y, cz));
                cz += stepZ;
            }
        } else {
            // Z leg first
            int stepZ = Integer.signum(dz);
            for (int i = 0; i != dz; i += stepZ) {
                int y = level.getHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cx, cz);
                line.add(new BlockPos(cx, y, cz));
                cz += stepZ;
            }
            // X leg
            int stepX = Integer.signum(dx);
            for (int i = 0; i != dx; i += stepX) {
                int y = level.getHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cx, cz);
                line.add(new BlockPos(cx, y, cz));
                cx += stepX;
            }
        }

        // Final position
        int y = level.getHeight(
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, to.getX(), to.getZ());
        line.add(new BlockPos(to.getX(), y, to.getZ()));

        return line;
    }
}