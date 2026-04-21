// src/main/java/tterrag1112/life_in_the_village/Village/Economy/Trade/RouteRealiser.java
package tterrag1112.life_in_the_village.Village.Economy.Trade;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.Heightmap;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.OrganicRoadPlacer;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.PathMaterial;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.RoadShape;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.RoadPrimitive;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts a cell-level path (list of atlas cell keys) into a
 * block-level path and places road blocks along it.
 *
 * <h3>Phase 7b additions</h3>
 * <ul>
 *   <li><b>Smoothed waypoints.</b> Cell centres are interpolated with
 *       {@link RoutePathSmoother} before any block-level routing
 *       happens. This eliminates the sharp angles at cell boundaries
 *       that made pre-7b roads look like they were built out of
 *       Lego pieces.</li>
 *   <li><b>Water span detection.</b> Each inter-waypoint hop is checked
 *       for water crossings. Wide spans get a plank-deck bridge placed
 *       directly via {@link RoadRouter#placeBridge}, and the block
 *       router routes around the bridge endpoints rather than trying
 *       (and failing) to cross open water.</li>
 * </ul>
 *
 * <h3>What didn't change</h3>
 * The block-level placement is still {@link RoadRouter#findRoad} +
 * {@link RoadRouter#placeRoad}. Smoothing happens upstream of those
 * calls; bridges are inserted between hops, not within them.
 */
public final class RouteRealiser {

    /** A water span this many blocks or longer triggers bridge placement. */
    private static final int BRIDGE_THRESHOLD = 3;

    /** Sample step (in blocks) for water span detection. */
    private static final int WATER_SAMPLE_STEP = 2;

    private RouteRealiser() {}

    // =========================================================================
    // Realisation
    // =========================================================================

    /**
     * Realises a cell path into a block path and places road blocks.
     * Returns the list of placed surface positions, or an empty list
     * if every inter-cell hop failed.
     */
    public static List<BlockPos> realise(ServerLevel level,
                                         List<Long> cellPath,
                                         RoadRouter.RoadQuality quality) {
        if (cellPath.size() < 2) return List.of();

        List<BlockPos> rawWaypoints = new ArrayList<>(cellPath.size());
        for (long key : cellPath) {
            rawWaypoints.add(surfaceCenter(level, key));
        }

        return realiseFromWaypoints(level, rawWaypoints, quality, null);
    }

    /**
     * Realises a cell path between two specific endpoints. The first
     * and last waypoints become the provided hubs instead of the cell
     * centres, so the road lands precisely at the village boundaries.
     *
     * @param data saved data used for merge-point detection (may be null)
     */
    public static List<BlockPos> realiseBetween(ServerLevel level,
                                                List<Long> cellPath,
                                                BlockPos startHub,
                                                BlockPos endHub,
                                                RoadRouter.RoadQuality quality,
                                                VillageSavedData data) {
        if (cellPath.isEmpty()) return List.of();

        if (cellPath.size() == 1) {
            // Single-cell route — direct hub-to-hub via drift path
            List<BlockPos> direct = driftedHop(level, startHub, endHub, data);
            return placeWithBridges(level, direct, quality);
        }

        List<BlockPos> rawWaypoints = new ArrayList<>(cellPath.size());
        rawWaypoints.add(startHub);
        for (int i = 1; i < cellPath.size() - 1; i++) {
            rawWaypoints.add(surfaceCenter(level, cellPath.get(i)));
        }
        rawWaypoints.add(endHub);

        return realiseFromWaypoints(level, rawWaypoints, quality, data);
    }

    // =========================================================================
    // Core realisation pipeline
    // =========================================================================

    /**
     * Smooths the waypoint list, then generates drift-noise centerlines
     * between consecutive smoothed waypoints, placing bridges over water.
     */
    private static List<BlockPos> realiseFromWaypoints(
            ServerLevel level,
            List<BlockPos> rawWaypoints,
            RoadRouter.RoadQuality quality,
            VillageSavedData data) {

        // ── Step 1: smooth the waypoint sequence ────────────────────────────
        List<BlockPos> smoothed = RoutePathSmoother.smooth(level, rawWaypoints);

        // ── Step 2: drift centerlines between waypoints, bridging water ─────
        List<BlockPos> blockPath = new ArrayList<>();
        List<WaterSpan> bridgeSpans = new ArrayList<>();

        for (int i = 0; i < smoothed.size() - 1; i++) {
            BlockPos a = smoothed.get(i);
            BlockPos b = smoothed.get(i + 1);

            // Detect water spans on the straight line between a and b
            List<WaterSpan> spans = detectWaterSpans(level, a, b);
            if (spans.isEmpty()) {
                // Plain land hop — organic drift path, no A* diagonals
                List<BlockPos> hop = driftedHop(level, a, b, data);
                appendHop(blockPath, hop);
                continue;
            }

            // Water present — drift to each bridge entrance, bridge, drift on
            BlockPos cursor = a;
            for (WaterSpan span : spans) {
                List<BlockPos> beforeBridge = driftedHop(level, cursor, span.entrance, null);
                appendHop(blockPath, beforeBridge);
                bridgeSpans.add(span);
                cursor = span.exit;
            }
            // Final leg from last bridge exit to b
            List<BlockPos> afterBridge = driftedHop(level, cursor, b, null);
            appendHop(blockPath, afterBridge);
        }

        if (blockPath.isEmpty()) return List.of();

        // ── Step 3: clear trees along the centerline ────────────────────────
        for (BlockPos p : blockPath) {
            RoadRouter.clearTreesAt(level, p.getX(), p.getZ(), p.getY());
        }

        // ── Step 4: paint surface with organic zone placement ───────────────
        PathMaterial material = PathMaterial.cobblestone();
        RoadShape.RoadTier tier = RoadShape.RoadTier.TOWN_ROAD;
        RandomSource rng = RandomSource.create(
                blockPath.get(0).asLong() ^ blockPath.get(blockPath.size() - 1).asLong());
        OrganicRoadPlacer.PlacementResult result =
                OrganicRoadPlacer.place(level, blockPath, material, tier, null, rng);
        List<BlockPos> placed = result.placedBlocks();

        // ── Step 5: place any bridges that were detected ────────────────────
        for (WaterSpan span : bridgeSpans) {
            List<BlockPos> bridgeBlocks = RoadRouter.placeBridge(
                    level, span.entrance, span.exit);
            placed.addAll(bridgeBlocks);
        }

        return placed;
    }

    /**
     * Generates a subsampled drift-noise centerline between two points.
     * Subsampling to every {@link #CENTERLINE_STRIDE}-th point before handing
     * the path to {@link OrganicRoadPlacer} dramatically reduces placed block
     * counts while keeping the road shape, since the placer re-queries the
     * surface heightmap for each X,Z position it paints anyway.
     *
     * <p>If {@code data} is provided and a suitable existing road block is
     * found along the route corridor (directional, lateral, and detour checks
     * all pass), the hop routes through that block so parallel routes merge
     * rather than running side-by-side.
     */
    private static final int CENTERLINE_STRIDE = 4;

    private static List<BlockPos> driftedHop(ServerLevel level,
                                              BlockPos from, BlockPos to,
                                              VillageSavedData data) {
        if (data != null) {
            BlockPos merge = RoadRouter.findMergePoint(from, to, data);
            if (merge != null) {
                List<BlockPos> seg1 = subsample(
                        RoadPrimitive.tradeCenterline(level, from, merge, 6.0, level.getSeed()),
                        CENTERLINE_STRIDE);
                List<BlockPos> seg2 = subsample(
                        RoadPrimitive.tradeCenterline(level, merge, to, 6.0, level.getSeed()),
                        CENTERLINE_STRIDE);
                List<BlockPos> combined = new ArrayList<>(seg1);
                if (seg2.size() > 1) combined.addAll(seg2.subList(1, seg2.size()));
                return combined;
            }
        }
        return subsample(
                RoadPrimitive.tradeCenterline(level, from, to, 6.0, level.getSeed()),
                CENTERLINE_STRIDE);
    }

    /** Keeps every {@code stride}-th point, always including the last. */
    private static List<BlockPos> subsample(List<BlockPos> path, int stride) {
        if (path.size() <= 2 || stride <= 1) return path;
        List<BlockPos> out = new ArrayList<>((path.size() / stride) + 2);
        for (int i = 0; i < path.size() - 1; i += stride) {
            out.add(path.get(i));
        }
        out.add(path.get(path.size() - 1));
        return out;
    }

    /** Appends a hop's blocks, skipping the first to avoid duplicating junctions. */
    private static void appendHop(List<BlockPos> blockPath, List<BlockPos> hop) {
        if (hop.isEmpty()) return;
        if (blockPath.isEmpty()) {
            blockPath.addAll(hop);
        } else {
            blockPath.addAll(hop.subList(1, hop.size()));
        }
    }

    /**
     * Convenience for the single-cell direct-hub case. Places road
     * blocks plus any bridges detected on the direct path.
     */
    private static List<BlockPos> placeWithBridges(ServerLevel level,
                                                   List<BlockPos> blockPath,
                                                   RoadRouter.RoadQuality quality) {
        if (blockPath.isEmpty()) return List.of();
        for (BlockPos p : blockPath) {
            RoadRouter.clearTreesAt(level, p.getX(), p.getZ(), p.getY());
        }
        PathMaterial material = PathMaterial.cobblestone();
        RoadShape.RoadTier tier = RoadShape.RoadTier.TOWN_ROAD;
        RandomSource rng = RandomSource.create(blockPath.get(0).asLong());
        OrganicRoadPlacer.PlacementResult result =
                OrganicRoadPlacer.place(level, blockPath, material, tier, null, rng);
        List<BlockPos> placed = result.placedBlocks();

        // Detect bridges on the direct path itself
        if (blockPath.size() >= 2) {
            BlockPos a = blockPath.get(0);
            BlockPos b = blockPath.get(blockPath.size() - 1);
            for (WaterSpan span : detectWaterSpans(level, a, b)) {
                List<BlockPos> bridgeBlocks = RoadRouter.placeBridge(
                        level, span.entrance, span.exit);
                placed.addAll(bridgeBlocks);
            }
        }
        return placed;
    }

    // =========================================================================
    // Water span detection
    // =========================================================================

    /**
     * A contiguous water span on a straight line between two points,
     * with the entry and exit positions on dry land at the sides.
     */
    private record WaterSpan(BlockPos entrance, BlockPos exit, int length) {}

    /**
     * Walks the straight line between {@code a} and {@code b} in
     * 2-block steps and identifies water spans of {@link #BRIDGE_THRESHOLD}
     * or more contiguous water samples. Returns the entry/exit points
     * of each span, snapped to dry land where possible.
     */
    private static List<WaterSpan> detectWaterSpans(ServerLevel level,
                                                    BlockPos a, BlockPos b) {
        List<WaterSpan> spans = new ArrayList<>();
        int dx = b.getX() - a.getX();
        int dz = b.getZ() - a.getZ();
        int distSq = dx * dx + dz * dz;
        if (distSq < WATER_SAMPLE_STEP * WATER_SAMPLE_STEP) return spans;

        int steps = Math.max(1, (int) Math.sqrt(distSq) / WATER_SAMPLE_STEP);
        BlockPos waterStart = null;
        BlockPos lastDryBeforeWater = a;
        int waterStepCount = 0;

        for (int s = 0; s <= steps; s++) {
            float t = (float) s / steps;
            int x = Math.round(a.getX() + t * dx);
            int z = Math.round(a.getZ() + t * dz);
            int y = level.getHeight(
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos here = new BlockPos(x, y, z);

            boolean wet = isWaterSurface(level, here);
            if (wet) {
                if (waterStart == null) {
                    waterStart = here;
                    waterStepCount = 1;
                } else {
                    waterStepCount++;
                }
            } else {
                if (waterStart != null
                        && waterStepCount * WATER_SAMPLE_STEP >= BRIDGE_THRESHOLD) {
                    // We just exited a water span — record it
                    spans.add(new WaterSpan(
                            lastDryBeforeWater, here,
                            waterStepCount * WATER_SAMPLE_STEP));
                }
                waterStart = null;
                waterStepCount = 0;
                lastDryBeforeWater = here;
            }
        }

        // Tail span — water that runs to the end of the segment
        if (waterStart != null
                && waterStepCount * WATER_SAMPLE_STEP >= BRIDGE_THRESHOLD) {
            int y = level.getHeight(
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, b.getX(), b.getZ());
            spans.add(new WaterSpan(
                    lastDryBeforeWater,
                    new BlockPos(b.getX(), y, b.getZ()),
                    waterStepCount * WATER_SAMPLE_STEP));
        }

        return spans;
    }

    private static boolean isWaterSurface(ServerLevel level, BlockPos pos) {
        // The MOTION_BLOCKING_NO_LEAVES heightmap returns the Y of the
        // first solid surface OR the water surface, whichever is higher.
        // Over open water this gives us the surface block; we test
        // whether the block at that position is liquid.
        return level.getBlockState(pos).liquid()
                || level.getBlockState(pos.below()).liquid();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static BlockPos surfaceCenter(ServerLevel level, long cellKey) {
        BlockPos centre = AtlasRouteRouter.cellKeyToBlockCenter(cellKey);
        int y = level.getHeight(
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                centre.getX(), centre.getZ());
        return new BlockPos(centre.getX(), y, centre.getZ());
    }
}