package tterrag1112.life_in_the_village.Village.Economy.Trade;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
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

        // Find all other villages with docks within sea-route range
        for (Village other : data.getAllVillages()) {
            if (other.getId().equals(originVillage.getId())) continue;
            if (!hasDock(other, data)) continue;

            // Don't duplicate — if a sea route already exists, skip
            if (seaRouteExistsBetween(data, originVillage.getId(), other.getId())) continue;

            UUID destDockId = findDockBuilding(other, data);
            if (destDockId == null) continue;
            BlockPos destDockPos = data.getBuildingById(destDockId)
                    .map(Building::getShape)
                    .map(Building.BuildingShape::getOrigin)
                    .orElse(other.getAnchorPos());

            // Try to find a sea path
            List<Long> cellPath = SeaRouteRouter.findRoute(
                    atlas, originDockPos, destDockPos);
            if (cellPath.isEmpty()) continue;

            // Check for reusable existing sea route
            SeaRoute reusable = findReusableSeaRoute(data, cellPath);
            SeaRoute route;
            if (reusable != null) {
                route = reusable;
            } else {
                route = SeaRoute.create(
                        originVillage.getId(), other.getId(),
                        originDockId, destDockId,
                        cellPath);
                data.addSeaRoute(route);
            }

            // Create a TradeRoute referencing this connection
            TradeRoute tradeRoute = new TradeRoute(
                    UUID.randomUUID(),
                    originVillage.getId(),
                    other.getId(),
                    route.getConnectionId(),
                    TradeRoute.RouteStatus.ACTIVE,
                    TradeRoute.RouteType.NEUTRAL,  // or whatever default fits
                    level.getGameTime(),
                    0L,                             // lastCaravanTick
                    1.0                             // penalty or whatever the 9th arg is
            );
            data.addTradeRoute(tradeRoute);
            route.addRouteReference(tradeRoute.getRouteId());

            System.out.println("TradeRouteManager: established sea route "
                    + originVillage.getName() + " ↔ " + other.getName()
                    + " (" + cellPath.size() + " cells)");
        }
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

    private static boolean seaRouteExistsBetween(VillageSavedData data,
                                                 UUID villageA, UUID villageB) {
        for (SeaRoute route : data.getAllSeaRoutes()) {
            if ((route.getVillageA().equals(villageA) && route.getVillageB().equals(villageB))
                    || (route.getVillageA().equals(villageB) && route.getVillageB().equals(villageA))) {
                return true;
            }
        }
        return false;
    }

    private static SeaRoute findReusableSeaRoute(VillageSavedData data, List<Long> cellPath) {
        Set<Long> newCells = new HashSet<>(cellPath);
        for (SeaRoute existing : data.getAllSeaRoutes()) {
            Set<Long> existingCells = new HashSet<>(existing.getCellPath());
            int overlap = 0;
            for (Long cell : newCells) {
                if (existingCells.contains(cell)) overlap++;
            }
            double ratio = (double) overlap / newCells.size();
            if (ratio >= 0.5) return existing;
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