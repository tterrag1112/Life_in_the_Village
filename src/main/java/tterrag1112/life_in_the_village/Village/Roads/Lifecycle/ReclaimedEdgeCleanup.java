package tterrag1112.life_in_the_village.Village.Roads.Lifecycle;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import tterrag1112.life_in_the_village.Networking.WorldRoadSavedData;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadEdge;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadNode;
import tterrag1112.life_in_the_village.Village.Roads.Graph.WorldRoadGraph;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Phase 9 — periodically removes RECLAIMED edges from the graph after their
 * grace period has elapsed. The grace period is
 * {@link DeadEdgeState#RECLAIM_GRACE_DAYS} days after entering RECLAIMED so
 * players can stumble on the trace before it's gone.
 *
 * <h3>What gets removed</h3>
 * <ul>
 *   <li>The edge itself, via {@link WorldRoadGraph#removeEdge}.</li>
 *   <li>Any orphan TERMINUS / POI_STUB / WAYSTATION / TRUNK_JUNCTION nodes
 *       that no longer have an incident edge after this removal. Gateway
 *       links to existing villages are preserved (the village still exists).</li>
 *   <li>Best-effort despawn of placed road blocks: replaces each known block
 *       in {@link RoadEdge#getBlockPath()} with grass_block / dirt where the
 *       chunk is loaded; quietly skips unloaded positions.</li>
 * </ul>
 */
public final class ReclaimedEdgeCleanup {

    private ReclaimedEdgeCleanup() {}

    /** Cleanup cadence — once per in-game month is plenty. */
    public static final long CLEANUP_INTERVAL_TICKS = DeadEdgeState.TICKS_PER_DAY * 30L;

    private static long lastCleanupTick = -CLEANUP_INTERVAL_TICKS;

    public static int maybeCleanup(ServerLevel level) {
        long tick = level.getGameTime();
        if (tick - lastCleanupTick < CLEANUP_INTERVAL_TICKS) return 0;
        lastCleanupTick = tick;
        return cleanupReclaimedEdges(level);
    }

    public static int cleanupReclaimedEdges(ServerLevel level) {
        WorldRoadGraph graph = WorldRoadSavedData.get(level).getGraph();
        long currentTick = level.getGameTime();
        long graceTicks = DeadEdgeState.TICKS_PER_DAY * DeadEdgeState.RECLAIM_GRACE_DAYS;

        List<UUID> toRemove = new ArrayList<>();
        Set<UUID> nodesToCheck = new HashSet<>();
        for (RoadEdge edge : graph.allEdges()) {
            if (!edge.isDead()) continue;
            DeadEdgeState state = edge.getDeadState().orElse(null);
            if (state == null) continue;
            if (state.phaseAtTick(currentTick) != DeadEdgeState.DeathPhase.RECLAIMED) continue;
            long reclaimedAt = state.reclaimedAt().orElse(currentTick);
            if (currentTick - reclaimedAt < graceTicks) continue;

            toRemove.add(edge.getEdgeId());
            nodesToCheck.add(edge.getNodeAId());
            nodesToCheck.add(edge.getNodeBId());
        }

        for (UUID edgeId : toRemove) {
            RoadEdge edge = graph.getEdge(edgeId);
            if (edge == null) continue;
            despawnEdgeBlocks(level, edge);
            graph.removeEdge(edgeId);
            DeadEdgeDetector.forget(edgeId);
            EdgeDeathEvent.fire(new EdgeDeathEvent.Event(
                    edgeId, edge.getTier(),
                    EdgeDeathEvent.Kind.REMOVED,
                    DeadEdgeState.DeathPhase.RECLAIMED, currentTick));
        }

        if (!toRemove.isEmpty()) {
            removeOrphanNodes(graph, nodesToCheck);
            WorldRoadSavedData.get(level).markDirty();
            System.out.println("[ReclaimedEdgeCleanup] tick=" + currentTick
                    + " removed=" + toRemove.size());
        }
        return toRemove.size();
    }

    /**
     * Replaces each block in the edge's known block path with a natural surface
     * block. Best-effort — skips unloaded chunks. The despawn is intentionally
     * gentle (grass/dirt) so the world still has a place where the road was.
     */
    public static void despawnEdgeBlocks(ServerLevel level, RoadEdge edge) {
        int n = edge.getBlockPath().size();
        if (n == 0) return;
        for (int i = 0; i < n; i++) {
            BlockPos pos = edge.getBlockPath().get(i);
            if (!level.isLoaded(pos)) continue;
            // Replace road surface with grass; keep adjacent dirt unchanged.
            // The pos is at surface Y (the air column above the road block in
            // some pipelines), so step down by one to hit the road block.
            BlockPos roadBlock = pos.below();
            if (level.isLoaded(roadBlock) && !level.getBlockState(roadBlock).isAir()) {
                level.setBlock(roadBlock, Blocks.GRASS_BLOCK.defaultBlockState(), 3);
            }
        }
    }

    /**
     * Removes nodes from the graph that have no incident edges. Preserves
     * {@code VILLAGE_DOCK} and gateway-linked TERMINUS nodes (the village
     * still exists; future re-connection may want them).
     */
    private static void removeOrphanNodes(WorldRoadGraph graph, Set<UUID> candidates) {
        Set<UUID> nodesWithEdges = new HashSet<>();
        for (RoadEdge e : graph.allEdges()) {
            nodesWithEdges.add(e.getNodeAId());
            nodesWithEdges.add(e.getNodeBId());
        }
        for (UUID id : candidates) {
            if (nodesWithEdges.contains(id)) continue;
            RoadNode n = graph.getNode(id);
            if (n == null) continue;
            // Keep village docks and any node still linked to a gateway.
            if (n.type() == RoadNode.NodeType.VILLAGE_DOCK) continue;
            if (n.gatewayLink().isPresent()) continue;
            graph.removeNode(id);
        }
    }
}
