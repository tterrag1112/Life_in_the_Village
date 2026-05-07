package tterrag1112.life_in_the_village.Village.Planning.V2.Layer4;

import net.minecraft.core.BlockPos;

/**
 * V2 Layer 4 — shared road corridor geometry.
 *
 * <p>{@link #intersects} answers "does the rectangle
 * {@code (minX, minZ)..(maxX, maxZ)} overlap the road
 * corridor of {@code corridorHalf} blocks around the segment
 * {@code start}→{@code end}?". Used by:
 * <ul>
 *   <li>{@code PhasedPlanner.findBestCandidate} — reject
 *       candidate building footprints whose AABB intersects
 *       any skeleton segment's corridor before placement.</li>
 *   <li>{@code OverlapAuditor} — flag the same intersection
 *       on already-placed buildings as a final sanity check.</li>
 * </ul>
 *
 * <p>Implementation: walks the segment at 1-block resolution.
 * If any sample point (expanded perpendicular by the AABB by
 * {@code corridorHalf} on each side) is inside the AABB, the
 * corridor crosses. Approximate; precision is fine for V1's
 * short segments and small AABBs.
 */
public final class RoadCorridors {

    /** Walk step in blocks. */
    private static final int SAMPLE_STEP = 1;

    private RoadCorridors() {}

    /** True iff the corridor of half-width {@code corridorHalf}
     *  around the segment {@code start}→{@code end} overlaps the
     *  axis-aligned rectangle {@code (minX, minZ)..(maxX, maxZ)}. */
    public static boolean intersects(BlockPos start, BlockPos end, int corridorHalf,
                                     int minX, int minZ, int maxX, int maxZ) {
        double dx = end.getX() - start.getX();
        double dz = end.getZ() - start.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1e-9) return aabbContains(minX, minZ, maxX, maxZ,
                start.getX(), start.getZ(), corridorHalf);
        int steps = Math.max(1, (int) Math.round(len / SAMPLE_STEP));
        for (int i = 0; i <= steps; i++) {
            double t = i / (double) steps;
            int x = (int) Math.round(start.getX() + dx * t);
            int z = (int) Math.round(start.getZ() + dz * t);
            if (aabbContains(minX, minZ, maxX, maxZ, x, z, corridorHalf)) return true;
        }
        return false;
    }

    /** True iff the AABB expanded by {@code halfWidth} on each
     *  side contains {@code (px, pz)}. */
    private static boolean aabbContains(int minX, int minZ, int maxX, int maxZ,
                                        int px, int pz, int halfWidth) {
        return px >= minX - halfWidth && px <= maxX + halfWidth
                && pz >= minZ - halfWidth && pz <= maxZ + halfWidth;
    }
}
