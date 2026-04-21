package tterrag1112.life_in_the_village.Village.Roads.Graph;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Networking.WorldRoadSavedData;
import tterrag1112.life_in_the_village.Village.Economy.Trade.TradeRoad;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.*;

/**
 * One-time migration from the old pairwise {@link TradeRoad} model to the
 * new {@link WorldRoadGraph} model.
 *
 * <h3>What this migration produces</h3>
 * For each {@link TradeRoad} that has a populated cell path:
 * <ul>
 *   <li>A {@link RoadNode} of type {@code VILLAGE_DOCK} for each endpoint
 *       village (deduplicated: one node per village across all roads).</li>
 *   <li>A single {@link RoadEdge} of tier {@code CONNECTOR} connecting those
 *       two nodes. The cell path, block path, realized flag, and maintenance
 *       score are copied directly from the source {@code TradeRoad}.</li>
 * </ul>
 * Legacy TradeRoads with no cell path (pre-Phase-7a saves) are skipped.
 *
 * <h3>What this migration does NOT do</h3>
 * This migration intentionally produces a simple "star" topology with one edge
 * per village pair. It does NOT split overlapping edges, insert junctions, or
 * deduplicate parallel roads. That work belongs in Phase 4 (parallelism cleanup)
 * and Phase 3b (corridor-aware routing). Keeping migration simple reduces surface
 * area and makes rollback easier.
 *
 * <h3>Idempotency</h3>
 * The {@code migrated} flag on {@link WorldRoadSavedData} guards against re-runs.
 * The method returns immediately if already migrated, so calling it every tick is
 * safe and cheap.
 *
 * <h3>Source data preservation</h3>
 * {@link TradeRoad} instances in {@link VillageSavedData} are NOT deleted or
 * modified. They remain in place for rollback safety. A later phase removes them
 * once the graph system is stable.
 *
 * <h3>Meander profiles for migrated edges</h3>
 * Migrated edges receive amplitude=0, frequency=0, seeded with the low 64 bits
 * of the source road's UUID. The flat profile is intentional — legacy roads have
 * no planned meander, and they will be re-realized under the Phase-5 system with
 * correct profiles derived from their planned tier and culture.
 */
public final class TradeRoadMigration {

    private TradeRoadMigration() {}

    /**
     * Migrates existing {@link TradeRoad}s into the road graph if not already done.
     * Safe to call on every tick — returns immediately after the first successful run.
     */
    public static void migrateIfNeeded(ServerLevel level,
                                       VillageSavedData villageData,
                                       WorldRoadSavedData roadData) {
        if (roadData.isMigrated()) return;

        WorldRoadGraph graph = roadData.getGraph();

        // One dock node per village, created lazily within this migration run.
        Map<UUID, UUID> villageToDockNode = new HashMap<>();

        int roadsMigrated = 0;
        int edgesCreated  = 0;

        for (TradeRoad road : villageData.getAllTradeRoads()) {
            if (!road.hasCellPath()) continue; // skip pre-Phase-7a legacy roads

            UUID nodeAId = getOrCreateDockNode(
                    road.getVillageA(), villageData, graph, villageToDockNode);
            UUID nodeBId = getOrCreateDockNode(
                    road.getVillageB(), villageData, graph, villageToDockNode);

            // Seed the meander profile from the road's UUID low bits so the
            // same source road always produces the same profile, even if
            // migration is re-run on a restored backup.
            RoadEdge.MeanderProfile meander = new RoadEdge.MeanderProfile(
                    0f, 0f, road.getRoadId().getLeastSignificantBits());

            RoadEdge edge = new RoadEdge(
                    UUID.randomUUID(),
                    nodeAId, nodeBId,
                    road.getCellPath(),
                    RoadEdge.EdgeTier.CONNECTOR,
                    meander);

            edge.getMaintainerVillageIds().add(road.getVillageA());
            edge.getMaintainerVillageIds().add(road.getVillageB());
            edge.setMaintenance(road.getQuality());

            if (road.isRealised() && !road.getBlocks().isEmpty()) {
                edge.markRealized(road.getBlocks());
            }

            graph.addEdge(edge);
            roadsMigrated++;
            edgesCreated++;
        }

        int nodesCreated = villageToDockNode.size();

        roadData.setMigrated(true);
        roadData.markDirty();

        System.out.println("[RoadGraph Migration] Migrated " + roadsMigrated
                + " TradeRoads into " + nodesCreated + " nodes, "
                + edgesCreated + " edges.");
    }

    private static UUID getOrCreateDockNode(UUID villageId,
                                             VillageSavedData villageData,
                                             WorldRoadGraph graph,
                                             Map<UUID, UUID> villageToDockNode) {
        UUID existing = villageToDockNode.get(villageId);
        if (existing != null) return existing;

        BlockPos pos = villageData.getVillageById(villageId)
                .map(Village::getAnchorPos)
                .filter(Objects::nonNull)
                .orElse(BlockPos.ZERO);

        RoadNode node = new RoadNode(
                UUID.randomUUID(),
                pos,
                RoadNode.NodeType.VILLAGE_DOCK,
                Optional.empty());

        graph.addNode(node);
        villageToDockNode.put(villageId, node.nodeId());
        return node.nodeId();
    }
}
