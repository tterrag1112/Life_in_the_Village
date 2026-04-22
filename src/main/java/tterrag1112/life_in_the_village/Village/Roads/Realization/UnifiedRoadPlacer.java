package tterrag1112.life_in_the_village.Village.Roads.Realization;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluids;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.OrganicRoadPlacer;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.PathMaterial;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.RoadShape;
import tterrag1112.life_in_the_village.Village.Economy.Trade.RoadRouter;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadEdge;
import tterrag1112.life_in_the_village.World.SeasonTracker;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Places road blocks for a single primitive's centerline.
 *
 * <h3>Pipeline (per-centerline)</h3>
 * <ol>
 *   <li>Densify sparse waypoints to block-dense path (1 block between steps).</li>
 *   <li>Detect water spans on the original sparse waypoints.</li>
 *   <li>Clear trees along the dense path.</li>
 *   <li>Paint the surface with {@link OrganicRoadPlacer} using the dense path.</li>
 *   <li>Place plank-deck bridges over detected water spans.</li>
 *   <li>Apply culture-specific architectural detail passes (imperial/highland/nordic).</li>
 *   <li>Apply winter snow overlay on stone-tier roads.</li>
 * </ol>
 *
 * <p>The caller supplies the centerline from a primitive's
 * {@code computeCenterline()} call — this class never pathfinds.
 */
public final class UnifiedRoadPlacer {

    /** Minimum contiguous water-sample steps to trigger bridge placement. */
    private static final int BRIDGE_THRESHOLD = 3;

    /** Step size (blocks) when sampling the line between waypoints for water. */
    private static final int WATER_SAMPLE_STEP = 4;

    private UnifiedRoadPlacer() {}

    /**
     * Places road blocks along the given centerline.
     *
     * @param level      server level
     * @param centerline ordered waypoints from the primitive (start → end); may be sparse
     * @param material   block material to use for road surface
     * @param tier       width tier for OrganicRoadPlacer
     * @param edge       source edge (used for deterministic RNG seeding)
     * @param culture    village culture ("imperial", "highland", "nordic", "default", or null)
     * @return all blocks that were placed (road surface + bridge decks)
     */
    public static List<BlockPos> place(ServerLevel level,
                                       List<BlockPos> centerline,
                                       PathMaterial material,
                                       RoadShape.RoadTier tier,
                                       RoadEdge edge,
                                       @Nullable String culture) {
        if (centerline.size() < 2) return List.of();

        // Densify sparse waypoints to block-dense path before placement.
        // Bridge detection uses the original sparse list because detectWaterSpans
        // handles arbitrary-length segments and would short-circuit at 1-block gaps.
        List<BlockPos> dense = densify(centerline, level);

        // ── Step 1: detect water spans on original (sparse) waypoints ──────────
        List<WaterSpan> bridgeSpans = new ArrayList<>();
        for (int i = 0; i < centerline.size() - 1; i++) {
            bridgeSpans.addAll(detectWaterSpans(level, centerline.get(i), centerline.get(i + 1)));
        }

        // ── Step 2: clear trees along dense path ───────────────────────────────
        for (BlockPos p : dense) {
            RoadRouter.clearTreesAt(level, p.getX(), p.getZ(), p.getY());
        }

        // ── Step 3: paint surface using dense path ─────────────────────────────
        long seed = edge.getEdgeId().getLeastSignificantBits()
                ^ edge.getEdgeId().getMostSignificantBits();
        RandomSource rng = RandomSource.create(seed);
        OrganicRoadPlacer.PlacementResult result =
                OrganicRoadPlacer.place(level, dense, material, tier, null, rng);
        List<BlockPos> placed = new ArrayList<>(result.placedBlocks());

        // ── Step 4: place bridges ───────────────────────────────────────────────
        for (WaterSpan span : bridgeSpans) {
            placed.addAll(RoadRouter.placeBridge(level, span.entrance(), span.exit()));
        }

        // ── Step 5: culture-specific architectural detail passes ───────────────
        if (culture != null) {
            switch (culture) {
                case "imperial" -> imperialGutterPass(level, dense, tier);
                case "highland" -> highlandRetainingWallPass(level, dense, tier);
                case "nordic"   -> nordicCorduroyPass(level, dense, tier);
            }
        }

        // ── Step 6: winter snow on stone-tier roads ────────────────────────────
        SeasonTracker.Season season = SeasonTracker.currentSeason(level);
        if (season == SeasonTracker.Season.WINTER && tier.placedHalfWidth() >= 2) {
            winterSnowPass(level, placed);
        }

        return placed;
    }

