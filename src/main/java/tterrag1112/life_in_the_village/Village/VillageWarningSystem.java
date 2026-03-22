package tterrag1112.life_in_the_village.Village;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Profession.ProfessionPerkManager;

import java.util.*;
import java.util.stream.Collectors;

public class VillageWarningSystem {

    // Villages within this range are considered "connected"
    private static final double TRADE_ROUTE_RADIUS = 1500.0;

    // How often warnings spread — every 2 in-game days
    private static final long SPREAD_INTERVAL = 48000L;

    // Reputation drop applied to warned villages
    private static final int WARNING_REP_PENALTY = -10;

    // Reputation threshold above which warning clears
    private static final int CLEAR_THRESHOLD = 0;

    // -------------------------------------------------------------------------
    // Warning issuance
    // -------------------------------------------------------------------------

    /**
     * Issues a warning against a player from a specific village.
     * Immediately applies reputation penalty to the source village.
     */
    public static void issueWarning(UUID playerId,
                                    UUID sourceVillageId,
                                    ServerLevel level,
                                    VillageSavedData data) {
        // Don't issue duplicate warnings
        if (data.hasWarning(playerId, sourceVillageId)) return;

        data.addPlayerWarning(level, playerId, sourceVillageId,
                level.getGameTime());
        // Apply immediate rep penalty to source village
        data.getVillageById(sourceVillageId).ifPresent(village -> {
            village.modifyReputation(playerId, WARNING_REP_PENALTY);
            data.setDirty();

            village.getVillageGuards(level).forEach(guard ->
                    guard.setCurrentActivity("Investigating theft"));

            ProfessionPerkManager.onVillageAlarm(level, village);

            // Notify player if online
            level.getServer().getPlayerList()
                    .getPlayer(playerId);
            var player = level.getServer().getPlayerList()
                    .getPlayer(playerId);
            if (player != null) {
                player.displayClientMessage(
                        Component.literal(
                                        "An adventurer group has reported you "
                                                + "to " + village.getName() + "! "
                                                + "Your reputation has decreased.")
                                .withStyle(net.minecraft.ChatFormatting.RED),
                        false);
            }
        });

    }

    // -------------------------------------------------------------------------
    // Warning spread tick — call from WorldEvents
    // -------------------------------------------------------------------------

    /**
     * Spreads warnings to connected villages over time.
     * Should be called from the server tick event.
     */
    public static void tickWarningSpread(ServerLevel level,
                                         VillageSavedData data,
                                         long currentTick) {
        if (currentTick % SPREAD_INTERVAL != 0) return;

        Map<UUID, Map<UUID, Long>> warnings = data.getActiveWarnings();
        if (warnings.isEmpty()) return;

        // For each warned player
        warnings.forEach((playerId, villageWarnings) -> {
            List<UUID> toSpread = new ArrayList<>(villageWarnings.keySet());

            toSpread.forEach(sourceVillageId -> {
                // Find connected villages that don't have the warning yet
                List<Village> connected = findConnectedVillages(
                        sourceVillageId, data, level);

                connected.stream()
                        .filter(v -> !data.hasWarning(
                                playerId, v.getId()))
                        .forEach(v -> {
                            // Spread warning with same penalty
                            data.addPlayerWarning(level,
                                    playerId, v.getId(), currentTick);
                            v.modifyReputation(
                                    playerId, WARNING_REP_PENALTY);

                            // Notify player if online
                            var player = level.getServer()
                                    .getPlayerList()
                                    .getPlayer(playerId);
                            if (player != null) {
                                player.displayClientMessage(
                                        Component.literal(
                                                        "Word of your actions has "
                                                                + "reached " + v.getName()
                                                                + "!")
                                                .withStyle(
                                                        net.minecraft.ChatFormatting
                                                                .RED),
                                        false);
                            }

                            System.out.println("Warning spread to village "
                                    + v.getName() + " for player "
                                    + playerId);
                        });
            });
        });

        data.setDirty();
    }

    // -------------------------------------------------------------------------
    // Warning clearing
    // -------------------------------------------------------------------------

    /**
     * Checks all warnings for a player and clears any where
     * reputation has recovered above the threshold.
     * Call this periodically or on player login.
     */
    public static void checkAndClearWarnings(UUID playerId,
                                             VillageSavedData data) {
        Map<UUID, Long> warnings = data.getActiveWarnings()
                .getOrDefault(playerId, Map.of());

        if (warnings.isEmpty()) return;

        List<UUID> toRemove = new ArrayList<>();

        warnings.keySet().forEach(villageId ->
                data.getVillageById(villageId).ifPresent(village -> {
                    int rep = village.getReputation(playerId);
                    if (rep > CLEAR_THRESHOLD) {
                        toRemove.add(villageId);
                        System.out.println("Warning cleared for player "
                                + playerId + " in village "
                                + village.getName()
                                + " (rep: " + rep + ")");
                    }
                }));

        toRemove.forEach(villageId ->
                data.clearWarning(playerId, villageId));

        if (!toRemove.isEmpty()) data.setDirty();
    }

    // -------------------------------------------------------------------------
    // Proximity-based connection check
    // -------------------------------------------------------------------------

    public static List<Village> findConnectedVillages(
            UUID villageId,
            VillageSavedData data,
            ServerLevel level) {
        Village source = data.getVillageById(villageId).orElse(null);
        if (source == null) return List.of();

        BlockPos sourceCenter = source.getBounds(data)
                .map(b -> BlockPos.containing(b.getCenter()))
                .orElse(null);
        if (sourceCenter == null) return List.of();

        return data.getAllVillages().stream()
                .filter(v -> !v.getId().equals(villageId))
                .filter(v -> v.getBounds(data)
                        .map(b -> BlockPos.containing(b.getCenter())
                                .closerThan(sourceCenter,
                                        TRADE_ROUTE_RADIUS))
                        .orElse(false))
                .collect(Collectors.toList());
    }

    // -------------------------------------------------------------------------
    // Query helpers
    // -------------------------------------------------------------------------

    /**
     * Returns true if a player has any active warning in any village.
     */
    public static boolean hasAnyWarning(UUID playerId,
                                        VillageSavedData data) {
        return !data.getActiveWarnings()
                .getOrDefault(playerId, Map.of())
                .isEmpty();
    }

    /**
     * Returns all villages that have warned a player.
     */
    public static List<Village> getWarnedVillages(UUID playerId,
                                                  VillageSavedData data) {
        return data.getActiveWarnings()
                .getOrDefault(playerId, Map.of())
                .keySet().stream()
                .map(data::getVillageById)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toList());
    }
}