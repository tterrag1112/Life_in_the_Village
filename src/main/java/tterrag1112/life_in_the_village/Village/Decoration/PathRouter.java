// src/main/java/tterrag1112/life_in_the_village/Village/Decoration/PathRouter.java
package tterrag1112.life_in_the_village.Village.Decoration;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Rotation;
import tterrag1112.life_in_the_village.Village.Building;

import java.util.*;

/**
 * Lightweight path utilities for internal village paths.
 *
 * <p>Note: for all street routing use
 * {@link tterrag1112.life_in_the_village.Village.Economy.Trade.RoadRouter#findRoad}
 * (A*) which handles slopes, cost weighting, and partial paths correctly.
 * {@link #findPath} (BFS) is retained for short inter-building connections
 * only.
 */
public class PathRouter {

    private static final int MAX_FLOOD_RADIUS = 64;
    private static final int MAX_HEIGHT_DIFF  = 3;

    // =========================================================================
    // Building entrance calculation
    // =========================================================================

    /**
     * Returns the walkable position immediately outside a building's entrance.
     *
     * <h3>Convention</h3>
     * <ul>
     *   <li>{@code origin} = NW corner of the placed structure in world
     *       space (from {@link
     *       tterrag1112.life_in_the_village.Village.BuildingPlacer}).</li>
     *   <li>{@code origin.getY()} = solid floor block Y
     *       (MOTION_BLOCKING_NO_LEAVES convention).</li>
     *   <li>Entrance walkable Y = {@code floorY + 1} (air above the floor).</li>
     *   <li>With {@code Rotation.NONE} the entrance faces <b>south</b>
     *       (+Z). Other rotations are handled accordingly.</li>
     * </ul>
     *
     * <h3>Entrance XZ</h3>
     * The entrance is at the centre of the face the door is on,
     * one block outside that face.
     * <pre>
     *  NONE          → south face centre  (+Z side, mid X)
     *  CLOCKWISE_180 → north face centre  (-Z side, mid X)
     *  CLOCKWISE_90  → east face centre   (+X side, mid Z)
     *  CCW_90        → west face centre   (-X side, mid Z)
     * </pre>
     */
    public static BlockPos getBuildingEntrance(Building building) {
        BlockPos origin = building.getShape().getOrigin();
        int      width  = building.getShape().getWidth();
        int      length = building.getShape().getLength();
        // Walkable Y = one above the solid floor block
        int      walkY  = origin.getY() + 1;

        // Centre of each face + 1 block outside
        return switch (building.getRotation()) {
            case NONE ->
                // South face: X = origin.X + width/2, Z = origin.Z + length
                    new BlockPos(
                            origin.getX() + width  / 2,
                            walkY,
                            origin.getZ() + length);

            case CLOCKWISE_180 ->
                // North face: X = origin.X + width/2, Z = origin.Z - 1
                    new BlockPos(
                            origin.getX() + width / 2,
                            walkY,
                            origin.getZ() - 1);

            case CLOCKWISE_90 ->
                // East face: X = origin.X + width, Z = origin.Z + length/2
                    new BlockPos(
                            origin.getX() + width,
                            walkY,
                            origin.getZ() + length / 2);

            case COUNTERCLOCKWISE_90 ->
                // West face: X = origin.X - 1, Z = origin.Z + length/2
                    new BlockPos(
                            origin.getX() - 1,
                            walkY,
                            origin.getZ() + length / 2);

            default ->
                // Fallback: south face
                    new BlockPos(
                            origin.getX() + width / 2,
                            walkY,
                            origin.getZ() + length);
        };
    }

    // =========================================================================
    // BFS path finder (short inter-building paths only)
    // =========================================================================

