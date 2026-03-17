package tterrag1112.life_in_the_village.Village;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.Optional;

public class BuildSiteFinder {

    private static final int MAX_SEARCH_RADIUS = 128;
    private static final int STEP_SIZE = 8;
    private static final int MAX_HEIGHT_VARIATION = 4;

    /**
     * Scans outward from village bounds in a spiral to find
     * a flat area large enough for the given building footprint.
     */
    public static Optional<BlockPos> findSite(
            ServerLevel level, AABB villageBounds,
            int requiredWidth, int requiredLength) {

        // Start search from the edge of village bounds
        int centerX = (int)((villageBounds.minX + villageBounds.maxX) / 2);
        int centerZ = (int)((villageBounds.minZ + villageBounds.maxZ) / 2);
        int startRadius = (int)(Math.max(
                villageBounds.maxX - villageBounds.minX,
                villageBounds.maxZ - villageBounds.minZ
        ) / 2) + 8; // start just outside village bounds

        // Spiral outward
        for (int radius = startRadius; radius <= startRadius + MAX_SEARCH_RADIUS;
             radius += STEP_SIZE) {
            // Check points around the perimeter at this radius
            for (int angle = 0; angle < 360; angle += 15) {
                double rad = Math.toRadians(angle);
                int x = centerX + (int)(radius * Math.cos(rad));
                int z = centerZ + (int)(radius * Math.sin(rad));

                // Skip if overlaps existing village bounds
                if (villageBounds.inflate(4).intersects(
                        new AABB(x, villageBounds.minY - 10, z,
                                x + requiredWidth, villageBounds.maxY + 10,
                                z + requiredLength))) {
                    continue;
                }

                int y = findSurfaceY(level, x, z);
                if (y < 0) continue;

                BlockPos candidate = new BlockPos(x, y, z);
                if (isSiteFlat(level, candidate, requiredWidth, requiredLength)) {
                    return Optional.of(candidate);
                }
            }
        }

        return Optional.empty();
    }

    private static int findSurfaceY(ServerLevel level, int x, int z) {
        // Walk down from world height to find solid ground
        int maxY = level.getMaxY();
        for (int y = maxY; y > level.getMinY(); y--) {
            BlockPos pos = new BlockPos(x, y, z);
            BlockState state = level.getBlockState(pos);
            BlockState above = level.getBlockState(pos.above());
            if (state.isSolidRender() && above.isAir()) {
                return y + 1; // return the air block above solid ground
            }
        }
        return -1;
    }

    private static boolean isSiteFlat(ServerLevel level, BlockPos origin,
                                      int width, int length) {
        int baseY = origin.getY();
        int minY = baseY;
        int maxY = baseY;

        for (int x = 0; x < width; x++) {
            for (int z = 0; z < length; z++) {
                int surfaceY = findSurfaceY(level,
                        origin.getX() + x, origin.getZ() + z);
                if (surfaceY < 0) return false;
                minY = Math.min(minY, surfaceY);
                maxY = Math.max(maxY, surfaceY);
                if (maxY - minY > MAX_HEIGHT_VARIATION) return false;

                // Check no water or lava
                BlockState state = level.getBlockState(
                        new BlockPos(origin.getX() + x, surfaceY - 1, origin.getZ() + z)
                );
                if (state.liquid()) return false;
            }
        }

        return true;
    }
}
