package tterrag1112.life_in_the_village.Npc.BusinessFront;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Tracks which building each connected player is currently standing in.
 * Spec line 33.
 *
 * <p>Updated once per player tick from {@link
 * tterrag1112.life_in_the_village.Events.PlayerEventProximityHandler}
 * — one {@link VillageSavedData#getBuildingAt} per player per tick is
 * cheap (low single-digit microseconds) and the spec acknowledges
 * the cost (line 47).</p>
 *
 * <p>Transition listeners fire on enter / leave so downstream
 * subsystems (greeter assignment now, crime trespassing in next
 * session) can react without polling.</p>
 */
public final class BuildingPresenceTracker {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Map<UUID, BuildingPresence> STATE = new HashMap<>();

    /** Listeners notified on (player, buildingId) leave. */
    private static final java.util.List<BiConsumer<ServerPlayer, UUID>> LEAVE_LISTENERS = new java.util.ArrayList<>();
    /** Listeners notified on (player, buildingId) enter. */
    private static final java.util.List<BiConsumer<ServerPlayer, UUID>> ENTER_LISTENERS = new java.util.ArrayList<>();

    private BuildingPresenceTracker() {}

    // ── Tick + cleanup ────────────────────────────────────────────────────

    /** Called from the server-side player tick. Cheap. */
    public static void onPlayerTick(ServerPlayer player) {
        if (player == null) return;
        if (!(player.level() instanceof ServerLevel level)) return;

        VillageSavedData data = VillageSavedData.get(level);
        BlockPos pos = player.blockPosition();
        Optional<Building> current = data.getBuildingAt(pos);

        BuildingPresence prev = STATE.get(player.getUUID());
        UUID newId  = current.map(Building::getId).orElse(null);
        UUID prevId = prev != null ? prev.buildingId() : null;

        if (Objects.equals(newId, prevId)) return; // no change

        if (prevId != null) {
            for (BiConsumer<ServerPlayer, UUID> l : LEAVE_LISTENERS) {
                try { l.accept(player, prevId); }
                catch (Throwable t) { LOGGER.warn("[BuildingPresenceTracker] leave listener threw: {}", t.getMessage()); }
            }
        }
        if (newId != null) {
            for (BiConsumer<ServerPlayer, UUID> l : ENTER_LISTENERS) {
                try { l.accept(player, newId); }
                catch (Throwable t) { LOGGER.warn("[BuildingPresenceTracker] enter listener threw: {}", t.getMessage()); }
            }
        }
        STATE.put(player.getUUID(), new BuildingPresence(
                player.getUUID(), newId, level.getGameTime()));
    }

    /** Cleans up tracker entries when the player disconnects. */
    public static void onPlayerLogout(UUID playerId) {
        if (playerId == null) return;
        STATE.remove(playerId);
    }

    // ── Listener registration ──────────────────────────────────────────────

    public static synchronized void registerEnterListener(BiConsumer<ServerPlayer, UUID> listener) {
        if (listener != null) ENTER_LISTENERS.add(listener);
    }

    public static synchronized void registerLeaveListener(BiConsumer<ServerPlayer, UUID> listener) {
        if (listener != null) LEAVE_LISTENERS.add(listener);
    }

    // ── Queries ────────────────────────────────────────────────────────────

    public static Optional<UUID> getBuildingFor(UUID playerId) {
        if (playerId == null) return Optional.empty();
        BuildingPresence p = STATE.get(playerId);
        return p == null ? Optional.empty() : Optional.ofNullable(p.buildingId());
    }

    public static Optional<BuildingPresence> presence(UUID playerId) {
        return Optional.ofNullable(STATE.get(playerId));
    }

    /** True iff the player is currently inside a {@link BuildingTypeFlags#hasBusinessFront} building. */
    public static boolean isInBusiness(ServerPlayer player, ServerLevel level) {
        if (player == null || level == null) return false;
        UUID id = getBuildingFor(player.getUUID()).orElse(null);
        if (id == null) return false;
        return VillageSavedData.get(level).getBuildingById(id)
                .map(b -> BuildingTypeFlags.hasBusinessFront(b.getType()))
                .orElse(false);
    }

    /** Snapshot of current presences for the {@code /business presence} command. */
    public static Map<UUID, BuildingPresence> snapshot() {
        return java.util.Collections.unmodifiableMap(new HashMap<>(STATE));
    }
}
