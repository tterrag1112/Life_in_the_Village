package tterrag1112.life_in_the_village.Village.Decoration;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import tterrag1112.life_in_the_village.Village.Building;

import java.util.*;

public class PathRouter {

    private static final int MAX_FLOOD_RADIUS = 64;
    private static final int MAX_HEIGHT_DIFF = 3;

    /**
     * Finds path blocks connecting two building entrance points
     * using flood fill that follows terrain.
     */
    public static List<BlockPos> findPath(ServerLevel level,
                                          BlockPos from, BlockPos to) {
        // BFS flood fill from both ends simultaneously
        Map<BlockPos, BlockPos> fromParents = new HashMap<>();
        Map<BlockPos, BlockPos> toParents = new HashMap<>();
        Queue<BlockPos> fromQueue = new LinkedList<>();
        Queue<BlockPos> toQueue = new LinkedList<>();

        BlockPos fromSurface = findSurface(level, from);
        BlockPos toSurface = findSurface(level, to);

        fromQueue.add(fromSurface);
        fromParents.put(fromSurface, null);
        toQueue.add(toSurface);
        toParents.put(toSurface, null);

        BlockPos meetPoint = null;
        int iterations = 0;

        while (!fromQueue.isEmpty() && !toQueue.isEmpty()
                && iterations < MAX_FLOOD_RADIUS * MAX_FLOOD_RADIUS) {
            iterations++;

            // Expand from start
            if (!fromQueue.isEmpty()) {
                BlockPos current = fromQueue.poll();
                for (BlockPos neighbor : getNeighbors(level, current)) {
                    if (!fromParents.containsKey(neighbor)) {
                        fromParents.put(neighbor, current);
                        fromQueue.add(neighbor);

                        if (toParents.containsKey(neighbor)) {
                            meetPoint = neighbor;
                            break;
                        }
                    }
                }
            }

            if (meetPoint != null) break;

            // Expand from end
            if (!toQueue.isEmpty()) {
                BlockPos current = toQueue.poll();
                for (BlockPos neighbor : getNeighbors(level, current)) {
                    if (!toParents.containsKey(neighbor)) {
                        toParents.put(neighbor, current);
                        toQueue.add(neighbor);

                        if (fromParents.containsKey(neighbor)) {
                            meetPoint = neighbor;
                            break;
                        }
                    }
                }
            }

            if (meetPoint != null) break;
        }

        if (meetPoint == null) {
            // Fallback — straight line if flood fill fails
            return straightLine(level, fromSurface, toSurface);
        }

        // Reconstruct path from both sides
        List<BlockPos> path = new ArrayList<>();

        // From start to meet point
        BlockPos current = meetPoint;
        while (current != null) {
            path.add(0, current);
            current = fromParents.get(current);
        }

        // From meet point to end
        current = toParents.get(meetPoint);
        while (current != null) {
            path.add(current);
            current = toParents.get(current);
        }

        return path;
    }

    private static List<BlockPos> getNeighbors(ServerLevel level, BlockPos pos) {
        List<BlockPos> neighbors = new ArrayList<>();
        int[][] offsets = {{1,0},{-1,0},{0,0,1},{0,0,-1}};

        for (int[] offset : offsets) {
            int nx = pos.getX() + offset[0];
            int nz = pos.getZ() + (offset.length > 2 ? offset[2] : 0);

            // Find surface Y at this XZ
            BlockPos surfacePos = findSurface(level,
                    new BlockPos(nx, pos.getY(), nz));

            // Only include if height difference is acceptable
            if (Math.abs(surfacePos.getY() - pos.getY()) <= MAX_HEIGHT_DIFF) {
                // Don't path through buildings
                if (!isInsideBuilding(level, surfacePos)) {
                    neighbors.add(surfacePos);
                }
            }
        }

        return neighbors;
    }

    private static boolean isInsideBuilding(ServerLevel level, BlockPos pos) {
        var data = tterrag1112.life_in_the_village.Networking.VillageSavedData.get(level);
        return data.getBuildingAt(pos).isPresent()
                || data.getBuildingAt(pos.below()).isPresent()
                || data.getBuildingAt(pos.above()).isPresent();
    }

    private static BlockPos findSurface(ServerLevel level, BlockPos pos) {
        int maxY = level.getMaxY();
        for (int y = Math.min(pos.getY() + 16, maxY);
             y > level.getMinY(); y--) {
            BlockPos check = new BlockPos(pos.getX(), y, pos.getZ());
            BlockState state = level.getBlockState(check);
            BlockState above = level.getBlockState(check.above());
            if (state.isSolidRender() && above.isAir()) {
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
            BlockPos surface = findSurface(level, new BlockPos(x, from.getY(), z));
            line.add(surface);
        }
        return line;
    }

    /**
     * Get the entrance point of a building — the block just outside
     * the building's origin face.
     */
    public static BlockPos getBuildingEntrance(Building building) {
        BlockPos origin = building.getShape().getOrigin();
        // Entrance is one block south of origin at ground level
        return origin.south(1);
    }
}
