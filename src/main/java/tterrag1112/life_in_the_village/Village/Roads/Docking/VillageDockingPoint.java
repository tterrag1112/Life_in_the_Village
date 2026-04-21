package tterrag1112.life_in_the_village.Village.Roads.Docking;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.VillagePath;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.List;
import java.util.UUID;

/**
 * The docking geometry for one village on a trade-road connector edge.
 *
 * <h3>Two-point model</h3>
 * The arm endpoint is where the village's own internal road terminates —
 * the last block of a CrossroadsRecipe arm, for example. The docking
 * anchor is {@link #DEFAULT_APPROACH_LENGTH} blocks further out along
 * the same direction. Trade-road realization lands at the docking anchor;
 * the {@link #DEFAULT_APPROACH_LENGTH}-block gap between anchor and arm
 * endpoint is filled by the "approach segment" using the village's own
 * road material, creating a seamless visual join.
 *
 * <h3>Arm direction detection</h3>
 * The outward arm direction is derived from the internal road network:
 * find the nearest path centerline block to the arm endpoint (within
 * 20 blocks) and compute the direction FROM that block TOWARD the arm
 * endpoint. This reliably recovers the arm axis even if the exact arm
 * primitive is not available at realization time. Fallback when no path
 * block is found: village-center → arm-endpoint direction.
 *
 * <h3>Session use</h3>
 * Instances are transient — computed at realization time, not persisted.
 */
public record VillageDockingPoint(
        BlockPos armEndpoint,
        BlockPos dockingAnchor,
        double armDirectionRadians,
        UUID villageId,
        int approachLength
) {

    public static final int DEFAULT_APPROACH_LENGTH = 30;

    /**
     * Computes the docking geometry for {@code village}.
     *
     * @param village       the village being docked
     * @param directionHint a position (typically the other village's anchor)
     *                      used to pick the gate facing the destination;
     *                      may be null if unknown
     * @param level         world reference for surface height queries
     * @param data          saved data for path lookup (arm direction detection)
     */
    public static VillageDockingPoint compute(Village village,
                                              BlockPos directionHint,
                                              ServerLevel level,
                                              VillageSavedData data) {
        BlockPos armEndpoint   = pickArmEndpoint(village, directionHint, data);
        double   armDirRad     = computeArmDirection(village, armEndpoint, data);
        BlockPos dockingAnchor = projectAnchor(level, armEndpoint, armDirRad);

        return new VillageDockingPoint(
                armEndpoint, dockingAnchor, armDirRad,
                village.getId(), DEFAULT_APPROACH_LENGTH);
    }

    // =========================================================================
    // Arm endpoint selection
    // =========================================================================

    private static BlockPos pickArmEndpoint(Village village,
                                            BlockPos directionHint,
                                            VillageSavedData data) {
        // Prefer the gate that faces toward the other village
        if (village.hasCapitalGates() && directionHint != null) {
            return village.nearestCapitalGate(directionHint.getX(), directionHint.getZ());
        }
        if (village.hasCapitalGates()) {
            return village.getCapitalGatePositions().get(0);
        }
        BlockPos gate = village.getMainGateEndpoint();
        if (gate != null) return gate;

        // Fallback: shouldn't happen for properly-planned villages
        System.out.println("[VillageDockingPoint] Warning: village "
                + village.getId() + " has no capital gate or main gate endpoint;"
                + " falling back to path hub. Check village layout planning.");
        return village.getEffectivePathHub(data);
    }

    // =========================================================================
    // Arm direction detection
    // =========================================================================

    /**
     * Finds the outward arm direction from the arm endpoint by searching
     * the village's internal road centerlines. The nearest centerline block
     * within 20 blocks of the arm endpoint (excluding the endpoint itself)
     * gives us a "predecessor" on the road. The direction FROM that block
     * TOWARD the arm endpoint is the outward direction.
     *
     * <p>Fallback when no path block is found nearby: direction from the
     * village center toward the arm endpoint.
     */
    private static double computeArmDirection(Village village,
                                              BlockPos armEndpoint,
                                              VillageSavedData data) {
        BlockPos nearest  = null;
        double   nearestD = Double.MAX_VALUE;

        List<VillagePath> paths = data.getPathsForVillage(village.getId());
        for (VillagePath path : paths) {
            for (BlockPos block : path.getCenterline()) {
                double dx = armEndpoint.getX() - block.getX();
                double dz = armEndpoint.getZ() - block.getZ();
                double d  = dx * dx + dz * dz;
                // Exclude the endpoint itself (distSq ≤ 1) and blocks > 20 blocks away
                if (d > 1.0 && d < 400.0 && d < nearestD) {
                    nearestD = d;
                    nearest  = block;
                }
            }
        }

        if (nearest != null) {
            double dx = armEndpoint.getX() - nearest.getX();
            double dz = armEndpoint.getZ() - nearest.getZ();
            return Math.atan2(dz, dx);
        }

        // Fallback: village centre → arm endpoint
        BlockPos centre = village.getAnchorPos();
        if (centre != null) {
            double dx = armEndpoint.getX() - centre.getX();
            double dz = armEndpoint.getZ() - centre.getZ();
            if (Math.abs(dx) > 0.001 || Math.abs(dz) > 0.001) {
                return Math.atan2(dz, dx);
            }
        }
        return 0.0; // last resort — east
    }

    // =========================================================================
    // Docking anchor projection
    // =========================================================================

    /** Projects the anchor {@link #DEFAULT_APPROACH_LENGTH} blocks out, surface-snapped. */
    private static BlockPos projectAnchor(ServerLevel level,
                                          BlockPos armEndpoint,
                                          double armDirRad) {
        int dx = (int) Math.round(Math.cos(armDirRad) * DEFAULT_APPROACH_LENGTH);
        int dz = (int) Math.round(Math.sin(armDirRad) * DEFAULT_APPROACH_LENGTH);
        int ax = armEndpoint.getX() + dx;
        int az = armEndpoint.getZ() + dz;
        int ay = safeHeight(level, ax, az);
        return new BlockPos(ax, ay, az);
    }

    private static int safeHeight(ServerLevel level, int x, int z) {
        if (!level.isLoaded(new BlockPos(x, 0, z))) return 64;
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
    }
}
