// src/main/java/tterrag1112/life_in_the_village/Events/RouteRealisationSystem.java
package tterrag1112.life_in_the_village.Events;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Economy.Trade.RoadRouter;
import tterrag1112.life_in_the_village.Village.Economy.Trade.RouteRealiser;
import tterrag1112.life_in_the_village.Village.Economy.Trade.TradeRoad;
import tterrag1112.life_in_the_village.Village.Village;
import tterrag1112.life_in_the_village.World.Atlas.AtlasCell;
import tterrag1112.life_in_the_village.World.Atlas.WorldAtlas;

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
 * <h3>Failure handling</h3>
 * If realisation fails entirely (the realiser returns no blocks), the
 * road is left in the planned state and will be retried on the next
 * tick the player remains nearby. There is no permanent failure mode.
 */
public final class RouteRealisationSystem {

    /** Player→cell distance to trigger realisation, in blocks. */
    private static final int REALISE_RADIUS = 384;

    private RouteRealisationSystem() {}

    // =========================================================================
    // Tick entry — called by RouteRealisationTickSystem
    // =========================================================================

    public static void run(ServerLevel level, VillageSavedData data) {
        WorldAtlas atlas = WorldAtlas.get(level);
        List<? extends ServerPlayer> players = level.players();
        if (players.isEmpty()) return;

        for (TradeRoad road : data.getAllTradeRoads()) {
            if (road.isRealised()) continue;
            if (!road.hasCellPath()) continue;
            if (!isPlayerNearCellPath(players, road.getCellPath())) continue;

            realiseRoad(level, atlas, data, road);
            break; // one per scan
        }
    }

    // =========================================================================
    // Realisation
    // =========================================================================

    private static void realiseRoad(ServerLevel level,
                                    WorldAtlas atlas,
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

        BlockPos hubA = resolveHub(villageA.get(), data);
        BlockPos hubB = resolveHub(villageB.get(), data);

        System.out.println("RouteRealisationSystem: realising road "
                + road.getRoadId().toString().substring(0, 8)
                + " (" + villageA.get().getName()
                + " ↔ " + villageB.get().getName() + ")");

        List<BlockPos> placed = RouteRealiser.realiseBetween(
                level, road.getCellPath(),
                hubA, hubB,
                RoadRouter.RoadQuality.COBBLESTONE);

        if (placed.isEmpty()) {
            System.out.println("RouteRealisationSystem: realisation produced "
                    + "no blocks — leaving road planned for retry");
            return;
        }

        road.markRealised(placed);
        data.setDirty();

        System.out.println("RouteRealisationSystem: road "
                + road.getRoadId().toString().substring(0, 8)
                + " realised with " + placed.size() + " blocks");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static boolean isPlayerNearCellPath(
            List<? extends ServerPlayer> players, List<Long> cellPath) {

        long radiusSq = (long) REALISE_RADIUS * REALISE_RADIUS;
        for (long cellKey : cellPath) {
            int cx = AtlasCell.unpackX(cellKey);
            int cz = AtlasCell.unpackZ(cellKey);
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

    private static BlockPos resolveHub(Village village,
                                       VillageSavedData data) {
        if (village.hasCapitalGates()) {
            return village.getCapitalGatePositions().get(0);
        }
        return village.getEffectivePathHub(data);
    }
}