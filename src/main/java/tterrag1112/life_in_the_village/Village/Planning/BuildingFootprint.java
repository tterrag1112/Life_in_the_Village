package tterrag1112.life_in_the_village.Village.Planning;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.RoadShape;
import tterrag1112.life_in_the_village.Village.Village;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;

import java.util.*;

/**
 * Maintains a 2D exclusion grid for a village, preventing buildings from
 * overlapping each other, roads, or reserved expansion space.
 *
 * <h3>How it works</h3>
 * The grid is a {@code Set<Long>} of packed XZ coordinates. Before placing
 * a building, its full rotated footprint (plus buffer) is tested against
 * the grid. If any overlap is found, the position is rejected and the
 * planner shifts the candidate outward.
 *
 * <h3>What occupies the grid</h3>
 * <ul>
 *   <li>Building footprints — the full XZ extent of every placed building
 *       plus a configurable buffer (default 2 blocks)</li>
 *   <li>Road reservations — the reserved width (not just placed width)
 *       of every road segment, so future road upgrades have space</li>
 *   <li>Town square — the plaza area is reserved at spawn time</li>
 * </ul>
 *
 * <h3>Lifecycle</h3>
 * Built once during village spawn from all placed buildings.
 * Updated incrementally when the builder NPC adds expansion buildings.
 * NOT persisted — rebuilt from building list on load if needed.
 *
 * <h3>Usage</h3>
 * <pre>
 * BuildingFootprint grid = BuildingFootprint.fromVillage(village, data);
 *
 * // Test a candidate position:
 * boolean clear = grid.isClear(candidatePos, width, length, buffer);
 *
 * // After placing:
 * grid.occupy(building, buffer);
 * </pre>
 */
public class BuildingFootprint {

    /** Default buffer around each building footprint (blocks). */
    public static final int DEFAULT_BUFFER = 2;

    /** Road reservation buffer — uses the road's reserved half-width. */
    public static final int ROAD_RESERVATION_HALF_WIDTH =
            RoadShape.RoadTier.TOWN_ROAD.reservedHalfWidth();

    // ── Grid storage ─────────────────────────────────────────────────────────

    private final Set<Long> occupied = new HashSet<>();
    private final Set<Long> roadReserved = new HashSet<>();

    // =========================================================================
    // Construction
    // =========================================================================

    /**
     * Builds the footprint grid from all existing buildings in a village.
     */
    public static BuildingFootprint fromVillage(Village village,
                                                VillageSavedData data) {
        BuildingFootprint grid = new BuildingFootprint();

        for (UUID buildingId : village.getBuildingIds()) {
            data.getBuildingById(buildingId).ifPresent(building ->
                    grid.occupyBuilding(building, DEFAULT_BUFFER));
        }

        return grid;
    }

    // =========================================================================
    // Building occupation
    // =========================================================================

    /**
     * Marks a building's footprint (plus buffer) as occupied.
     */
    /** Phase 22: clears all occupied + reserved cells. */
    public void clear() {
        occupied.clear();
        roadReserved.clear();
    }

    public void occupyBuilding(Building building, int buffer) {
        BlockPos origin = building.getShape().getOrigin();
        int w = building.getShape().getWidth();
        int l = building.getShape().getLength();

        for (int x = origin.getX() - buffer; x < origin.getX() + w + buffer; x++) {
            for (int z = origin.getZ() - buffer; z < origin.getZ() + l + buffer; z++) {
                occupied.add(packXZ(x, z));
            }
        }
    }

    /**
     * Marks a rectangular area as occupied (for town square, custom zones).
     */
    public void occupyRect(BlockPos corner, int width, int length, int buffer) {
        for (int x = corner.getX() - buffer; x < corner.getX() + width + buffer; x++) {
            for (int z = corner.getZ() - buffer; z < corner.getZ() + length + buffer; z++) {
                occupied.add(packXZ(x, z));
            }
        }
    }

