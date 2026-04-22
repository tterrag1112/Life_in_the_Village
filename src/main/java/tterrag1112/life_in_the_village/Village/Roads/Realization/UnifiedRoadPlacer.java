package tterrag1112.life_in_the_village.Village.Roads.Realization;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluids;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.OrganicRoadPlacer;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.PathMaterial;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.RoadShape;
import tterrag1112.life_in_the_village.Village.Economy.Trade.RoadRouter;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadEdge;

import java.util.ArrayList;
import java.util.List;

/**
 * Places road blocks for a single primitive's centerline.
 *
 * <h3>Pipeline (per-centerline)</h3>
 * <ol>
 *   <li>Detect water spans between consecutive waypoints.</li>
 *   <li>Clear trees along the entire centerline.</li>
 *   <li>Paint the surface with {@link OrganicRoadPlacer}.</li>
 *   <li>Place plank-deck bridges over detected water spans via
 *       {@link RoadRouter#placeBridge}.</li>
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
     * @param centerline ordered waypoints from the primitive (start → end)
     * @param material   block material to use for road surface
     * @param tier       width tier for OrganicRoadPlacer
     * @param edge       source edge (used for deterministic RNG seeding)
     * @return all blocks that were placed (road surface + bridge decks)
     */
    public static List<BlockPos> place(ServerLevel level,
                                       List<BlockPos> centerline,
                                       PathMaterial material,
                                       RoadShape.RoadTier tier,
                                       RoadEdge edge) {
        if (centerline.size() < 2) return List.of();

        // ── Step 1: detect water spans ──────────────────────────────────────
        List<WaterSpan> bridgeSpans = new ArrayList<>();
        for (int i = 0; i < centerline.size() - 1; i++) {
            bridgeSpans.addAll(detectWaterSpans(level, centerline.get(i), centerline.get(i + 1)));
        }

        // ── Step 2: clear trees ─────────────────────────────────────────────
        for (BlockPos p : centerline) {
            RoadRouter.clearTreesAt(level, p.getX(), p.getZ(), p.getY());
        }

        // ── Step 3: paint surface ───────────────────────────────────────────
        long seed = edge.getEdgeId().getLeastSignificantBits()
                ^ edge.getEdgeId().getMostSignificantBits();
        RandomSource rng = RandomSource.create(seed);
        OrganicRoadPlacer.PlacementResult result =
                OrganicRoadPlacer.place(level, centerline, material, tier, null, rng);
        List<BlockPos> placed = new ArrayList<>(result.placedBlocks());

        // ── Step 4: place bridges ───────────────────────────────────────────
        for (WaterSpan span : bridgeSpans) {
            placed.addAll(RoadRouter.placeBridge(level, span.entrance(), span.exit()));
        }

        return placed;
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
}
