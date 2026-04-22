package tterrag1112.life_in_the_village.Events;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Networking.WorldRoadSavedData;
import tterrag1112.life_in_the_village.Village.Roads.Decoration.JunctionDecorator;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadNode;
import tterrag1112.life_in_the_village.Village.Roads.Graph.WorldRoadGraph;

import java.util.List;

/**
 * Tick system that decorates {@link RoadNode.NodeType#TRUNK_JUNCTION} and
 * {@link RoadNode.NodeType#GREAT_ROAD_ANCHOR} nodes when a player comes within
 * range and the node has not yet been decorated.
 *
 * <h3>Rate limiting</h3>
 * At most one node is decorated per tick to avoid frame spikes. The system runs
 * every second (interval 20). Undecorated nodes are found opportunistically — there
 * is no queue or guarantee of ordering, and that is intentional: the first node
 * within range of any player wins.
 */
public final class NodeDecorationSystem {

    /** Player range within which a node triggers decoration. */
    private static final int DECORATION_RADIUS = 128;

    private NodeDecorationSystem() {}

    // =========================================================================
    // Tick entry
    // =========================================================================

    public static void tick(ServerLevel level, VillageSavedData villageData) {
        List<? extends ServerPlayer> players = level.players();
        if (players.isEmpty()) return;

        WorldRoadGraph graph = WorldRoadSavedData.get(level).getGraph();

        for (RoadNode node : graph.allNodes()) {
            if (node.type() != RoadNode.NodeType.TRUNK_JUNCTION
                    && node.type() != RoadNode.NodeType.GREAT_ROAD_ANCHOR) continue;
            if (!node.getDecorationPositions().isEmpty()) continue; // already decorated

            if (!isNearAnyPlayer(node, players)) continue;

            JunctionDecorator.decorate(level, node, graph, villageData);
            WorldRoadSavedData.get(level).markDirty();
            return; // one decoration per tick
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static boolean isNearAnyPlayer(RoadNode node, List<? extends ServerPlayer> players) {
        long r2 = (long) DECORATION_RADIUS * DECORATION_RADIUS;
        for (ServerPlayer p : players) {
            if (node.position().distSqr(p.blockPosition()) <= r2) return true;
        }
        return false;
    }
}