    // =========================================================================
    // Road reservation
    // =========================================================================

    /**
     * Reserves space for a road segment. Road placer calls this for each
     * centerline position. The reserved width is the maximum future width
     * so buildings don't encroach on upgrade space.
     *
     * @param centerline the center positions of the road
     */
    public void reserveRoad(List<BlockPos> centerline, int halfWidth) {
        for (BlockPos center : centerline) {
            // Reserve a square of halfWidth around each center point
            // (works for roads in any direction)
            for (int dx = -halfWidth; dx <= halfWidth; dx++) {
                for (int dz = -halfWidth; dz <= halfWidth; dz++) {
                    roadReserved.add(packXZ(
                            center.getX() + dx, center.getZ() + dz));
                }
            }
        }
    }

    // =========================================================================
    // Collision testing
    // =========================================================================

    /**
     * Tests if a rectangular area is completely free of existing buildings
     * and road reservations.
     *
     * @param origin top-left corner of the test area
     * @param width  X extent
     * @param length Z extent
     * @param buffer additional clearance around the test area
     * @return true if the area is free
     */
    public boolean isClear(BlockPos origin, int width, int length, int buffer) {
        for (int x = origin.getX() - buffer; x < origin.getX() + width + buffer; x++) {
            for (int z = origin.getZ() - buffer; z < origin.getZ() + length + buffer; z++) {
                long key = packXZ(x, z);
                if (occupied.contains(key) || roadReserved.contains(key)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Tests if a single XZ position is occupied.
     */
    public boolean isOccupied(int x, int z) {
        long key = packXZ(x, z);
        return occupied.contains(key) || roadReserved.contains(key);
    }

    /**
     * Tests if a single XZ position is within a building footprint
     * (not counting road reservations).
     */
    public boolean isBuildingAt(int x, int z) {
        return occupied.contains(packXZ(x, z));
    }

    /**
     * Tests if a single XZ position is within a road reservation
     * (not counting building footprints).
     */
    public boolean isRoadAt(int x, int z) {
        return roadReserved.contains(packXZ(x, z));
    }

    // =========================================================================
    // Position adjustment
    // =========================================================================

    /**
     * Shifts a candidate building position outward from the village center
     * until it no longer collides. Returns null if no valid position is
     * found within maxShift blocks.
     *
     * @param candidate initial candidate position
     * @param center    village center position
     * @param width     building width (X)
     * @param length    building length (Z)
     * @param buffer    clearance buffer
     * @param maxShift  maximum outward shift in blocks
     * @return adjusted position, or null if no valid position found
     */
    public BlockPos findClearPosition(BlockPos candidate, BlockPos center,
                                      int width, int length,
                                      int buffer, int maxShift) {
        if (isClear(candidate, width, length, buffer)) return candidate;

        // Direction from center to candidate
        double dx = candidate.getX() - center.getX();
        double dz = candidate.getZ() - center.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1) { dx = 1; dz = 0; len = 1; }
        double nx = dx / len;
        double nz = dz / len;

        for (int shift = 2; shift <= maxShift; shift += 2) {
            BlockPos shifted = new BlockPos(
                    candidate.getX() + (int)(nx * shift),
                    candidate.getY(),
                    candidate.getZ() + (int)(nz * shift));

            if (isClear(shifted, width, length, buffer)) {
                return shifted;
            }
        }

        return null; // no valid position within range
    }

    // =========================================================================
    // Grid size
    // =========================================================================

    public int occupiedCount()     { return occupied.size(); }
    public int roadReservedCount() { return roadReserved.size(); }

    // =========================================================================
    // Packing — same convention as VillagePerimeter and existing code
    // =========================================================================

    private static long packXZ(int x, int z) {
        return ((long) x & 0xFFFFFFFFL) | (((long) z & 0xFFFFFFFFL) << 32);
    }
}