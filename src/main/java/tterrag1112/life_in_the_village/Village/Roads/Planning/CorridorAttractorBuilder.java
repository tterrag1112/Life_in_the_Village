package tterrag1112.life_in_the_village.Village.Roads.Planning;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Village.Economy.Trade.AtlasRouteRouter;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadEdge;
import tterrag1112.life_in_the_village.Village.Roads.Graph.WorldRoadGraph;

import java.util.*;

/**
 * Builds corridor-aware attractor sets for {@link AtlasRouteRouter}.
 *
 * <p>Before routing a new connector, the planner calls
 * {@link #buildAttractors} to collect every graph edge cell that lies
 * within {@code corridorPaddingBlocks} (perpendicular distance) of the
 * straight line between the two endpoints. These cells are injected as
 * per-tier discount multipliers, steering the router to follow existing
 * infrastructure rather than running a parallel road alongside it.
 *
 * <p>Tier-to-discount mapping (lower = stronger attraction):
 * <ul>
 *   <li>GREAT_ROAD → 0.15 (matches the existing road-cell discount)
 *   <li>TRUNK      → 0.25
 *   <li>CONNECTOR  → 0.40
 *   <li>LOCAL      → 0.60 (mild; avoids over-committing to minor spurs)
 * </ul>
 *
 * <p>When multiple edges contribute cells to the same location, the
 * lower (stronger) discount wins.
 */
public final class CorridorAttractorBuilder {

    private CorridorAttractorBuilder() {}

    /**
     * The output of a corridor-attractor query.
     *
     * @param cellKeys  union of all qualifying cell keys
     * @param discounts per-cell multiplier; only cells in {@code cellKeys} are present
     */
    public record CorridorAttractors(Set<Long> cellKeys, Map<Long, Float> discounts) {
        /** Convenience empty instance — used when the graph has no nearby edges. */
        public static CorridorAttractors empty() {
            return new CorridorAttractors(Collections.emptySet(), Collections.emptyMap());
        }
    }

    /**
     * Builds a {@link CorridorAttractors} for the straight-line corridor
     * between {@code from} and {@code to}.
     *
     * @param graph                 the world road graph to query
     * @param from                  start of the corridor (block position)
     * @param to                    end of the corridor (block position)
     * @param corridorPaddingBlocks perpendicular tolerance; 192 = 3 atlas cells
     */
    public static CorridorAttractors buildAttractors(
            WorldRoadGraph graph,
            BlockPos from,
            BlockPos to,
            int corridorPaddingBlocks) {

        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        double dist = Math.sqrt((double) dx * dx + (double) dz * dz);

        // Sample the corridor every 256 blocks
        int sampleCount = Math.max(1, (int) Math.ceil(dist / 256.0));
        int queryRadius = corridorPaddingBlocks + 128;

        // Collect distinct edge IDs along the corridor
        Set<UUID> edgeIds = new HashSet<>();
        for (int i = 0; i <= sampleCount; i++) {
            float t = (float) i / sampleCount;
            int sx = (int)(from.getX() + dx * t);
            int sz = (int)(from.getZ() + dz * t);
            for (RoadEdge edge : graph.edgesNear(sx, sz, queryRadius)) {
                edgeIds.add(edge.getEdgeId());
            }
        }

        if (edgeIds.isEmpty()) return CorridorAttractors.empty();

        Set<Long> cellKeys = new HashSet<>();
        Map<Long, Float> discounts = new HashMap<>();

        for (UUID edgeId : edgeIds) {
            RoadEdge edge = graph.getEdge(edgeId);
            if (edge == null) continue;
            float discount = discountForTier(edge.getTier());

            for (long cellKey : edge.getCellPath()) {
                BlockPos center = AtlasRouteRouter.cellKeyToBlockCenter(cellKey);
                double perpDist = pointToSegmentDistance(
                        center.getX(), center.getZ(),
                        from.getX(), from.getZ(),
                        to.getX(), to.getZ());
                if (perpDist <= corridorPaddingBlocks) {
                    cellKeys.add(cellKey);
                    // Keep the lower (stronger) discount if the cell is claimed by multiple edges
                    discounts.merge(cellKey, discount, Math::min);
                }
            }
        }

        return new CorridorAttractors(cellKeys, discounts);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static float discountForTier(RoadEdge.EdgeTier tier) {
        return switch (tier) {
            case GREAT_ROAD -> 0.15f;
            case TRUNK      -> 0.25f;
            case CONNECTOR  -> 0.40f;
            case LOCAL      -> 0.60f;
        };
    }

    /**
     * Perpendicular distance from point (px, pz) to the line segment
     * (ax, az)→(bx, bz). Returns the distance to the nearest endpoint
     * when the projection falls outside the segment.
     */
    static double pointToSegmentDistance(
            int px, int pz,
            int ax, int az,
            int bx, int bz) {
        double sdx = bx - ax;
        double sdz = bz - az;
        double lenSq = sdx * sdx + sdz * sdz;
        if (lenSq < 1e-9) {
            double dpx = px - ax, dpz = pz - az;
            return Math.sqrt(dpx * dpx + dpz * dpz);
        }
        double t = Math.max(0.0, Math.min(1.0,
                ((px - ax) * sdx + (pz - az) * sdz) / lenSq));
        double cx = ax + t * sdx;
        double cz = az + t * sdz;
        double ex = px - cx, ez = pz - cz;
        return Math.sqrt(ex * ex + ez * ez);
    }
}
