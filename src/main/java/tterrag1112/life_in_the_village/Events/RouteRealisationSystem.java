// src/main/java/tterrag1112/life_in_the_village/Events/RouteRealisationSystem.java
package tterrag1112.life_in_the_village.Events;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.OrganicRoadPlacer;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.PathMaterial;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.RoadShape;
import tterrag1112.life_in_the_village.Village.Decoration.VillageBiomeStyle;
import tterrag1112.life_in_the_village.Village.Economy.Trade.RoadRouter;
import tterrag1112.life_in_the_village.Village.Economy.Trade.RouteRealiser;
import tterrag1112.life_in_the_village.Village.Economy.Trade.TradeRoad;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.RoadPrimitive;
import tterrag1112.life_in_the_village.Village.Roads.Decoration.ConnectorAllee;
import tterrag1112.life_in_the_village.Village.Roads.Docking.VillageDockingPoint;
import tterrag1112.life_in_the_village.Village.Village;
import tterrag1112.life_in_the_village.World.Atlas.AtlasCell;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Converts planned trade roads into realised (block-placed) ones when
 * a player approaches their corridor.
 *
 * <h3>Activation policy</h3>
 * A road is realised when a player is within {@link #REALISE_RADIUS}
 * blocks of any cell in the road's cell path. To avoid frame spikes
 * when many roads qualify simultaneously, this system realises at
 * most one road per scan. Subsequent roads wait for the next eligible
 * tick. In practice this means a player standing in a high-density
 * route junction will see roads appear over a few seconds rather than
 * all at once — acceptable trade-off for a stable tick rate.
 *
 * <h3>Two-segment realisation (Phase 2)</h3>
 * Each road is now realised in two visual segments per village end:
 * <ol>
 *   <li><b>Trade-road segment</b> — cell-path smoothing + drift centerline
 *       from one village's docking anchor to the other's. Uses cobblestone
 *       material (existing pipeline).</li>
 *   <li><b>Approach segment</b> — straight {@link RoadPrimitive.StraightRoad}
 *       (amplitude 2.0) from the docking anchor to the village arm endpoint,
 *       using the village's own {@link PathMaterial} so the surface matches
 *       its internal roads with no visible seam.</li>
 * </ol>
 * A roadside allée of saplings is planted alongside each approach segment.
 *
 * <h3>Failure handling</h3>
 * If realisation fails entirely (the realiser returns no blocks), the
 * road is left in the planned state and will be retried on the next
 * tick the player remains nearby. There is no permanent failure mode.
 */
public final class RouteRealisationSystem {

    /** Player→cell distance to trigger realisation, in blocks. */
    private static final int REALISE_RADIUS = 384;

    /** Drift amplitude for the approach segment — low to keep it visually straight. */
    private static final double APPROACH_DRIFT = 2.0;

    private RouteRealisationSystem() {}

    // =========================================================================
    // Tick entry — called by RouteRealisationTickSystem
    // =========================================================================

    public static void run(ServerLevel level, VillageSavedData data) {
        List<? extends ServerPlayer> players = level.players();
        if (players.isEmpty()) return;

        for (TradeRoad road : data.getAllTradeRoads()) {
            if (road.isRealised()) continue;
            if (!road.hasCellPath()) continue;
            if (!isPlayerNearCellPath(players, road.getCellPath())) continue;

            realiseRoad(level, data, road);
            break; // one per scan
        }
    }

    // =========================================================================
    // Realisation
    // =========================================================================

    private static void realiseRoad(ServerLevel level,
                                    VillageSavedData data,
                                    TradeRoad road) {
        Optional<Village> villageA = data.getVillageById(road.getVillageA());
        Optional<Village> villageB = data.getVillageById(road.getVillageB());

        if (villageA.isEmpty() || villageB.isEmpty()) {
            System.out.println("RouteRealisationSystem: road "
                    + road.getRoadId().toString().substring(0, 8)
                    + " has missing endpoints, marking realised with no blocks");
            road.markRealised(List.of());
            data.setDirty();
            return;
        }

        // ── Compute docking geometry for both ends ───────────────────────────
        VillageDockingPoint dpA = VillageDockingPoint.compute(
                villageA.get(), villageB.get().getAnchorPos(), level, data);
        VillageDockingPoint dpB = VillageDockingPoint.compute(
                villageB.get(), villageA.get().getAnchorPos(), level, data);

        System.out.println("RouteRealisationSystem: realising road "
                + road.getRoadId().toString().substring(0, 8)
                + " (" + villageA.get().getName()
                + " ↔ " + villageB.get().getName() + ")");

        // ── Step 1: trade-road segment between docking anchors ───────────────
        List<BlockPos> placed = new ArrayList<>(RouteRealiser.realiseBetween(
                level, road.getCellPath(),
                dpA.dockingAnchor(), dpB.dockingAnchor(),
                RoadRouter.RoadQuality.COBBLESTONE, data));

        if (placed.isEmpty()) {
            System.out.println("RouteRealisationSystem: trade-road segment produced "
                    + "no blocks — leaving road planned for retry");
            return;
        }

        // ── Step 2: approach segments (docking anchor → arm endpoint) ─────────
        placed.addAll(placeApproach(level, dpA, villageA.get()));
        placed.addAll(placeApproach(level, dpB, villageB.get()));

        road.markRealised(placed);
        data.setDirty();

        System.out.println("RouteRealisationSystem: road "
                + road.getRoadId().toString().substring(0, 8)
                + " realised with " + placed.size() + " blocks");
    }

    // =========================================================================
    // Approach segment placement
    // =========================================================================

    /**
     * Places the short approach road from the docking anchor to the village
     * arm endpoint, using the village's own surface material and tier so
     * the join is visually seamless.
     *
     * @return list of placed block positions (may be empty on failure)
     */
    private static List<BlockPos> placeApproach(ServerLevel level,
                                                VillageDockingPoint dock,
                                                Village village) {
        // Centerline: docking anchor → arm endpoint, low drift for a clean straight
        RoadPrimitive.StraightRoad primitive = new RoadPrimitive.StraightRoad(
                dock.dockingAnchor(), dock.armEndpoint(),
                APPROACH_DRIFT,
                RoadShape.RoadTier.VILLAGE_ROAD);
        List<BlockPos> centerline = primitive.computeCenterline(level, level.getSeed());

        if (centerline.size() < 2) return List.of();

        // Material: village's biome style × path tier → matching internal surface
        VillageBiomeStyle biome    = VillageBiomeStyle.detect(level, dock.armEndpoint());
        PathMaterial      material = PathMaterial.forBiomeAndTier(biome, village.getPathTier());

        // Clear trees along the approach first (same pass as trade-road pipeline)
        for (BlockPos p : centerline) {
            RoadRouter.clearTreesAt(level, p.getX(), p.getZ(), p.getY());
        }

        RandomSource rng = RandomSource.create(
                dock.armEndpoint().asLong() ^ dock.dockingAnchor().asLong());
        OrganicRoadPlacer.PlacementResult result = OrganicRoadPlacer.place(
                level, centerline, material, RoadShape.RoadTier.VILLAGE_ROAD, null, rng);

        // Plant allée alongside the approach
        ConnectorAllee.place(level, dock, centerline, village.getVillageType());

        return result.placedBlocks();
    }

    // =========================================================================
    // Hub resolution (legacy reference — superseded by VillageDockingPoint)
    // =========================================================================

    /**
     * Original hub-selection logic, retained to document the pre-Phase-2
     * behaviour. No longer called from {@link #realiseRoad}; replaced by
     * {@link VillageDockingPoint#compute}.
     */
    @SuppressWarnings("unused")
    private static BlockPos resolveHub(Village village,
                                       Village otherVillage,
                                       VillageSavedData data) {
        BlockPos otherAnchor = otherVillage != null ? otherVillage.getAnchorPos() : null;
        if (village.hasCapitalGates() && otherAnchor != null) {
            return village.nearestCapitalGate(otherAnchor.getX(), otherAnchor.getZ());
        }
        if (village.hasCapitalGates()) {
            return village.getCapitalGatePositions().get(0);
        }
        BlockPos gate = village.getMainGateEndpoint();
        if (gate != null) return gate;
        return village.getEffectivePathHub(data);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static boolean isPlayerNearCellPath(
            List<? extends ServerPlayer> players, List<Long> cellPath) {

        long radiusSq = (long) REALISE_RADIUS * REALISE_RADIUS;
        for (long cellKey : cellPath) {
            int cx     = AtlasCell.unpackX(cellKey);
            int cz     = AtlasCell.unpackZ(cellKey);
            int blockX = (cx << AtlasCell.CELL_SHIFT) + AtlasCell.CELL_HALF;
            int blockZ = (cz << AtlasCell.CELL_SHIFT) + AtlasCell.CELL_HALF;
            for (ServerPlayer p : players) {
                long dx = (long)(p.getX() - blockX);
                long dz = (long)(p.getZ() - blockZ);
                if (dx * dx + dz * dz <= radiusSq) return true;
            }
        }
        return false;
    }
}
