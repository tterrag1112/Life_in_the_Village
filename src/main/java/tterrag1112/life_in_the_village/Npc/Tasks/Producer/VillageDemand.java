package tterrag1112.life_in_the_village.Npc.Tasks.Producer;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Economy.VillageEconomy;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Per-village cache for {@link VillageEconomy#computeStockpileTargets}.
 *
 * <p>{@code computeStockpileTargets} is a live scan (entity counts +
 * inventory reads), moderately expensive. {@link ProductionTaskSource#generate}
 * runs on every NPC every ~100 ticks, so a village with N producers would
 * trigger N scans per 100 ticks without this cache.</p>
 *
 * <p>The cache holds one entry per village (keyed by {@link Village#getId()})
 * and recomputes at most once per {@link #TTL_TICKS} (1200 ticks ≈ 60 s).
 * All reads/writes happen on the server tick thread (same thread as the brain
 * behaviors that call generate), so no synchronisation is needed.</p>
 *
 * <p>Items not present in the returned map have no per-item demand signal;
 * callers must fall back to their hardcoded spec quota for those items.</p>
 */
public final class VillageDemand {

    private VillageDemand() {}

    /** How long a cached demand snapshot lives (ticks). 1200 = 60 s at 20 tps. */
    static final long TTL_TICKS = 1200L;

    private record CachedTargets(long computedAt, Map<Item, Integer> targets) {}

    /** One entry per village UUID. Populated lazily on first access per village. */
    private static final Map<UUID, CachedTargets> CACHE = new HashMap<>();

    /**
     * Returns the demand-derived stockpile targets for {@code village},
     * recomputing at most once per {@link #TTL_TICKS}.
     *
     * <p>The returned map is read-only and covers only the subset of items
     * that {@link VillageEconomy#computeStockpileTargets} tracks (food
     * staples, building materials, seeds, and profession-driven tools).
     * Items absent from the map have no demand signal.</p>
     *
     * @return an unmodifiable snapshot; never null, may be empty.
     */
    public static Map<Item, Integer> targetsFor(
            ServerLevel level, Village village, VillageSavedData data) {
        long now = level.getGameTime();
        UUID id = village.getId();
        CachedTargets cached = CACHE.get(id);
        if (cached != null && now - cached.computedAt() < TTL_TICKS) {
            return cached.targets();
        }
        Map<Item, Integer> fresh = VillageEconomy.computeStockpileTargets(level, village, data);
        CACHE.put(id, new CachedTargets(now, Map.copyOf(fresh)));
        return fresh;
    }

    /**
     * Evict the cache entry for a village (e.g. when a village is removed
     * or its population changes dramatically). Not required for correctness —
     * the TTL covers drift — but available for callers that want immediate
     * re-evaluation.
     */
    public static void evict(UUID villageId) {
        CACHE.remove(villageId);
    }
}
