package tterrag1112.life_in_the_village.Events;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Networking.WorldRoadSavedData;
import tterrag1112.life_in_the_village.Village.Roads.Decoration.JunctionDecorator;
import tterrag1112.life_in_the_village.Village.Roads.Decoration.RoadOvergrowthDecorator;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadEdge;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadNode;
import tterrag1112.life_in_the_village.Village.Roads.Graph.WorldRoadGraph;

import java.util.List;

/**
 * Tick system for ambient road decorations.
 *
 * <h3>Node decorations (first-time)</h3>
 * Decorates {@link RoadNode.NodeType#TRUNK_JUNCTION} and
 * {@link RoadNode.NodeType#GREAT_ROAD_ANCHOR} nodes when a player comes within
 * {@link #DECORATION_RADIUS} blocks and the node has not yet been decorated.
 * At most one node is decorated per invocation (rate-limited).
 *
 * <h3>Edge overgrowth refresh (maintenance band change)</h3>
 * When the upkeep system crosses a Phase-6b maintenance band boundary it sets
 * {@link RoadEdge#isNeedsDecorationRefresh()}. This system picks up that flag,
 * removes existing overgrowth decorations from the world (blocks at stored
 * decoration positions), clears the positions, and re-runs
 * {@link RoadOvergrowthDecorator} — but only when the edge is in player range.
 * Milestones (placed by {@link tterrag1112.life_in_the_village.Village.Roads.Decoration.MilestoneDecorator})
 * are permanent Old Realm features; they are not cleared by a refresh.
 * At most one edge is refreshed per invocation, after the node pass.
 */
public final class NodeDecorationSystem {

    /** Player range within which a node or edge triggers decoration. */
    private static final int DECORATION_RADIUS = 128;

    private NodeDecorationSystem() {}

    // =========================================================================
    // Tick entry
    // =========================================================================

    public static void tick(ServerLevel level, VillageSavedData villageData) {
        List<? extends ServerPlayer> players = level.players();
        if (players.isEmpty()) return;

        WorldRoadSavedData roadData = WorldRoadSavedData.get(level);
        WorldRoadGraph graph = roadData.getGraph();

        // ── Node decoration pass (first-time) ─────────────────────────────────
        for (RoadNode node : graph.allNodes()) {
            if (node.type() != RoadNode.NodeType.TRUNK_JUNCTION
                    && node.type() != RoadNode.NodeType.GREAT_ROAD_ANCHOR) continue;
            if (!node.getDecorationPositions().isEmpty()) continue;
            if (!isNearAnyPlayer(node.position(), players)) continue;

            JunctionDecorator.decorate(level, node, graph, villageData);
            roadData.markDirty();
            return; // one decoration per tick
        }

        // ── Edge overgrowth refresh pass (maintenance band changed) ───────────
        for (RoadEdge edge : graph.allEdges()) {
            if (!edge.isNeedsDecorationRefresh()) continue;
            if (edge.getBlockPath().isEmpty()) continue;
            if (!isEdgeNearAnyPlayer(edge, players)) continue;

            refreshEdgeOvergrowth(level, edge, graph);
            edge.setNeedsDecorationRefresh(false);
            roadData.markDirty();
            return; // one edge refresh per tick
        }
    }

    // =========================================================================
    // Overgrowth refresh
    // =========================================================================

    /**
     * Removes existing overgrowth blocks from the world (clears stored decoration
     * positions that are not milestone-style stone blocks), then re-runs
     * {@link RoadOvergrowthDecorator} with the current maintenance score.
     */
    private static void refreshEdgeOvergrowth(ServerLevel level, RoadEdge edge,
                                               WorldRoadGraph graph) {
        // Remove previously placed overgrowth blocks from the world.
        // We only remove vegetation/debris blocks — stone milestones are kept.
        List<BlockPos> positions = new java.util.ArrayList<>(edge.getDecorationPositions());
        for (BlockPos pos : positions) {
            if (!level.isLoaded(pos)) continue;
            var state = level.getBlockState(pos);
            if (isRemovableOvergrowth(state)) {
                level.removeBlock(pos, false);
            }
        }

        // Clear all decoration positions so the decorator starts fresh.
        // Milestone positions are also cleared here; MilestoneDecorator is NOT re-run
        // (milestones are permanent features, not maintenance-linked), so they will
        // simply remain absent from the list without being re-placed.
        edge.clearDecorationPositions();

        // Re-run overgrowth decorator with current maintenance
        RoadOvergrowthDecorator.decorate(level, edge, graph);
    }

    private static boolean isRemovableOvergrowth(net.minecraft.world.level.block.state.BlockState state) {
        var block = state.getBlock();
        return block == net.minecraft.world.level.block.Blocks.SHORT_GRASS
                || block == net.minecraft.world.level.block.Blocks.TALL_GRASS
                || block == net.minecraft.world.level.block.Blocks.OAK_SAPLING
                || block == net.minecraft.world.level.block.Blocks.SPRUCE_SAPLING
                || block == net.minecraft.world.level.block.Blocks.OAK_LEAVES
                || block == net.minecraft.world.level.block.Blocks.DEAD_BUSH
                || block == net.minecraft.world.level.block.Blocks.SNOW
                || block == net.minecraft.world.level.block.Blocks.MOSS_BLOCK
                || block == net.minecraft.world.level.block.Blocks.STRIPPED_OAK_LOG
                || block == net.minecraft.world.level.block.Blocks.STRIPPED_SPRUCE_LOG
                || block == net.minecraft.world.level.block.Blocks.STRIPPED_ACACIA_LOG
                || block == net.minecraft.world.level.block.Blocks.STRIPPED_DARK_OAK_LOG
                || block == net.minecraft.world.level.block.Blocks.COARSE_DIRT;
    }

    // =========================================================================
    // Proximity helpers
    // =========================================================================

    private static boolean isNearAnyPlayer(BlockPos pos, List<? extends ServerPlayer> players) {
        long r2 = (long) DECORATION_RADIUS * DECORATION_RADIUS;
        for (ServerPlayer p : players) {
            if (pos.distSqr(p.blockPosition()) <= r2) return true;
        }
        return false;
    }

    private static boolean isEdgeNearAnyPlayer(RoadEdge edge, List<? extends ServerPlayer> players) {
        // Sample midpoint of the block path for a fast proximity check
        List<BlockPos> path = edge.getBlockPath();
        if (path.isEmpty()) return false;
        BlockPos mid = path.get(path.size() / 2);
        return isNearAnyPlayer(mid, players);
    }
}
