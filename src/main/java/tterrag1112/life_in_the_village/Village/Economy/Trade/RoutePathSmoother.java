// src/main/java/tterrag1112/life_in_the_village/Village/Economy/Trade/RoutePathSmoother.java
package tterrag1112.life_in_the_village.Village.Economy.Trade;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.List;

/**
 * Catmull-Rom interpolation for route waypoint sequences.
 *
 * <h3>Why interpolate cell centres, not dense block paths</h3>
 * The block-level path produced by {@link RoadRouter#findRoad} is
 * already locally smooth — A* within a single 64-block cell never
 * makes hard turns. The visible jagginess in pre-7b roads came from
 * the <em>direction changes between cells</em>, where one cell's
 * A* result met the next at a sharp angle.
 *
 * <p>This class addresses that by inserting curved control points
 * <em>between</em> cell centres before any inter-cell routing begins.
 * When the block router then connects the augmented waypoint list,
 * each segment is short and the overall path follows a gentle curve
 * through cell junctions.
 *
 * <h3>Tension</h3>
 * Catmull-Rom takes a tension parameter that controls how curvy the
 * interpolation is. {@link #DEFAULT_TENSION} is set conservatively
 * to keep curves close to the original cell-centre line. Higher
 * values produce broader sweeping curves that look nicer in open
 * terrain but can cause the path to drift several blocks from the
 * planned cells — risky near village boundaries. The current value
 * of 0.5 is the standard "uniform Catmull-Rom" tension and is a
 * good middle ground.
 */
public final class RoutePathSmoother {

    /** Catmull-Rom tension. 0 = very tight, 0.5 = uniform, 1 = sweeping. */
    public static final float DEFAULT_TENSION = 0.5f;

    /**
     * Number of interpolated points to insert between each pair of
     * input waypoints. Higher = smoother curves but more block-level
     * routing hops to compute. 4 produces a noticeable curve while
     * keeping the routing budget reasonable.
     */
    private static final int SUBDIVISIONS_PER_SEGMENT = 4;

    private RoutePathSmoother() {}

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Smooths a sequence of waypoints by inserting Catmull-Rom
     * interpolated control points between each consecutive pair.
     * Returns a new list with the same start and end points but
     * additional points along the curve.
     *
     * <p>The Y coordinate of each interpolated point is resolved
     * against the world surface so the smoothed path follows the
     * terrain rather than floating or burying itself.
     *
     * @param level     server level for surface Y lookup
     * @param waypoints original waypoint list (cell centres)
     * @return smoothed waypoint list
     */
    public static List<BlockPos> smooth(ServerLevel level,
                                        List<BlockPos> waypoints) {
        if (waypoints.size() < 2) return new ArrayList<>(waypoints);

        // Catmull-Rom needs four points to interpolate between the
        // middle two. For the first and last segments we duplicate
        // the endpoints to give the curve something to use.
        List<BlockPos> padded = new ArrayList<>(waypoints.size() + 2);
        padded.add(waypoints.get(0));
        padded.addAll(waypoints);
        padded.add(waypoints.get(waypoints.size() - 1));

        List<BlockPos> result = new ArrayList<>();
        result.add(waypoints.get(0));

        for (int i = 0; i < padded.size() - 3; i++) {
            BlockPos p0 = padded.get(i);
            BlockPos p1 = padded.get(i + 1);
            BlockPos p2 = padded.get(i + 2);
            BlockPos p3 = padded.get(i + 3);

            // Subdivide the curve segment between p1 and p2
            for (int s = 1; s <= SUBDIVISIONS_PER_SEGMENT; s++) {
                float t = (float) s / (SUBDIVISIONS_PER_SEGMENT + 1);
                BlockPos interp = catmullRom(p0, p1, p2, p3, t);
                int y = level.getHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        interp.getX(), interp.getZ());
                result.add(new BlockPos(interp.getX(), y, interp.getZ()));
            }

            // Add the segment endpoint at its actual surface Y
            int y2 = level.getHeight(
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    p2.getX(), p2.getZ());
            result.add(new BlockPos(p2.getX(), y2, p2.getZ()));
        }

        return result;
    }

    // =========================================================================
    // Catmull-Rom math
    // =========================================================================

    /**
     * Standard Catmull-Rom spline interpolation. Returns a point on
     * the curve passing through {@code p1} and {@code p2}, with
     * {@code p0} and {@code p3} controlling the tangents.
     *
     * <p>{@code t} is the parameter in [0, 1] — at t=0 the result
     * equals p1, at t=1 it equals p2.
     */
    private static BlockPos catmullRom(BlockPos p0, BlockPos p1,
                                       BlockPos p2, BlockPos p3,
                                       float t) {
        float t2 = t * t;
        float t3 = t2 * t;
        float tension = DEFAULT_TENSION;

        // Standard Catmull-Rom basis matrix
        float c0 = -tension * t + 2 * tension * t2 - tension * t3;
        float c1 = 1 + (tension - 3) * t2 + (2 - tension) * t3;
        float c2 = tension * t + (3 - 2 * tension) * t2 + (tension - 2) * t3;
        float c3 = -tension * t2 + tension * t3;

        float x = c0 * p0.getX() + c1 * p1.getX()
                + c2 * p2.getX() + c3 * p3.getX();
        float z = c0 * p0.getZ() + c1 * p1.getZ()
                + c2 * p2.getZ() + c3 * p3.getZ();

        return new BlockPos(Math.round(x), p1.getY(), Math.round(z));
    }
}