    /**
     * Finds a path between two points using bidirectional BFS.
     * Suitable for short paths (≤ {@value MAX_FLOOD_RADIUS} blocks).
     * For longer routes use
     * {@link tterrag1112.life_in_the_village.Village.Economy.Trade.RoadRouter#findRoad}.
     */
    public static List<BlockPos> findPath(ServerLevel level,
                                          BlockPos from, BlockPos to) {
        Map<BlockPos, BlockPos> fromParents = new HashMap<>();
        Map<BlockPos, BlockPos> toParents   = new HashMap<>();
        Queue<BlockPos> fromQueue = new LinkedList<>();
        Queue<BlockPos> toQueue   = new LinkedList<>();

        BlockPos fromSurface = findSurface(level, from);
        BlockPos toSurface   = findSurface(level, to);

        fromQueue.add(fromSurface);
        fromParents.put(fromSurface, null);
        toQueue.add(toSurface);
        toParents.put(toSurface, null);

        BlockPos meetPoint  = null;
        int      iterations = 0;
        int      limit      = MAX_FLOOD_RADIUS * MAX_FLOOD_RADIUS;

        while (!fromQueue.isEmpty() && !toQueue.isEmpty()
                && iterations < limit) {
            iterations++;

            if (!fromQueue.isEmpty()) {
                BlockPos current = fromQueue.poll();
                for (BlockPos nb : getNeighbors(level, current)) {
                    if (!fromParents.containsKey(nb)) {
                        fromParents.put(nb, current);
                        fromQueue.add(nb);
                        if (toParents.containsKey(nb)) {
                            meetPoint = nb;
                            break;
                        }
                    }
                }
            }
            if (meetPoint != null) break;

            if (!toQueue.isEmpty()) {
                BlockPos current = toQueue.poll();
                for (BlockPos nb : getNeighbors(level, current)) {
                    if (!toParents.containsKey(nb)) {
                        toParents.put(nb, current);
                        toQueue.add(nb);
                        if (fromParents.containsKey(nb)) {
                            meetPoint = nb;
                            break;
                        }
                    }
                }
            }
            if (meetPoint != null) break;
        }

        if (meetPoint == null) {
            return straightLine(level, fromSurface, toSurface);
        }

        List<BlockPos> path = new ArrayList<>();
        BlockPos cur = meetPoint;
        while (cur != null) { path.add(0, cur); cur = fromParents.get(cur); }
        cur = toParents.get(meetPoint);
        while (cur != null) { path.add(cur); cur = toParents.get(cur); }
        return path;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static List<BlockPos> getNeighbors(ServerLevel level,
                                               BlockPos pos) {
        List<BlockPos> neighbors = new ArrayList<>();
        int[][] offsets = {{1,0},{-1,0},{0,1},{0,-1}};
        for (int[] off : offsets) {
            int nx = pos.getX() + off[0];
            int nz = pos.getZ() + off[1];
            BlockPos surface = findSurface(level, new BlockPos(nx, pos.getY(), nz));
            if (Math.abs(surface.getY() - pos.getY()) <= MAX_HEIGHT_DIFF
                    && !isInsideBuilding(level, surface)) {
                neighbors.add(surface);
            }
        }
        return neighbors;
    }

    private static boolean isInsideBuilding(ServerLevel level, BlockPos pos) {
        var data = tterrag1112.life_in_the_village.Networking
                .VillageSavedData.get(level);
        return data.getBuildingAt(pos).isPresent()
                || data.getBuildingAt(pos.below()).isPresent()
                || data.getBuildingAt(pos.above()).isPresent();
    }

    private static BlockPos findSurface(ServerLevel level, BlockPos pos) {
        for (int y = Math.min(pos.getY() + 16, level.getMaxY());
             y > level.getMinY(); y--) {
            BlockPos check = new BlockPos(pos.getX(), y, pos.getZ());
            BlockState above = level.getBlockState(check.above());
            if (level.getBlockState(check).isSolidRender()
                    && above.isAir()) {
                return check.above();
            }
        }
        return pos;
    }

    private static List<BlockPos> straightLine(ServerLevel level,
                                               BlockPos from, BlockPos to) {
        List<BlockPos> line = new ArrayList<>();
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        int steps = Math.max(Math.abs(dx), Math.abs(dz));
        if (steps == 0) return line;
        for (int i = 0; i <= steps; i++) {
            int x = from.getX() + dx * i / steps;
            int z = from.getZ() + dz * i / steps;
            line.add(findSurface(level, new BlockPos(x, from.getY(), z)));
        }
        return line;
    }
}