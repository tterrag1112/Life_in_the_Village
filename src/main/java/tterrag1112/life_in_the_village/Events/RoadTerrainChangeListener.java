package tterrag1112.life_in_the_village.Events;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import tterrag1112.life_in_the_village.Life_in_the_village;
import tterrag1112.life_in_the_village.Networking.WorldRoadSavedData;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadEdge;
import tterrag1112.life_in_the_village.Village.Roads.Graph.WorldRoadGraph;
import tterrag1112.life_in_the_village.Village.Roads.Realization.RoadPlacementContext;
import tterrag1112.life_in_the_village.World.Atlas.AtlasCell;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Subscribes to world terrain-change events and marks road edges stale
 * whenever a block modification falls within a cell covered by a realized edge.
 *
 * <p>Only realized edges are marked stale — unrealized edges will simply be
 * realized fresh when a player approaches them.</p>
 */
@EventBusSubscriber(modid = Life_in_the_village.MODID)
public class RoadTerrainChangeListener {

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        markCellStale(level, event.getPos());
    }

    @SubscribeEvent
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (RoadPlacementContext.isSuppressed()) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        markCellStale(level, event.getPos());
    }

    @SubscribeEvent
    public static void onExplosion(ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        WorldRoadSavedData roadData = WorldRoadSavedData.get(level);
        WorldRoadGraph graph = roadData.getGraph();

        // Collect unique cell keys across all affected positions
        Set<Long> cellKeys = new HashSet<>();
        for (BlockPos pos : event.getAffectedBlocks()) {
            int cellX = pos.getX() >> AtlasCell.CELL_SHIFT;
            int cellZ = pos.getZ() >> AtlasCell.CELL_SHIFT;
            cellKeys.add(AtlasCell.packKey(cellX, cellZ));
        }

        boolean anyDirty = invalidateCells(graph, cellKeys);
        if (anyDirty) roadData.markDirty();
    }

    // -------------------------------------------------------------------------

    static void markCellStale(ServerLevel level, BlockPos pos) {
        WorldRoadSavedData roadData = WorldRoadSavedData.get(level);
        WorldRoadGraph graph = roadData.getGraph();

        int cellX = pos.getX() >> AtlasCell.CELL_SHIFT;
        int cellZ = pos.getZ() >> AtlasCell.CELL_SHIFT;
        long cellKey = AtlasCell.packKey(cellX, cellZ);

        Set<UUID> edgeIds = graph.edgesInCell(cellKey);
        if (edgeIds.isEmpty()) return;

        boolean anyDirty = false;
        for (UUID edgeId : edgeIds) {
            RoadEdge edge = graph.getEdge(edgeId);
            if (edge == null || !edge.isRealized()) continue;
            edge.markCellStale(cellKey);
            anyDirty = true;
        }
        if (anyDirty) roadData.markDirty();
    }

    /** Marks all realized edges that cover any of the given cells as stale. Returns true if any edge was dirtied. */
    public static boolean invalidateCells(WorldRoadGraph graph, Set<Long> cellKeys) {
        boolean anyDirty = false;
        for (long cellKey : cellKeys) {
            for (UUID edgeId : graph.edgesInCell(cellKey)) {
                RoadEdge edge = graph.getEdge(edgeId);
                if (edge == null || !edge.isRealized()) continue;
                edge.markCellStale(cellKey);
                anyDirty = true;
            }
        }
        return anyDirty;
    }
}
