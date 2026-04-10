package tterrag1112.life_in_the_village.Village.Economy.Trade;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Lore.HistoryTextGenerator;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Decoration.VillageSizeTier;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;
import tterrag1112.life_in_the_village.Village.Village;
import tterrag1112.life_in_the_village.Village.VillageWarningSystem;
import tterrag1112.life_in_the_village.World.Atlas.AtlasCell;
import tterrag1112.life_in_the_village.World.Atlas.WorldAtlas;

import java.util.*;

/**
 * Establishes and maintains trade routes between villages.
 *
 * <h3>Phase 7a — atlas-driven routing</h3>
 * Routes are now planned at the atlas cell level using
 * {@link AtlasRouteRouter} instead of running block-level A* across
 * thousands of blocks. The cell sequence becomes the route's
 * canonical path; block-level realisation happens lazily when a
 * player approaches the corridor.
 *
 * <h3>Road sharing</h3>
 * When a new route's cell path overlaps an existing road by more than
 * {@link #SHARE_THRESHOLD}, the new route reuses that road instead of
 * creating a new one. Multiple routes referencing the same road
 * produce the "trunk and spur" topology you'd see on a real-world
 * highway map: heavily used corridors get one shared road, with
 * unique branches at the ends.
 *
 * <h3>Realisation policy</h3>
 * New roads are created in the {@code planned} state — they have a
 * cell path but no blocks. {@link RouteRealisationSystem} flips them
 * to realised when a player approaches. Roads that overlap with an
 * existing realised road are themselves marked realised immediately
 * (the blocks are already there).
 */
public class TradeRouteManager {

    private static final double MAX_ROUTE_DISTANCE = 3000.0;
    private static final long   TICK_INTERVAL      = 24000L;

    /**
     * Minimum cell-path overlap fraction for a new route to reuse an
     * existing road instead of creating its own. 0.5 = the new route
     * shares at least half its cells with the existing one.
     */
    private static final double SHARE_THRESHOLD = 0.5;

    /** Padding around the start/end blocks for atlas pre-fill, in blocks. */
    private static final int ATLAS_PREFILL_PADDING = 256;

    /** Per-call atlas fill budget when establishing a route. */
    private static final long ATLAS_PREFILL_BUDGET_NS = 50_000_000L; // 50ms

    private static final int REPAIR_THRESHOLD = 50;

    // =========================================================================
    // Establish routes for a new village
    // =========================================================================

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

        int routesNeeded = (int) (maxRoads - existingRoutes);

        WorldAtlas atlas = WorldAtlas.get(level);