    // =========================================================================
    // Densification — identical to RoadRouter.densify (private there)
    // =========================================================================

    /**
     * Expands sparse waypoints into a block-dense path where consecutive
     * positions differ by at most 1 block on the dominant axis. Each
     * interpolated step is surface-snapped via the heightmap.
     *
     * <p>This is the same algorithm as {@code RoadRouter.densify}. It is copied
     * here because that method is private, and UnifiedRoadPlacer must not depend
     * on RoadRouter's internal implementation details.
     */
    static List<BlockPos> densify(List<BlockPos> waypoints, ServerLevel level) {
        if (waypoints.size() <= 1) return waypoints;
        List<BlockPos> out = new ArrayList<>();
        out.add(waypoints.get(0));
        for (int i = 1; i < waypoints.size(); i++) {
            BlockPos a = waypoints.get(i - 1);
            BlockPos b = waypoints.get(i);
            int steps = Math.max(Math.abs(b.getX() - a.getX()),
                                 Math.abs(b.getZ() - a.getZ()));
            for (int s = 1; s <= steps; s++) {
                float t  = (float) s / steps;
                int ix   = Math.round(a.getX() + t * (b.getX() - a.getX()));
                int iz   = Math.round(a.getZ() + t * (b.getZ() - a.getZ()));
                int iy   = level.getHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, ix, iz);
                out.add(new BlockPos(ix, iy, iz));
            }
        }
        return out;
    }

    // =========================================================================
    // Water span detection
    // =========================================================================

    private record WaterSpan(BlockPos entrance, BlockPos exit, int length) {}

    private static List<WaterSpan> detectWaterSpans(ServerLevel level,
                                                     BlockPos a, BlockPos b) {
        List<WaterSpan> spans = new ArrayList<>();
        int dx = b.getX() - a.getX();
        int dz = b.getZ() - a.getZ();
        int distSq = dx * dx + dz * dz;
        if (distSq < WATER_SAMPLE_STEP * WATER_SAMPLE_STEP) return spans;

        int steps = Math.max(1, (int) Math.sqrt(distSq) / WATER_SAMPLE_STEP);
        BlockPos waterStart = null;
        BlockPos lastDry = a;
        int wetCount = 0;

        for (int s = 0; s <= steps; s++) {
            float t = (float) s / steps;
            int x = Math.round(a.getX() + t * dx);
            int z = Math.round(a.getZ() + t * dz);
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos here = new BlockPos(x, y, z);

            if (isWaterSurface(level, here)) {
                if (waterStart == null) {
                    waterStart = lastDry;
                    wetCount = 1;
                } else {
                    wetCount++;
                }
            } else {
                if (waterStart != null) {
                    if (wetCount >= BRIDGE_THRESHOLD) {
                        spans.add(new WaterSpan(waterStart, here, wetCount));
                    }
                    waterStart = null;
                    wetCount = 0;
                }
                lastDry = here;
            }
        }

        if (waterStart != null && wetCount >= BRIDGE_THRESHOLD) {
            spans.add(new WaterSpan(waterStart, b, wetCount));
        }

        return spans;
    }

    private static boolean isWaterSurface(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) return false;
        return level.getFluidState(pos).is(Fluids.WATER)
                || level.getFluidState(pos).is(Fluids.FLOWING_WATER);
    }

    // =========================================================================
    // Culture-specific architectural detail passes
    // =========================================================================

    /**
     * Imperial drainage gutters: stone slab placed alongside the outermost road
     * edge at road-surface level, creating a physical kerb/drainage channel.
     * Only placed where the block is replaceable (terrain is open at that Y).
     */
    private static void imperialGutterPass(ServerLevel level,
                                            List<BlockPos> dense,
                                            RoadShape.RoadTier tier) {
        int halfWidth = tier.placedHalfWidth();
        BlockState slab = Blocks.STONE_SLAB.defaultBlockState();

        for (int i = 0; i < dense.size(); i++) {
            BlockPos center = dense.get(i);
            int[] perp = computePerp(dense, i);
            // Road block is at center.getY() - 1; gutter slab at road block Y (half-step down visually)
            int roadY = center.getY() - 1;

            for (int side : new int[]{-1, 1}) {
                int gx = center.getX() + perp[0] * side * (halfWidth + 1);
                int gz = center.getZ() + perp[1] * side * (halfWidth + 1);
                BlockPos gutterPos = new BlockPos(gx, roadY, gz);
                BlockPos belowGutter = gutterPos.below();
                BlockState below = level.getBlockState(belowGutter);
                BlockState here = level.getBlockState(gutterPos);
                // Only place when terrain supports it: block below solid, position open
                if (below.isSolidRender() && (here.isAir() || here.canBeReplaced())) {
                    level.setBlock(gutterPos, slab, 3);
                }
            }
        }
    }

    /**
     * Highland retaining walls: cobblestone_wall on the downhill side when the
     * terrain drops more than 2 blocks within 3 lateral blocks of the road centre.
     * Walls stack up to 3 high when the drop is severe.
     */
    private static void highlandRetainingWallPass(ServerLevel level,
                                                   List<BlockPos> dense,
                                                   RoadShape.RoadTier tier) {
        BlockState wall = Blocks.COBBLESTONE_WALL.defaultBlockState();
        int wallOffset = tier.placedHalfWidth() + 1;

        for (int i = 0; i < dense.size(); i++) {
            BlockPos center = dense.get(i);
            int[] perp = computePerp(dense, i);
            int roadY = center.getY() - 1;

            for (int side : new int[]{-1, 1}) {
                int checkX = center.getX() + perp[0] * side * 3;
                int checkZ = center.getZ() + perp[1] * side * 3;
                int sideY = level.getHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, checkX, checkZ) - 1;
                int drop = roadY - sideY;

                if (drop > 2) {
                    int wallX = center.getX() + perp[0] * side * wallOffset;
                    int wallZ = center.getZ() + perp[1] * side * wallOffset;
                    int wallCount = Math.min(3, drop - 1);
                    for (int h = 0; h < wallCount; h++) {
                        level.setBlock(new BlockPos(wallX, roadY + h, wallZ), wall, 3);
                    }
                }
            }
        }
    }

    /**
     * Nordic corduroy: replaces core blocks with spruce planks where the ground
     * beneath is mud, clay, or other swamp-like terrain. The plank run covers
     * the full core half-width perpendicular to the road direction.
     */
    private static void nordicCorduroyPass(ServerLevel level,
                                            List<BlockPos> dense,
                                            RoadShape.RoadTier tier) {
        BlockState plank = Blocks.SPRUCE_PLANKS.defaultBlockState();
        int coreHalf = tier.coreHalf;

        for (int i = 0; i < dense.size(); i++) {
            BlockPos center = dense.get(i);
            int roadY = center.getY() - 1;

            // Check terrain type at centre position
            BlockState groundBlock = level.getBlockState(new BlockPos(
                    center.getX(), roadY - 1, center.getZ()));
            if (!isSwampTerrain(groundBlock)) continue;

            int[] perp = computePerp(dense, i);
            for (int d = -coreHalf; d <= coreHalf; d++) {
                int px = center.getX() + perp[0] * d;
                int pz = center.getZ() + perp[1] * d;
                BlockPos plankPos = new BlockPos(px, roadY, pz);
                BlockState existing = level.getBlockState(plankPos);
                if (!existing.liquid()) {
                    level.setBlock(plankPos, plank, 3);
                }
            }
        }
    }

    private static boolean isSwampTerrain(BlockState s) {
        return s.is(Blocks.MUD)
                || s.is(Blocks.CLAY)
                || s.is(Blocks.MUDDY_MANGROVE_ROOTS);
    }

    // =========================================================================
    // Seasonal overlay
    // =========================================================================

    /**
     * Places a 1-layer snow block on top of each placed stone-tier road block
     * during winter. Placed blocks in the list are at surfY (the air position
     * above the road block), so snow is placed at that position.
     */
    private static void winterSnowPass(ServerLevel level, List<BlockPos> placed) {
        BlockState snow = Blocks.SNOW.defaultBlockState();
        for (BlockPos pos : placed) {
            if (!level.isLoaded(pos)) continue;
            BlockState existing = level.getBlockState(pos);
            if (existing.isAir()) {
                level.setBlock(pos, snow, 3);
            }
        }
    }

    // =========================================================================
    // Perpendicular direction helper
    // =========================================================================

    /** Mirrors OrganicRoadPlacer.computePerpendicular for use in detail passes. */
    private static int[] computePerp(List<BlockPos> line, int index) {
        BlockPos prev = index > 0 ? line.get(index - 1) : line.get(index);
        BlockPos next = index < line.size() - 1 ? line.get(index + 1) : line.get(index);
        int dx = next.getX() - prev.getX();
        int dz = next.getZ() - prev.getZ();
        int headX = Integer.signum(dx);
        int headZ = Integer.signum(dz);
        if (headX != 0 && headZ != 0) {
            return (index & 1) == 0 ? new int[]{0, 1} : new int[]{1, 0};
        }
        return new int[]{-headZ, headX};
    }
}
