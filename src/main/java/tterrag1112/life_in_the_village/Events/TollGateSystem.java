package tterrag1112.life_in_the_village.Events;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import tterrag1112.life_in_the_village.Kingdom.Kingdom;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Networking.WorldRoadSavedData;
import tterrag1112.life_in_the_village.Village.Economy.Trade.Caravan;
import tterrag1112.life_in_the_village.Village.Economy.Trade.CaravanSavedData;
import tterrag1112.life_in_the_village.Village.Reputation.VillageReputation;
import tterrag1112.life_in_the_village.Village.Roads.Economy.TollFeeCalculator;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadNode;
import tterrag1112.life_in_the_village.Village.Roads.Graph.WorldRoadGraph;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every-second tick handler for toll gate interactions.
 *
 * <h3>Caravan tolls</h3>
 * When a spawned caravan's lead entity is within {@link #CARAVAN_TRIGGER_RADIUS}
 * blocks of a TOLL_GATE node, the caravan's origin village treasury is debited
 * and the collecting kingdom's treasury is credited. A session-scoped set
 * {@code paidThisSession} (keyed by "caravanId:nodeId") prevents repeat charges
 * during a single crossing; it resets on server restart, which is acceptable.
 *
 * <h3>Player notification</h3>
 * A player entering within {@link #PLAYER_NOTIFY_RADIUS} blocks receives a
 * one-time chat message (per login session) with the upcoming fee. A player
 * entering within {@link #PLAYER_CROSS_RADIUS} is considered to have crossed
 * and the fee is logged (actual currency deduction is a Phase 10 dependency).
 */
public final class TollGateSystem {

    static final int CARAVAN_TRIGGER_RADIUS = 5;
    static final int PLAYER_NOTIFY_RADIUS   = 12;
    static final int PLAYER_CROSS_RADIUS    = 3;

    /** Session-scoped "caravanId:nodeId" pairs already charged. */
    private static final Set<String> PAID_THIS_SESSION = ConcurrentHashMap.newKeySet();

    /** Per-player, per-gate notification set (player UUID → set of notified gate node UUIDs). */
    private static final Map<UUID, Set<UUID>> PLAYER_NOTIFIED = new ConcurrentHashMap<>();

    private TollGateSystem() {}

    // =========================================================================
    // Main tick
    // =========================================================================

    static void tick(ServerLevel level, VillageSavedData vData) {
        WorldRoadSavedData roadData = WorldRoadSavedData.get(level);
        WorldRoadGraph graph = roadData.getGraph();
        Map<UUID, WorldRoadSavedData.TollGateRecord> tollGates = roadData.getAllTollGates();
        if (tollGates.isEmpty()) return;

        CaravanSavedData caravanData = CaravanSavedData.get(level);
        List<? extends ServerPlayer> players = level.players();

        for (WorldRoadSavedData.TollGateRecord record : tollGates.values()) {
            RoadNode node = graph.getNode(record.nodeId());
            if (node == null) continue;

            BlockPos gatePos = node.position();
            Kingdom kingdom = vData.getKingdomById(record.kingdomId()).orElse(null);

            tickCaravans(level, caravanData, vData, roadData, gatePos, record, kingdom);

            for (ServerPlayer player : players) {
                tickPlayer(player, vData, roadData, gatePos, record, kingdom);
            }
        }
    }

    // =========================================================================
    // Caravan toll charging
    // =========================================================================

    private static void tickCaravans(ServerLevel level,
                                      CaravanSavedData caravanData,
                                      VillageSavedData vData,
                                      WorldRoadSavedData roadData,
                                      BlockPos gatePos,
                                      WorldRoadSavedData.TollGateRecord record,
                                      Kingdom kingdom) {
        UUID nodeId = record.nodeId();

        for (Caravan caravan : caravanData.getAllCaravans()) {
            if (!caravan.isSpawned()) continue;

            String paidKey = caravan.getCaravanId() + ":" + nodeId;
            if (PAID_THIS_SESSION.contains(paidKey)) continue;

            BlockPos caravanPos = caravanLeadPos(caravan, level);
            if (caravanPos == null) continue;
            if (!withinRadius(caravanPos, gatePos, CARAVAN_TRIGGER_RADIUS)) continue;

            long goodsValue = estimateGoodsValue(caravan);
            TollFeeCalculator.TollFee fee = TollFeeCalculator.computeFee(
                    record.tier(), goodsValue, TollFeeCalculator.DEFAULT_REPUTATION);

            PAID_THIS_SESSION.add(paidKey);

            if (fee.isFree()) continue;

            vData.getVillageById(caravan.getOriginVillageId())
                    .ifPresent(v -> v.withdrawFromTreasury(fee.amountBronze()));

            if (kingdom != null) kingdom.depositToTreasury(fee.amountBronze());

            roadData.addTollRevenue(nodeId, fee.amountBronze());
            roadData.markDirty();

            System.out.println("[TollGate] Caravan "
                    + caravan.getCaravanId().toString().substring(0, 8)
                    + " paid " + fee.amountBronze() + " bronze at gate "
                    + nodeId.toString().substring(0, 8)
                    + " (" + fee.reason() + ")");
        }
    }

    // =========================================================================
    // Player notification and crossing
    // =========================================================================

    private static void tickPlayer(ServerPlayer player,
                                    VillageSavedData vData,
                                    WorldRoadSavedData roadData,
                                    BlockPos gatePos,
                                    WorldRoadSavedData.TollGateRecord record,
                                    Kingdom kingdom) {
        BlockPos playerPos = player.blockPosition();
        UUID nodeId = record.nodeId();

        boolean withinNotify = withinRadius(playerPos, gatePos, PLAYER_NOTIFY_RADIUS);
        if (!withinNotify) return;

        boolean withinCross = withinRadius(playerPos, gatePos, PLAYER_CROSS_RADIUS);

        Set<UUID> notified = PLAYER_NOTIFIED.computeIfAbsent(
                player.getUUID(), k -> ConcurrentHashMap.newKeySet());

        if (!notified.contains(nodeId)) {
            int reputation = playerKingdomReputation(player.getUUID(), record.kingdomId(), vData);
            TollFeeCalculator.TollFee fee = TollFeeCalculator.computeFee(
                    record.tier(), 0, reputation);

            String kName = kingdom != null ? kingdom.getName() : "Unknown Kingdom";
            String msg = fee.isFree()
                    ? "You approach a toll gate of " + kName + ". Your reputation earns you free passage."
                    : "You approach a toll gate of " + kName + ". Upcoming toll: " + fee.amountBronze() + " bronze.";

            player.sendSystemMessage(Component.literal(msg));
            notified.add(nodeId);
        }

        if (withinCross) {
            int reputation = playerKingdomReputation(player.getUUID(), record.kingdomId(), vData);
            TollFeeCalculator.TollFee fee = TollFeeCalculator.computeFee(
                    record.tier(), 0, reputation);

            if (!fee.isFree()) {
                roadData.addTollRevenue(nodeId, fee.amountBronze());
                roadData.markDirty();
                System.out.println("[TollGate] Player " + player.getName().getString()
                        + " crossed gate " + nodeId.toString().substring(0, 8)
                        + " — fee " + fee.amountBronze() + " bronze ("
                        + fee.reason() + ") [no currency system yet]");
            }
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static boolean withinRadius(BlockPos a, BlockPos b, int radius) {
        int dx = a.getX() - b.getX();
        int dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz <= radius * radius;
    }

    private static BlockPos caravanLeadPos(Caravan caravan, ServerLevel level) {
        List<UUID> ids = caravan.getEntityIds();
        if (ids.isEmpty()) return null;
        var entity = level.getEntity(ids.get(0));
        return entity != null ? entity.blockPosition() : null;
    }

    private static long estimateGoodsValue(Caravan caravan) {
        return caravan.getGoods().stream()
                .mapToLong(stack -> (long) stack.getCount() * 10)
                .sum();
    }

    private static int playerKingdomReputation(UUID playerId, UUID kingdomId,
                                                VillageSavedData vData) {
        return vData.getAllKingdoms().stream()
                .filter(k -> k.getId().equals(kingdomId))
                .findFirst()
                .map(k -> k.getVillageIds().stream()
                        .mapToInt(vid ->
                                vData.getReputation(playerId, vid)
                                        .map(VillageReputation::getScore)
                                        .orElse(TollFeeCalculator.DEFAULT_REPUTATION))
                        .max()
                        .orElse(TollFeeCalculator.DEFAULT_REPUTATION))
                .orElse(TollFeeCalculator.DEFAULT_REPUTATION);
    }
}
