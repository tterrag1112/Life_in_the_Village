// src/main/java/tterrag1112/life_in_the_village/Village/BuildSiteFinder.java
package tterrag1112.life_in_the_village.Village;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Planning.BuildingZone;
import tterrag1112.life_in_the_village.Village.Planning.ZoneRegistry;

import java.util.Optional;

/**
 * Finds a suitable placement site for an expansion building.
 *
 * <h3>Search strategy</h3>
 * <ol>
 *   <li><b>Ring-aware pass</b>: if the village has stored layout metadata
 *       ({@code villageCentre} + ring radii), search angular positions on
 *       the appropriate ring for the building's {@link BuildingZone}.
 *       This keeps expansion buildings in the same zone sectors as the
 *       original layout.</li>
 *   <li><b>Spiral fallback</b>: if ring search fails (terrain too rough,
 *       all slots occupied) or layout metadata is missing, spiral outward
 *       from village bounds as before.</li>
 * </ol>
 *
 * <h3>Y convention</h3>
 * Uses {@code MOTION_BLOCKING_NO_LEAVES} throughout — same heightmap as
 * {@code RoadRouter} and {@code VillagePlanner}.
 */
public class BuildSiteFinder {

    private static final int MAX_HEIGHT_VARIATION = 4;
    // Angular step for ring search (degrees)
    private static final int RING_ANGLE_STEP      = 15;
    // Number of rings to try beyond the preferred ring
    private static final int RING_OVERFLOW        = 1;
    // Maximum outward spiral radius beyond village bounds
    private static final int MAX_SPIRAL_RADIUS    = 128;
    private static final int SPIRAL_STEP          = 8;

    // =========================================================================
    // Main entry point
    // =========================================================================

    /**
     * Finds a placement site for a building of the given type.
     *
     * @param level          the server level
     * @param village        the village to expand (may have layout metadata)
     * @param data           saved data (used to check existing building XZ)
     * @param buildingType   the type being placed (determines zone + ring)
     * @param requiredWidth  structure width in blocks
     * @param requiredLength structure length in blocks
     * @return the solid-surface Y position of the site, or empty
     */
    public static Optional<BlockPos> findSite(
            ServerLevel level,
            Village village,
            VillageSavedData data,
            BuildingType buildingType,
            int requiredWidth,
            int requiredLength) {

        // ── Attempt 1: ring-aware search using stored layout metadata ─────────
        BlockPos centre = village.getVillageCentre();
        if (centre != null && village.getRing1Radius() > 0) {
            Optional<BlockPos> ringSite = findSiteOnRing(
                    level, village, data, buildingType,
                    requiredWidth, requiredLength, centre);
            if (ringSite.isPresent()) return ringSite;
        }

        // ── Attempt 2: spiral fallback ────────────────────────────────────────
        Optional<AABB> bounds = village.getBounds(data);
        if (bounds.isEmpty()) return Optional.empty();

        return findSiteSpiral(level, bounds.get(),
                requiredWidth, requiredLength);
    }

    /**
     * Legacy overload — used by existing callers that only have bounds.
     * Prefers ring search if the village can be found in saved data.
     */
    public static Optional<BlockPos> findSite(
            ServerLevel level, AABB villageBounds,
            int requiredWidth, int requiredLength) {
        return findSiteSpiral(level, villageBounds,
                requiredWidth, requiredLength);
    }

    // =========================================================================
    // Ring-aware search
    // =========================================================================

    private static Optional<BlockPos> findSiteOnRing(
            ServerLevel level,
            Village village,
            VillageSavedData data,
            BuildingType buildingType,
            int w, int l,
            BlockPos centre) {

        BuildingZone zone = ZoneRegistry.zoneOf(buildingType);
        int preferredRing = zone.preferredRing;

        // Try preferred ring then overflow rings
        for (int ringOffset = 0; ringOffset <= RING_OVERFLOW; ringOffset++) {
            int ringIndex = preferredRing + ringOffset;
            int radius    = ringRadiusFor(village, ringIndex);

            // Try the zone's preferred angular sector first,
            // then fall back to any angle if sector is full
            for (int pass = 0; pass < 2; pass++) {
                boolean sectorOnly = (pass == 0);

                for (int angle = 0; angle < 360; angle += RING_ANGLE_STEP) {
                    double rad = Math.toRadians(angle);

                    // Sector filter on first pass
                    if (sectorOnly && !zone.containsAngle(angle)) continue;

                    int cx = centre.getX() + (int)(Math.cos(rad) * radius);
                    int cz = centre.getZ() + (int)(Math.sin(rad) * radius);

                    int surfY = surfaceY(level, cx, cz);
                    if (surfY < 0) continue;

                    BlockPos candidate = new BlockPos(cx, surfY, cz);

                    // Reject if overlaps existing building footprints
                    if (overlapsExistingBuilding(candidate, w, l, data)) continue;

                    // Reject if terrain is too rough for the footprint
                    if (!isSiteFlat(level, candidate, w, l)) continue;

                    return Optional.of(candidate);
                }
            }
        }

        return Optional.empty();
    }

