package tterrag1112.life_in_the_village.Entities;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player-per-NPC "primed to gift" state. Activating the primer
 * tells the dispatcher that the player's next right-click on this NPC
 * while holding any item should be treated as a gift — even if the
 * held item would otherwise route somewhere else (Q7 / 3.3
 * priority documented in {@code NpcInteractionHandler}).
 *
 * <p>State expires after {@link #EXPIRY_TICKS} (~60s) so an
 * accidental prime doesn't strand later interactions. Empty-hand
 * right-click during primed state cancels the primer.</p>
 *
 * <p>Server-side, in-memory. The primed state survives the player
 * walking around but is dropped on disconnect (cleanup not strictly
 * required; entries simply expire).</p>
 */
public final class GiftPrimer {

    /** Primer lifetime in ticks. 1200 = 60s. */
    public static final long EXPIRY_TICKS = 1200L;

    private record Key(UUID playerId, UUID npcId) {}

    /** Tick at which the primer was set; expiry = +EXPIRY_TICKS. */
    private static final Map<Key, Long> PRIMED = new ConcurrentHashMap<>();

    private GiftPrimer() {}

    public static void prime(UUID playerId, UUID npcId, long currentTick) {
        if (playerId == null || npcId == null) return;
        PRIMED.put(new Key(playerId, npcId), currentTick);
    }

    public static void clear(UUID playerId, UUID npcId) {
        if (playerId == null || npcId == null) return;
        PRIMED.remove(new Key(playerId, npcId));
    }

    public static boolean isPrimed(UUID playerId, UUID npcId, long currentTick) {
        if (playerId == null || npcId == null) return false;
        Long set = PRIMED.get(new Key(playerId, npcId));
        if (set == null) return false;
        if (currentTick - set > EXPIRY_TICKS) {
            PRIMED.remove(new Key(playerId, npcId));
            return false;
        }
        return true;
    }

    /** True iff this player is primed against this NPC right now. */
    public static boolean isPrimed(ServerPlayer player, TownspersonMob npc) {
        if (player == null || npc == null) return false;
        if (!(npc.level() instanceof ServerLevel level)) return false;
        return isPrimed(player.getUUID(), npc.getUUID(), level.getGameTime());
    }
}
