package tterrag1112.life_in_the_village.Village.Economy.Trade;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Networking.WorldRoadSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadEdge;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadNode;
import tterrag1112.life_in_the_village.Village.Roads.Graph.WorldRoadGraph;
import tterrag1112.life_in_the_village.Village.Village;
import tterrag1112.life_in_the_village.World.Atlas.WorldAtlas;

import java.util.*;

/**
 * Establishes and maintains sea trade routes between villages with docks.
 *
 * <p>Land routes are produced by the road-graph connector pipeline
 * ({@link tterrag1112.life_in_the_village.Village.Roads.Planning.ConnectorPlanner})
 * and are not handled here. This class survives only for sea-route
 * establishment until C3 / Phase 13 folds sea into the world graph.
 */
public class TradeRouteManager {

    /** Padding around the start/end blocks for atlas pre-fill, in blocks. */
    private static final int ATLAS_PREFILL_PADDING = 256;

    /** Per-call atlas fill budget when establishing a route. */
    private static final long ATLAS_PREFILL_BUDGET_NS = 50_000_000L; // 50ms

    // =========================================================================
    // Establish routes for a new village
    // =========================================================================

    public static void establishRoutes(ServerLevel level,
                                       Village newVillage,
                                       VillageSavedData data) {
        if (hasDock(newVillage, data)) {
            establishSeaRoutes(level, newVillage, data);
        }
    }
    private static boolean hasDock(Village village, VillageSavedData data) {
        for (UUID buildingId : village.getBuildingIds()) {
            var building = data.getBuildingById(buildingId).orElse(null);
            if (building != null && building.getType() == BuildingType.DOCKS) {
                return true;
            }
        }
        return false;
    }

    /**
     * Track C3.3 — establishes a sea route as a SEA-tier {@link RoadEdge}
     * directly on the world graph instead of as a separate
     * {@code SeaRoute} record. The dock-as-trigger condition is unchanged.
     *
     * <p>For each pair of dock-building villages, the routing pipeline:
     * <ol>
     *   <li>Asks {@link SeaRouteRouter#findRoute} for a cellPath through
     *       ocean cells. (Same A* implementation; just produces a path
     *       that becomes a graph edge instead of a SeaRoute record.)</li>
     *   <li>Creates two {@code VILLAGE_DOCK} nodes at the cellPath
     *       endpoints — these are the boat's outbound and inbound
     *       waypoints. The dock-building UUIDs themselves are not
     *       persisted on the edge (re-scanned at dispatch time).</li>
     *   <li>Creates a SEA-tier {@link RoadEdge} between those nodes
     *       carrying the cellPath; both villages become maintainers so
     *       the existing upkeep loop pays for harbour maintenance.</li>
     *   <li>Wraps the new edge in a {@link TradeRoute} via
     *       {@link TradeRoute#createGraph} with a single-element
     *       {@code edgeIds} list.</li>
     * </ol>
     */
    private static void establishSeaRoutes(ServerLevel level,
                                           Village originVillage,
                                           VillageSavedData data) {
        UUID originDockId = findDockBuilding(originVillage, data);
        if (originDockId == null) return;

        BlockPos originDockPos = data.getBuildingById(originDockId)
                .map(Building::getShape)
                .map(Building.BuildingShape::getOrigin)
                .orElse(originVillage.getAnchorPos());

        WorldAtlas atlas = WorldAtlas.get(level);
        WorldRoadGraph graph = WorldRoadSavedData.get(level).getGraph();

        for (Village other : data.getAllVillages()) {
            if (other.getId().equals(originVillage.getId())) continue;
            if (!hasDock(other, data)) continue;

            if (seaEdgeExistsBetween(graph, originVillage.getId(), other.getId())) continue;

            UUID destDockId = findDockBuilding(other, data);
            if (destDockId == null) continue;
            BlockPos destDockPos = data.getBuildingById(destDockId)
                    .map(Building::getShape)
                    .map(Building.BuildingShape::getOrigin)
                    .orElse(other.getAnchorPos());

            List<Long> cellPath = SeaRouteRouter.findRoute(
                    atlas, originDockPos, destDockPos);
            if (cellPath.isEmpty()) continue;

            BlockPos endA = AtlasRouteRouter.cellKeyToBlockCenter(cellPath.get(0));
            BlockPos endB = AtlasRouteRouter.cellKeyToBlockCenter(cellPath.get(cellPath.size() - 1));
            int seaLevel = level.getSeaLevel();
            BlockPos waypointA = new BlockPos(endA.getX(), seaLevel, endA.getZ());
            BlockPos waypointB = new BlockPos(endB.getX(), seaLevel, endB.getZ());

            UUID nodeAId = createSeaDockNode(graph, waypointA, originVillage.getId());
            UUID nodeBId = createSeaDockNode(graph, waypointB, other.getId());

            long meanderSeed = (long) originVillage.getId().getMostSignificantBits()
                    ^ (long) other.getId().getLeastSignificantBits();
            RoadEdge seaEdge = RoadEdge.create(
                    nodeAId, nodeBId,
                    cellPath, RoadEdge.EdgeTier.SEA,
                    new RoadEdge.MeanderProfile(0f, 0f, meanderSeed));
            seaEdge.setMaintenance(100);
            seaEdge.getMaintainerVillageIds().add(originVillage.getId());
            seaEdge.getMaintainerVillageIds().add(other.getId());
            graph.addEdge(seaEdge);
            WorldRoadSavedData.get(level).markDirty();

            TradeRoute tradeRoute = TradeRoute.createGraph(
                    originVillage.getId(), other.getId(),
                    TradeRoute.RouteType.NEUTRAL, level.getGameTime(),
                    List.of(seaEdge.getEdgeId()), nodeAId);
            data.addTradeRoute(tradeRoute);

            System.out.println("TradeRouteManager: established sea edge "
                    + originVillage.getName() + " ↔ " + other.getName()
                    + " (" + cellPath.size() + " cells, edge="
                    + seaEdge.getEdgeId().toString().substring(0, 8) + ")");
        }
    }

