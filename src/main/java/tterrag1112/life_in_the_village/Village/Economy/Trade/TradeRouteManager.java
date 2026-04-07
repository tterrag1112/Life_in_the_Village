package tterrag1112.life_in_the_village.Village.Economy.Trade;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Lore.HistoryTextGenerator;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.VillagePath;
import tterrag1112.life_in_the_village.Village.Decoration.VillageSizeTier;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;
import tterrag1112.life_in_the_village.Village.Economy.Currency.NpcEconomy;
import tterrag1112.life_in_the_village.Village.Economy.Currency.NpcWallet;
import tterrag1112.life_in_the_village.Village.Village;
import tterrag1112.life_in_the_village.Village.VillageWarningSystem;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class TradeRouteManager {

    // Max distance for auto trade route establishment
    private static final double MAX_ROUTE_DISTANCE = 3000.0;
    // How often to tick trade routes
    private static final long   TICK_INTERVAL      = 24000L;

    // -------------------------------------------------------------------------
    // Auto-establishment
    // -------------------------------------------------------------------------

    /**
     * Called when a new village is created or when a kingdom
     * relationship changes. Establishes trade routes between
     * eligible villages.
     */
    // src/main/java/tterrag1112/life_in_the_village/Village/Economy/Trade/TradeRouteManager.java
// Replace the establishRoutes method entirely:

    public static void establishRoutes(ServerLevel level,
                                       Village newVillage,
                                       VillageSavedData data) {
        BlockPos newCenter = getVillageCenter(newVillage, data);
        if (newCenter == null) return;

        int buildingCount = newVillage.getBuildingIds().size();
        VillageSizeTier tier = VillageSizeTier.fromBuildingCount(buildingCount);
        int maxRoads = maxRoadsForTier(tier);

        long existingRoutes = data.getRoutesForVillage(newVillage.getId())
                .stream()
                .filter(r -> r.getStatus() == TradeRoute.RouteStatus.ACTIVE)
                .count();

        if (existingRoutes >= maxRoads) return;
        int routesNeeded = (int)(maxRoads - existingRoutes);

        List<Village> candidates = data.getAllVillages().stream()
                .filter(v -> !v.getId().equals(newVillage.getId()))
                .filter(v -> data.getRouteBetween(
                        newVillage.getId(), v.getId()).isEmpty())
                .filter(v -> {
                    BlockPos vc = getVillageCenter(v, data);
                    if (vc == null) return false;
                    double dist = Math.sqrt(
                            Math.pow(newCenter.getX() - vc.getX(), 2)
                                    + Math.pow(newCenter.getZ() - vc.getZ(), 2));
                    return dist <= MAX_ROUTE_DISTANCE;
                })
                .sorted(Comparator.comparingDouble(v -> {
                    BlockPos vc = getVillageCenter(v, data);
                    if (vc == null) return Double.MAX_VALUE;
                    return estimateTerrainDistance(level, newCenter, vc);
                }))
                .limit(routesNeeded)
                .toList();

        for (Village target : candidates) {
            BlockPos targetCenter = getVillageCenter(target, data);
            if (targetCenter == null) continue;

            TradeRoute.RouteType routeType =
                    determineRouteType(newVillage, target, data);

            System.out.println("TradeRouteManager: routing road from "
                    + newVillage.getName() + " to " + target.getName() + "...");

            // ── Route between village hub positions ───────────────────────────
            // Use path hubs (town squares) as endpoints when available so the
            // trade road terminates at the social heart of each village.
            BlockPos newHub    = resolveRouteHub(newVillage, data);
            BlockPos targetHub = resolveRouteHub(target, data);

            List<BlockPos> path = RoadRouter.findRoadWithMerge(
                    level, newHub, targetHub, data);

            if (path.isEmpty()) {
                System.out.println("TradeRouteManager: no path found, skipping");
                continue;
            }

            List<BlockPos> placedBlocks = RoadRouter.placeRoad(
                    level, path, RoadRouter.RoadQuality.COBBLESTONE);

            // ── Entrance roads ────────────────────────────────────────────────
            // If the trade road's start/end is not already at the hub,
            // place a short primary entrance road connecting them.
            // This handles the case where the A* path didn't reach exactly
            // the hub due to budget constraints.
            if (!path.isEmpty()) {
                BlockPos roadStart = path.get(0);
                BlockPos roadEnd   = path.get(path.size() - 1);

                // Connect road start → newVillage hub if more than 8 blocks apart
                if (!roadStart.closerThan(newHub, 8)) {
                    placeEntranceRoad(level, roadStart, newHub,
                            newVillage, data);
                }

                // Connect road end → target hub
                if (!roadEnd.closerThan(targetHub, 8)) {
                    placeEntranceRoad(level, roadEnd, targetHub,
                            target, data);
                }
            }

            TradeRoad road = TradeRoad.create(
                    newVillage.getId(), target.getId(), placedBlocks);
            data.addTradeRoad(road);

            TradeRoute route = TradeRoute.create(
                    newVillage.getId(), target.getId(),
                    road.getRoadId(), routeType, level.getGameTime());
            data.addTradeRoute(route);

            data.getKingdomForVillage(newVillage.getId())
                    .ifPresent(k -> k.getHistory().recordEvent(
                            HistoryTextGenerator.tradeRouteEstablished(
                                    newVillage.getName(), target.getName(),
                                    level.getGameTime()),
                            k.getName(), k.getRulerName(level)));

            System.out.println("TradeRouteManager: established "
                    + routeType + " route between "
                    + newVillage.getName() + " and " + target.getName()
                    + " (" + path.size() + " blocks)");
        }
    }

    // -------------------------------------------------------------------------
    // Entrance road placement
    // -------------------------------------------------------------------------

    private static void placeEntranceRoad(ServerLevel level,
                                          BlockPos roadEndpoint,
                                          BlockPos villageHub,
                                          Village village,
                                          VillageSavedData data) {
        List<BlockPos> entrancePath = RoadRouter.findRoadWithMerge(
                level, roadEndpoint, villageHub, data);

        if (entrancePath.isEmpty()) return;

        List<BlockPos> placed = RoadRouter.placeRoad(
                level, entrancePath,
                RoadRouter.RoadQuality.COBBLESTONE);

        if (placed.isEmpty()) return;

        // Register as a village path so it appears on the village map
        // and participates in the street network
        data.addVillagePath(new VillagePath(
                java.util.UUID.randomUUID(),
                village.getId(),
                placed,
                VillagePath.PathTier.COBBLESTONE));
        data.setDirty();

        System.out.println("TradeRouteManager: placed entrance road for "
                + village.getName() + " (" + placed.size() + " blocks)");
    }

    private static int maxRoadsForTier(VillageSizeTier tier) {
        return switch (tier) {
            case HAMLET  -> 1;
            case VILLAGE -> 1;
            case TOWN    -> 3;
            case CITY    -> 5;
        };
    }

    /**
     * Estimates terrain distance between two points by sampling
     * slope along the straight line. Much cheaper than full A*
     * and good enough for sorting candidates.
     */
    private static double estimateTerrainDistance(
            ServerLevel level, BlockPos from, BlockPos to) {
        int steps = Math.max(
                Math.abs(to.getX() - from.getX()),
                Math.abs(to.getZ() - from.getZ())) / 8;
        if (steps == 0) return from.distSqr(to);

        double totalCost = 0;
        int prevY = from.getY();

        for (int i = 1; i <= steps; i++) {
            float t = (float) i / steps;
            int x = (int)(from.getX()
                    + t * (to.getX() - from.getX()));
            int z = (int)(from.getZ()
                    + t * (to.getZ() - from.getZ()));
            int y = level.getHeight(
                    net.minecraft.world.level.levelgen.Heightmap
                            .Types.WORLD_SURFACE, x, z);

            int dy    = Math.abs(y - prevY);
            double stepDist = 8.0;

            // Apply same slope penalty as A*
            if (dy == 0)     totalCost += stepDist;
            else if (dy == 1) totalCost += stepDist * 3.0;
            else if (dy == 2) totalCost += stepDist * 15.0;
            else             totalCost += stepDist * 15.0 * dy * dy;

            prevY = y;
        }

        return totalCost;
    }

    // -------------------------------------------------------------------------
    // Tick
    // -------------------------------------------------------------------------

    public static void tick(ServerLevel level,
                            VillageSavedData data,
                            long currentTick) {
        if (currentTick % TICK_INTERVAL != 0) return;

        data.getAllTradeRoads().forEach(road -> {
            if (!road.isActive()) return;

            // Find the two villages that own this road
            data.getRouteBetween(road.getVillageA(),
                    road.getVillageB()).ifPresent(route -> {

                // Try to deduct upkeep from both villages
                // split cost equally
                int totalCost = road.getUpkeepCost();
                int costEach  = Math.max(1, totalCost / 2);

                boolean villageAPaid = deductUpkeep(
                        road.getVillageA(), costEach,
                        level, data);
                boolean villageBPaid = deductUpkeep(
                        road.getVillageB(), costEach,
                        level, data);

                if (villageAPaid && villageBPaid) {
                    // Both paid — repair or maintain quality
                    if (road.needsRepair()) {
                        road.performMaintenance(
                                currentTick, level);
                        System.out.println(
                                "TradeRouteManager: road repaired "
                                        + "between "
                                        + getVillageName(road.getVillageA(),
                                        data)
                                        + " and "
                                        + getVillageName(road.getVillageB(),
                                        data));
                    } else {
                        // Quality maintained — no visual change
                        road.setLastMaintenanceTick(currentTick);
                    }
                } else if (villageAPaid || villageBPaid) {
                    // Only one paid — partial maintenance
                    // slows degradation but doesn't repair
                    road.setQuality(Math.max(0,
                            road.getQuality() - 3));
                    if (road.tick(currentTick, level)) {
                        System.out.println(
                                "TradeRouteManager: partial upkeep "
                                        + "— road degrading between "
                                        + getVillageName(road.getVillageA(),
                                        data)
                                        + " and "
                                        + getVillageName(road.getVillageB(),
                                        data));
                    }
                } else {
                    // Neither paid — full degradation
                    if (road.tick(currentTick, level)) {
                        System.out.println(
                                "TradeRouteManager: no upkeep paid "
                                        + "— road degrading between "
                                        + getVillageName(road.getVillageA(),
                                        data)
                                        + " and "
                                        + getVillageName(road.getVillageB(),
                                        data));

                        // Suspend route if quality too low
                        if (road.getQuality() <= 20) {
                            route.setStatus(
                                    TradeRoute.RouteStatus
                                            .SUSPENDED);

                            data.getKingdomForVillage(route.getVillageA())
                                    .ifPresent(k -> k.getHistory().recordEvent(
                                            HistoryTextGenerator.tradeRouteCollapsed(getVillageName(route.getVillageA(), data), getVillageName(route.getVillageB(),data), level.getGameTime()), k.getName(), k.getRulerName(level)));
                            System.out.println(
                                    "TradeRouteManager: route "
                                            + "suspended — road quality "
                                            + "too low");
                            data.setDirty();
                        }
                    }
                }
            });
        });

        // Restore suspended routes if road was repaired
        data.getAllTradeRoutes().stream()
                .filter(r -> r.getStatus()
                        == TradeRoute.RouteStatus.SUSPENDED)
                .forEach(r -> data.getRoadById(r.getRoadId())
                        .ifPresent(road -> {
                            if (road.getQuality() >= REPAIR_THRESHOLD) {
                                r.setStatus(
                                        TradeRoute.RouteStatus.ACTIVE);
                                System.out.println(
                                        "TradeRouteManager: route "
                                                + "restored between "
                                                + getVillageName(r.getVillageA(),
                                                data)
                                                + " and "
                                                + getVillageName(r.getVillageB(),
                                                data));
                                data.setDirty();
                            }
                        }));

        VillageWarningSystem.tickWarningSpread(
                level, data, currentTick);
    }

    private static final int REPAIR_THRESHOLD = 50;

    private static boolean deductUpkeep(UUID villageId,
                                        int cost,
                                        ServerLevel level,
                                        VillageSavedData data) {
        // Road maintenance is a government expense — paid from the village treasury
        return data.getVillageById(villageId)
                .map(village -> {
                    long upkeepBronze = CurrencyValue.ofSilver(cost).toBronze();
                    return village.withdrawFromTreasury(upkeepBronze);
                })
                .orElse(false);
    }

    private static String getVillageName(UUID villageId,
                                         VillageSavedData data) {
        return data.getVillageById(villageId)
                .map(Village::getName)
                .orElse(villageId.toString()
                        .substring(0, 8));
    }

    // -------------------------------------------------------------------------
    // Route type determination
    // -------------------------------------------------------------------------

    private static TradeRoute.RouteType determineRouteType(
            Village a, Village b, VillageSavedData data) {
        UUID kingdomA = data.getKingdomForVillage(a.getId())
                .map(k -> k.getId()).orElse(null);
        UUID kingdomB = data.getKingdomForVillage(b.getId())
                .map(k -> k.getId()).orElse(null);

        // Both have no kingdom
        if (kingdomA == null && kingdomB == null)
            return TradeRoute.RouteType.NEUTRAL;

        // One has no kingdom
        if (kingdomA == null || kingdomB == null)
            return TradeRoute.RouteType.NEUTRAL;

        // Same kingdom
        if (kingdomA.equals(kingdomB))
            return TradeRoute.RouteType.KINGDOM_INTERNAL;

        // Different kingdoms
        return TradeRoute.RouteType.CROSS_KINGDOM;
    }

    private static BlockPos getVillageCenter(Village village,
                                             VillageSavedData data) {
        return village.getBounds(data)
                .map(b -> BlockPos.containing(b.getCenter()))
                .orElse(null);
    }
    private static BlockPos resolveRouteHub(Village village, VillageSavedData data) {
        if (village.hasCapitalGates()) {
 // Use the first registered gate as the road endpoint.
 // If there are multiple gates, the caller can pick the nearest one
 // after computing both hubs. For now, the first gate is fine
 // because RoadRouter finds its own path regardless of start point.
             return village.getCapitalGatePositions().get(0);
             }
            return village.getEffectivePathHub(data);
    }
}