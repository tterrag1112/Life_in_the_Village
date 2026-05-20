package tterrag1112.life_in_the_village.Npc.Brain;

import net.minecraft.core.BlockPos;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-wide claim table for furniture seats. Seats live in the world
 * (slabs, stairs, etc.), so the registry can't live on a single entity
 * — it's static and keyed by immutable {@link BlockPos}.
 *
 * <p>Concurrent: server-thread access only in normal play, but we use
 * {@link ConcurrentHashMap} to be safe against any future async access
 * (e.g. server-shutdown teardown ticks).
 *
 * <p>Cleared on server lifecycle if needed; entries are auto-released
 * when {@code SitAtFurnitureBehavior.stop} fires.
 */
public final class SeatOccupancyRegistry {

    private SeatOccupancyRegistry() {}

    private static final Map<BlockPos, UUID> CLAIMS = new ConcurrentHashMap<>();

    /** Atomically claim a seat. Returns true if the claim succeeded
     *  (the seat was free or already claimed by this NPC). */
    public static boolean tryClaim(BlockPos seat, UUID npc) {
        UUID prev = CLAIMS.putIfAbsent(seat.immutable(), npc);
        return prev == null || prev.equals(npc);
    }

    /** Release a claim, but only if held by this NPC (idempotent). */
    public static void release(BlockPos seat, UUID npc) {
        CLAIMS.remove(seat.immutable(), npc);
    }

    /** True if the seat is unclaimed or claimed by this NPC. */
    public static boolean isFreeFor(BlockPos seat, UUID npc) {
        UUID held = CLAIMS.get(seat.immutable());
        return held == null || held.equals(npc);
    }

    /** Hard reset — server shutdown / dimension unload. */
    public static void clearAll() {
        CLAIMS.clear();
    }
}
