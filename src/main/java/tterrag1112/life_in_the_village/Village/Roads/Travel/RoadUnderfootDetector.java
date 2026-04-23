package tterrag1112.life_in_the_village.Village.Roads.Travel;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadEdge;
import tterrag1112.life_in_the_village.Village.Roads.Graph.WorldRoadGraph;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Detects whether a player is standing on a realized road edge.
 *
 * <p>Detection uses the edge's persisted {@link RoadEdge#getBlockPath()} — the full
 * set of surface blocks placed by the realiser — combined with a 128-block spatial
 * pre-filter via {@link WorldRoadGraph#edgesNear}. Because roads can be several blocks
 * wide, the blockPath includes all surface blocks (not just the centreline), so an
 * exact XZ match with Y tolerance of ±1 is sufficient.
 */
public final class RoadUnderfootDetector {

    private RoadUnderfootDetector() {}

    /**
     * Returns the realized {@link RoadEdge} the player is standing on, or
     * {@code null} if the player is not on any road.
     *
     * <p>The player's "foot block" is defined as {@code player.blockPosition().below()},
     * i.e., the block directly underneath the entity's feet.
     *
     * @param player the server-side player to test
     * @param graph  the world road graph for this dimension
     * @return the edge underfoot, or {@code null}
     */
    @Nullable
    public static RoadEdge detectEdge(ServerPlayer player, WorldRoadGraph graph) {
        BlockPos foot = player.blockPosition().below();
        int x = foot.getX();
        int y = foot.getY();
        int z = foot.getZ();

        List<RoadEdge> candidates = graph.edgesNear(x, z, 128);
        for (RoadEdge edge : candidates) {
            if (!edge.isRealized()) continue;
            for (BlockPos bp : edge.getBlockPath()) {
                if (bp.getX() == x && bp.getZ() == z && Math.abs(bp.getY() - y) <= 1) {
                    return edge;
                }
            }
        }
        return null;
    }
}