    /** Track C3.3 — true if a SEA edge already connects these two villages. */
    private static boolean seaEdgeExistsBetween(WorldRoadGraph graph,
                                                  UUID villageA, UUID villageB) {
        for (RoadEdge edge : graph.allEdges()) {
            if (edge.getTier() != RoadEdge.EdgeTier.SEA) continue;
            List<UUID> maintainers = edge.getMaintainerVillageIds();
            if (maintainers.contains(villageA) && maintainers.contains(villageB)) {
                return true;
            }
        }
        return false;
    }

    /** Track C3.3 — creates a fresh VILLAGE_DOCK node at {@code pos}. */
    private static UUID createSeaDockNode(WorldRoadGraph graph, BlockPos pos,
                                            UUID villageId) {
        RoadNode node = new RoadNode(UUID.randomUUID(), pos,
                RoadNode.NodeType.VILLAGE_DOCK, Optional.empty());
        graph.addNode(node);
        return node.nodeId();
    }

    private static UUID findDockBuilding(Village village, VillageSavedData data) {
        for (UUID buildingId : village.getBuildingIds()) {
            var building = data.getBuildingById(buildingId).orElse(null);
            if (building != null && building.getType() == BuildingType.DOCKS) {
                return buildingId;
            }
        }
        return null;
    }

    // =========================================================================
    // Atlas pre-fill
    // =========================================================================

    /**
     * Pre-fills the atlas along the straight-line corridor between two
     * villages. Walks the line in 800-block hops and calls
     * {@link WorldAtlas#ensureRegionFilled} at each. The total budget
     * is split across hops so the server tick isn't starved.
     */
    public static void prefillAtlasCorridor(ServerLevel level,
                                            WorldAtlas atlas,
                                            BlockPos from,
                                            BlockPos to) {
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        int hopSize = 800;
        int hops = Math.max(1, (int) Math.ceil(dist / hopSize));

        long perHopBudget = ATLAS_PREFILL_BUDGET_NS / hops;

        for (int i = 0; i <= hops; i++) {
            float t = (float) i / hops;
            int x = (int)(from.getX() + dx * t);
            int z = (int)(from.getZ() + dz * t);
            atlas.ensureRegionFilled(level, x, z,
                    ATLAS_PREFILL_PADDING, perHopBudget);
        }
    }

}