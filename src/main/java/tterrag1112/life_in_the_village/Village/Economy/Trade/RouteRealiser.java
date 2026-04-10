// src/main/java/tterrag1112/life_in_the_village/Village/Economy/Trade/RouteRealiser.java
package tterrag1112.life_in_the_village.Village.Economy.Trade;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;

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

        return realiseFromWaypoints(level, rawWaypoints, quality);
    }

    /**
     * Realises a cell path between two specific endpoints. The first
     * and last waypoints become the provided hubs instead of the cell
     * centres, so the road lands precisely at the village boundaries.
     */
    public static List<BlockPos> realiseBetween(ServerLevel level,
                                                List<Long> cellPath,
                                                BlockPos startHub,
                                                BlockPos endHub,
                                                RoadRouter.RoadQuality quality) {
        if (cellPath.isEmpty()) return List.of();

        if (cellPath.size() == 1) {
            // Single-cell route — direct hub-to-hub
            List<BlockPos> direct = RoadRouter.findRoad(
                    level, startHub, endHub, 30_000);
            return placeWithBridges(level, direct, quality);
        }

        List<BlockPos> rawWaypoints = new ArrayList<>(cellPath.size());
        rawWaypoints.add(startHub);
        for (int i = 1; i < cellPath.size() - 1; i++) {
            rawWaypoints.add(surfaceCenter(level, cellPath.get(i)));
        }
        rawWaypoints.add(endHub);

        return realiseFromWaypoints(level, rawWaypoints, quality);
    }

    // =========================================================================
    // Core realisation pipeline
    // =========================================================================

    /**
     * Smooths the waypoint list, then routes between consecutive
     * smoothed waypoints, placing bridges over wide water spans.
     */
    private static List<BlockPos> realiseFromWaypoints(
            ServerLevel level,
            List<BlockPos> rawWaypoints,
            RoadRouter.RoadQuality quality) {

        // ── Step 1: smooth the waypoint sequence ────────────────────────────
        List<BlockPos> smoothed = RoutePathSmoother.smooth(level, rawWaypoints);

        // ── Step 2: route between smoothed waypoints, handling water ────────
        List<BlockPos> blockPath = new ArrayList<>();
        List<WaterSpan> bridgeSpans = new ArrayList<>();

        for (int i = 0; i < smoothed.size() - 1; i++) {
            BlockPos a = smoothed.get(i);
            BlockPos b = smoothed.get(i + 1);

            // Detect water spans on the straight line between a and b
            List<WaterSpan> spans = detectWaterSpans(level, a, b);
            if (spans.isEmpty()) {
                // Plain land hop — small budget, smoothed waypoints are close
                List<BlockPos> hop = RoadRouter.findRoad(level, a, b, 4_000);
                appendHop(blockPath, hop);
                continue;
            }

            // Water present — route to first bridge entrance, place the
            // bridge, then route from the bridge exit to the next
            // segment endpoint. Multiple spans on one segment are
            // handled by repeating this process.
            BlockPos cursor = a;
            for (WaterSpan span : spans) {
                List<BlockPos> beforeBridge = RoadRouter.findRoad(
                        level, cursor, span.entrance, 4_000);
                appendHop(blockPath, beforeBridge);
                bridgeSpans.add(span);
                cursor = span.exit;
            }
            // Final leg from the last bridge exit to b
            List<BlockPos> afterBridge = RoadRouter.findRoad(level, cursor, b, 4_000);
            appendHop(blockPath, afterBridge);
        }

        if (blockPath.isEmpty()) return List.of();

        // ── Step 3: place road blocks along the assembled path ──────────────
        List<BlockPos> placed = RoadRouter.placeRoad(level, blockPath, quality);

        // ── Step 4: place any bridges that were detected ────────────────────
        for (WaterSpan span : bridgeSpans) {
            List<BlockPos> bridgeBlocks = RoadRouter.placeBridge(
                    level, span.entrance, span.exit);
            placed.addAll(bridgeBlocks);
        }

        return placed;
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
        List<BlockPos> placed = RoadRouter.placeRoad(level, blockPath, quality);

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