        // Sort candidate destinations by straight-line distance — close
        // ones first, since they're more likely to find a viable path
        // and are more interesting trade partners anyway.
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
                    return newCenter.distSqr(vc);
                }))
                .limit(routesNeeded)
                .toList();

        for (Village target : candidates) {
            BlockPos targetCenter = getVillageCenter(target, data);
            if (targetCenter == null) continue;

            establishOneRoute(level, atlas, newVillage, target,
                    newCenter, targetCenter, data);
        }
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

    /**
     * Plans a single route between two villages. May reuse an existing
     * road if the cell path overlaps one substantially.
     */
    private static void establishOneRoute(ServerLevel level,
                                          WorldAtlas atlas,
                                          Village from,
                                          Village to,
                                          BlockPos fromCenter,
                                          BlockPos toCenter,
                                          VillageSavedData data) {

        // ── Step 1: ensure the atlas covers the corridor ─────────────────────
        // Walk the straight line between the two villages and pre-fill the
        // atlas in chunks of 800 blocks. This is best-effort — if the
        // budget runs out, the router will treat unfilled cells as
        // medium-cost and the route may take a worse path than ideal.
        prefillAtlasCorridor(level, atlas, fromCenter, toCenter);

        // ── Step 2: cell-level routing ───────────────────────────────────────
        Set<Long> attractors = collectTradeHubAttractors(
                data, fromCenter, toCenter);

        List<Long> cellPath = AtlasRouteRouter.findRoute(
                atlas, fromCenter, toCenter, attractors);
        if (cellPath.isEmpty()) {
            System.out.println("TradeRouteManager: no cell path from "
                    + from.getName() + " to " + to.getName());
            return;
        }

        System.out.println("TradeRouteManager: planned route "
                + from.getName() + " → " + to.getName()
                + " (" + cellPath.size() + " cells)");

        // ── Step 3: check for overlap with existing roads ────────────────────
        TradeRoad reusable = findReusableRoad(data, cellPath);
        TradeRoute.RouteType routeType = determineRouteType(from, to, data);

        TradeRoad road;
        if (reusable != null) {
            // Reuse — no new road, just attach the new route to the existing one
            road = reusable;
            System.out.println("TradeRouteManager: route "
                    + from.getName() + " ↔ " + to.getName()
                    + " is sharing existing road "
                    + road.getRoadId().toString().substring(0, 8)
                    + " (overlap "
                    + (int)(AtlasRouteRouter.pathOverlapRatio(
                    cellPath, road.getCellPath()) * 100) + "%)");
        } else {
            // Fresh road — create as planned, register cell path with atlas
            road = TradeRoad.createPlanned(
                    from.getId(), to.getId(), cellPath);
            data.addTradeRoad(road);
            atlas.addRoadThroughCells(road.getRoadId(), cellPath);
            System.out.println("TradeRouteManager: created new planned road "
                    + road.getRoadId().toString().substring(0, 8)
                    + " for " + from.getName() + " ↔ " + to.getName());
        }

        // ── Step 4: create the route record ──────────────────────────────────
        TradeRoute route = TradeRoute.create(
                from.getId(), to.getId(),
                road.getRoadId(), routeType, level.getGameTime());
        data.addTradeRoute(route);
        road.addRouteReference(route.getRouteId());

        // ── Step 5: history record ──────────────────────────────────────────
        data.getKingdomForVillage(from.getId())
                .ifPresent(k -> k.getHistory().recordEvent(
                        HistoryTextGenerator.tradeRouteEstablished(
                                from.getName(), to.getName(),
                                level.getGameTime()),
                        k.getName(), k.getRulerName(level)));

        data.setDirty();
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
    private static void prefillAtlasCorridor(ServerLevel level,
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

    // =========================================================================
    // Road sharing
    // =========================================================================

    /**
     * Looks through every existing road for one whose cell path overlaps
     * the new path by at least {@link #SHARE_THRESHOLD}. Returns the
     * highest-overlap road, or null if none qualify.
     *
     * <p>Roads with empty cell paths (legacy roads from before Phase 7a)
     * are skipped — we can't compute overlap on them.
     */
    private static TradeRoad findReusableRoad(VillageSavedData data,
                                              List<Long> newCellPath) {
        TradeRoad best = null;
        double    bestRatio = SHARE_THRESHOLD;

        for (TradeRoad road : data.getAllTradeRoads()) {
            if (!road.hasCellPath()) continue;
            if (!road.isActive()) continue;

            double ratio = AtlasRouteRouter.pathOverlapRatio(
                    newCellPath, road.getCellPath());
            if (ratio > bestRatio) {
                bestRatio = ratio;
                best = road;
            }
        }
        return best;
    }

    // =========================================================================
    // Tick — daily upkeep, degradation, suspension
    // =========================================================================

    public static void tick(ServerLevel level,
                            VillageSavedData data,
                            long currentTick) {
        if (currentTick % TICK_INTERVAL != 0) return;

        for (TradeRoad road : data.getAllTradeRoads()) {
            if (!road.isActive()) continue;
            if (!road.isRealised()) continue; // planned roads don't degrade

            tickOneRoad(level, data, road, currentTick);
        }

        // Restore suspended routes whose underlying road is repaired
        data.getAllTradeRoutes().stream()
                .filter(r -> r.getStatus() == TradeRoute.RouteStatus.SUSPENDED)
                .forEach(r -> data.getRoadById(r.getConnectionId())
                        .ifPresent(road -> {
                            if (road.getQuality() >= REPAIR_THRESHOLD) {
                                r.setStatus(TradeRoute.RouteStatus.ACTIVE);
                                data.setDirty();
                            }
                        }));

        VillageWarningSystem.tickWarningSpread(level, data, currentTick);
    }

    /**
     * Charges upkeep, applies degradation, and triggers route suspension
     * for one road. Pulled out of {@link #tick} so the per-road logic
     * stays readable.
     *
     * <h3>Upkeep model</h3>
     * Total upkeep cost is divided equally across the road's referencing
     * routes. Each route's share is then split between its two villages.
     * A village pays from its treasury — if it can't afford its portion,
     * the road records a deficit.
     *
     * <h3>Outcomes</h3>
     * <ul>
     *   <li><b>All deductions paid:</b> road maintained or repaired.</li>
     *   <li><b>Half or more paid:</b> partial maintenance — quality
     *       drifts down by 3, no visual repair.</li>
     *   <li><b>Less than half paid:</b> full degradation — quality drops
     *       by the standard amount, blocks degrade, and routes whose
     *       underlying road quality falls below 20 get suspended.</li>
     * </ul>
     */
    private static void tickOneRoad(ServerLevel level,
                                    VillageSavedData data,
                                    TradeRoad road,
                                    long currentTick) {
        int totalCost = road.getUpkeepCost();
        int routeCount = Math.max(1, road.getRouteIds().size());
        int costPerRoute = Math.max(1, totalCost / routeCount);
        int costPerVillage = Math.max(1, costPerRoute / 2);

        int totalDue = 0;
        int totalPaid = 0;

        for (UUID routeId : road.getRouteIds()) {
            Optional<TradeRoute> routeOpt = data.getRouteById(routeId);
            if (routeOpt.isEmpty()) continue;
            TradeRoute route = routeOpt.get();

            totalDue += costPerRoute;

            boolean aPaid = deductUpkeep(route.getVillageA(), costPerVillage, data);
            boolean bPaid = deductUpkeep(route.getVillageB(), costPerVillage, data);

            if (aPaid) totalPaid += costPerVillage;
            if (bPaid) totalPaid += costPerVillage;
        }

        if (totalDue == 0) return; // no active routes — road persists but no upkeep

        double paymentRatio = (double) totalPaid / totalDue;

        if (paymentRatio >= 1.0) {
            // Fully paid — repair if needed, otherwise just refresh maintenance tick
            if (road.needsRepair()) {
                road.performMaintenance(currentTick, level);
            } else {
                road.setLastMaintenanceTick(currentTick);
            }
        } else if (paymentRatio >= 0.5) {
            // Partial payment — slow degradation
            road.setQuality(Math.max(0, road.getQuality() - 3));
            road.tick(currentTick, level);
        } else {
            // Mostly unpaid — full degradation
            if (road.tick(currentTick, level)
                    && road.getQuality() <= 20) {
                // Suspend every route on this road
                for (UUID routeId : road.getRouteIds()) {
                    data.getRouteById(routeId).ifPresent(r -> {
                        r.setStatus(TradeRoute.RouteStatus.SUSPENDED);
                        data.getKingdomForVillage(r.getVillageA())
                                .ifPresent(k -> k.getHistory().recordEvent(
                                        HistoryTextGenerator.tradeRouteCollapsed(
                                                getVillageName(r.getVillageA(), data),
                                                getVillageName(r.getVillageB(), data),
                                                level.getGameTime()),
                                        k.getName(), k.getRulerName(level)));
                    });
                }
                data.setDirty();
            }
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static int maxRoadsForTier(VillageSizeTier tier) {
        return switch (tier) {
            case HAMLET  -> 1;
            case VILLAGE -> 1;
            case TOWN    -> 3;
            case CITY    -> 5;
        };
    }

    private static boolean deductUpkeep(UUID villageId,
                                        int cost,
                                        VillageSavedData data) {
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
                .orElse(villageId.toString().substring(0, 8));
    }

    private static TradeRoute.RouteType determineRouteType(
            Village a, Village b, VillageSavedData data) {
        UUID kingdomA = data.getKingdomForVillage(a.getId())
                .map(k -> k.getId()).orElse(null);
        UUID kingdomB = data.getKingdomForVillage(b.getId())
                .map(k -> k.getId()).orElse(null);
        if (kingdomA == null && kingdomB == null)
            return TradeRoute.RouteType.NEUTRAL;
        if (kingdomA == null || kingdomB == null)
            return TradeRoute.RouteType.NEUTRAL;
        if (kingdomA.equals(kingdomB))
            return TradeRoute.RouteType.KINGDOM_INTERNAL;
        return TradeRoute.RouteType.CROSS_KINGDOM;
    }

    private static BlockPos getVillageCenter(Village village,
                                             VillageSavedData data) {
        return village.getBounds(data)
                .map(b -> BlockPos.containing(b.getCenter()))
                .orElse(null);
    }
    /**
     * Returns the set of atlas cell keys covered by trade-hub villages
     * (planned or realised) whose centres lie within a generous corridor
     * of the straight line between {@code from} and {@code to}.
     *
     * <p>The corridor is sized so that mildly out-of-the-way trade hubs
     * are still found, encouraging routes to bend through them. Routes
     * to villages directly between two trade hubs will naturally pass
     * through the trade hub cells thanks to the router's attractor
     * discount.
     */
    private static Set<Long> collectTradeHubAttractors(
            VillageSavedData data, BlockPos from, BlockPos to) {

        Set<Long> attractors = new HashSet<>();

        // Corridor: rectangle around the from→to line, padded by 384 blocks
        // on each side. 384 = 6 atlas cells, generous enough that hubs
        // slightly off the natural path still register.
        int minX = Math.min(from.getX(), to.getX()) - 384;
        int maxX = Math.max(from.getX(), to.getX()) + 384;
        int minZ = Math.min(from.getZ(), to.getZ()) - 384;
        int maxZ = Math.max(from.getZ(), to.getZ()) + 384;

        for (Village v : data.getAllVillages()) {
            if (!"trade_hub".equals(v.getVillageType())) continue;

            BlockPos anchor = v.getAnchorPos();
            if (anchor == null) continue;

            int x = anchor.getX();
            int z = anchor.getZ();
            if (x < minX || x > maxX || z < minZ || z > maxZ) continue;

            int cellX = WorldAtlas.blockToCell(x);
            int cellZ = WorldAtlas.blockToCell(z);
            attractors.add(AtlasCell.packKey(cellX, cellZ));
        }

        return attractors;
    }
}