    /**
     * Returns the world-space ring radius for a given ring index (1-based).
     * Falls back gracefully if the village only has ring1 stored.
     */
    private static int ringRadiusFor(Village village, int ringIndex) {
        int r1 = village.getRing1Radius();
        int r2 = village.getRing2Radius() > 0
                ? village.getRing2Radius()
                : r1 + 20;
        // Extrapolate ring3 if needed
        int r3 = r2 + (r2 - r1);

        return switch (ringIndex) {
            case 1  -> r1;
            case 2  -> r2;
            default -> r3 + (ringIndex - 3) * 15; // extrapolate further
        };
    }

    /**
     * Returns true if a w×l footprint at {@code origin} overlaps any
     * existing building's XZ columns (ignoring Y so hillside buildings
     * don't block each other incorrectly).
     */
    private static boolean overlapsExistingBuilding(BlockPos origin,
                                                    int w, int l,
                                                    VillageSavedData data) {
        int padding = 4;
        for (Building b : data.getAllBuildings()) {
            BlockPos bMin = b.getShape().getMin();
            BlockPos bMax = b.getShape().getMax();

            // XZ AABB overlap check with padding
            boolean xOverlap = origin.getX() - padding < bMax.getX() + padding
                    && origin.getX() + w + padding > bMin.getX() - padding;
            boolean zOverlap = origin.getZ() - padding < bMax.getZ() + padding
                    && origin.getZ() + l + padding > bMin.getZ() - padding;

            if (xOverlap && zOverlap) return true;
        }
        return false;
    }

    // =========================================================================
    // Spiral fallback
    // =========================================================================

    private static Optional<BlockPos> findSiteSpiral(
            ServerLevel level, AABB villageBounds,
            int requiredWidth, int requiredLength) {

        int centerX = (int)((villageBounds.minX + villageBounds.maxX) / 2);
        int centerZ = (int)((villageBounds.minZ + villageBounds.maxZ) / 2);
        int startRadius = (int)(Math.max(
                villageBounds.maxX - villageBounds.minX,
                villageBounds.maxZ - villageBounds.minZ) / 2) + 8;

        for (int radius = startRadius;
             radius <= startRadius + MAX_SPIRAL_RADIUS;
             radius += SPIRAL_STEP) {

            for (int angle = 0; angle < 360; angle += RING_ANGLE_STEP) {
                double rad = Math.toRadians(angle);
                int x = centerX + (int)(radius * Math.cos(rad));
                int z = centerZ + (int)(radius * Math.sin(rad));

                // Skip if inside village bounds
                if (villageBounds.inflate(4).intersects(
                        new AABB(x, villageBounds.minY - 10, z,
                                x + requiredWidth,
                                villageBounds.maxY + 10,
                                z + requiredLength))) continue;

                int y = surfaceY(level, x, z);
                if (y < 0) continue;

                BlockPos candidate = new BlockPos(x, y, z);
                if (isSiteFlat(level, candidate, requiredWidth, requiredLength)) {
                    return Optional.of(candidate);
                }
            }
        }

        return Optional.empty();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Returns the solid surface block Y using MOTION_BLOCKING_NO_LEAVES.
     * Returns -1 if the column is all air or below min Y.
     */
    static int surfaceY(ServerLevel level, int x, int z) {
        int y = level.getHeight(
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        // Heightmap returns 0 for fully-air columns (void, etc.)
        return y > level.getMinY() ? y : -1;
    }

    /**
     * Returns true if the w×l footprint at {@code origin} has a height
     * variation of no more than {@link #MAX_HEIGHT_VARIATION} blocks and
     * contains no water or lava.
     */
    private static boolean isSiteFlat(ServerLevel level, BlockPos origin,
                                      int width, int length) {
        int baseY = origin.getY();
        int minY  = baseY, maxY = baseY;

        for (int dx = 0; dx < width; dx++) {
            for (int dz = 0; dz < length; dz++) {
                int sy = surfaceY(level,
                        origin.getX() + dx, origin.getZ() + dz);
                if (sy < 0) return false;

                minY = Math.min(minY, sy);
                maxY = Math.max(maxY, sy);
                if (maxY - minY > MAX_HEIGHT_VARIATION) return false;

                BlockState below = level.getBlockState(
                        new BlockPos(origin.getX() + dx, sy - 1,
                                origin.getZ() + dz));
                if (below.liquid()) return false;
            }
        }
        return true;
    }
}