package tterrag1112.life_in_the_village.Village.Roads.Decoration;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadEdge;
import tterrag1112.life_in_the_village.Village.Roads.Graph.WorldRoadGraph;

import java.util.List;

/**
 * Validates that decoration placements respect road surface clearance.
 *
 * <h3>Minimum offsets</h3>
 * Each road tier has a minimum perpendicular distance from centerline at which
 * decorations may be placed. Below this distance the decoration would overlap
 * the road body (core + inner + edge zones).
 */
public final class RoadClearanceValidator {

    private RoadClearanceValidator() {}

    /**
     * Returns the minimum perpendicular distance from road centerline at which
     * decorations may be placed for the given tier. Computed as placed half-width + 1.
     */
    public static int minimumDecorationOffset(RoadEdge.EdgeTier tier) {
        return switch (tier) {
            case GREAT_ROAD -> 5;  // CAPITAL_ROAD half-width 4 + 1 clearance
            case TRUNK      -> 4;  // TOWN_ROAD half-width 3 + 1
            case CONNECTOR  -> 3;  // VILLAGE_ROAD half-width 2 + 1
            case LOCAL      -> 3;  // VILLAGE_PATH minimal footpath + 2 clearance
            // Track C3.3 — SEA edges place no blocks; no clearance
            // is meaningful.
            case SEA        -> 0;
        };
    }

    /**
     * Returns {@code true} if {@code position} is at least {@code radiusBlocks}
     * away (XZ-only) from every block in any realized road edge near the position.
     * Unrealized edges (empty blockPath) are ignored.
     *
     * @param position    decoration candidate position
     * @param graph       the world road graph to check against
     * @param radiusBlocks minimum XZ clearance required
     */
    public static boolean isClearOfRoads(BlockPos position,
                                          WorldRoadGraph graph,
                                          int radiusBlocks) {
        List<RoadEdge> nearby = graph.edgesNear(
                position.getX(), position.getZ(), radiusBlocks + 64);
        int r2 = radiusBlocks * radiusBlocks;
        for (RoadEdge edge : nearby) {
            for (BlockPos bp : edge.getBlockPath()) {
                int dx = bp.getX() - position.getX();
                int dz = bp.getZ() - position.getZ();
                if (dx * dx + dz * dz < r2) return false;
            }
        }
        return true;
    }
}
