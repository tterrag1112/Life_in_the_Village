package tterrag1112.life_in_the_village.Village.Markets.Complex;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Economy.Market.MarketStall;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Canonical market-goods authority (market-stall-unification, 2026-06-12).
 *
 * <p><b>Design ruling (firm):</b> a market's tradeable goods live <i>only</i>
 * in its stall chests. The market BUILDING's own storage (chests inside the
 * building bounds) is <b>never</b> a merchant source or sink. The building
 * keeps non-inventory roles (plaza, stall-band geometry, market-day events).</p>
 *
 * <p>Where {@link StallGoods} operates on a <i>single</i> stall (the merchant's
 * own pitch, with the building hub as overflow — the pre-unification model),
 * this facade operates across <b>all active stall chests of a market</b> and
 * <b>never</b> touches building bounds. It is the single funnel every market
 * count / take / store path must route through.</p>
 *
 * <h3>Container-position cache (noon-meal-perf, 2026-06-12)</h3>
 * Discovery of the backing containers was a per-stall footprint cube scan —
 * {@code (2*FOOTPRINT_SIZE+1)^3} = 11³ = 1331 {@code getBlockEntity} lookups
 * <i>per stall, per call</i>. {@link #countItem} / {@link #takeItem} /
 * {@link #store} each rescanned; {@code EatMealBehavior.findAnyFoodSource}
 * multiplied that by the food-item count of the price registry. At the meal
 * window (many NPCs food-seeking) this dominated the server tick. We now cache
 * the <i>discovered container positions</i> per market (keyed by market UUID),
 * so a query re-resolves only the handful of known block-entity positions
 * instead of re-scanning the cube. The cache is invalidated on a short TTL
 * ({@link #CACHE_TTL_TICKS}), when the active-stall-set size changes, or when a
 * cached position no longer resolves to a {@link Container} (a chest was broken
 * or a chunk unloaded). Correctness: a stale-but-valid position set can at
 * worst miss a container added in the last {@link #CACHE_TTL_TICKS} ticks
 * (≈10 s) — the merchant deposit / read converges on the next refresh; it never
 * resolves a wrong container because every resolve is re-validated against the
 * live block entity each call.
 *
 * <h3>Clean-failure contract</h3>
 * {@link #store} returns {@code false} (and leaves the stack intact) when no
 * stall chest can absorb the goods. Callers MUST honour that boolean and keep
 * the goods with the seller rather than dropping them — the silent-drop that
 * destroyed items when the building had no chests is the bug this class exists
 * to kill. A failed store logs one WARN naming the market.
 */
public final class MarketInventory {

    private static final Logger LOGGER = LogUtils.getLogger();
    // One-shot per JVM so a chest-less market surfaces once, not per tick.
    private static volatile boolean LOGGED_NO_SINK = false;

    private MarketInventory() {}

    // -- Container-position cache ---------------------------------------------

    /** How long a discovered position set stays valid (ticks). Short enough
     *  that a newly placed/claimed stall is picked up within ~10 s, long
     *  enough that the meal-window read storm reuses one scan. */
    private static final long CACHE_TTL_TICKS = 200L;

    /** Cached footprint-scan result for one market. {@code positions} are the
     *  block positions that resolved to a {@link Container} at scan time;
     *  {@code expiry} is the game tick at/after which we rescan; {@code stalls}
     *  is the active-stall count at scan time (a cheap structural signature). */
    private record CacheEntry(List<BlockPos> positions, long expiry, int stalls) {}

    /** Per-market position cache. Concurrent because block-entity ticking and
     *  the server thread both touch markets; entries are immutable snapshots
     *  so a stale read is harmless. Cleared wholesale by {@link #invalidate}. */
    private static final Map<UUID, CacheEntry> POSITION_CACHE = new ConcurrentHashMap<>();

    /** Drops the cached position set for {@code marketId}, forcing a fresh
     *  footprint scan on the next query. Call after a stall is claimed,
     *  vacated, or its chest moved if you need the change visible before the
     *  TTL elapses; otherwise the TTL handles it. */
    public static void invalidate(UUID marketId) {
        if (marketId != null) POSITION_CACHE.remove(marketId);
    }

    /** Drops the entire cache (e.g. on world unload / dimension change). */
    public static void invalidateAll() {
        POSITION_CACHE.clear();
    }

    // -- Stall-chest enumeration ----------------------------------------------

    /** Half-extent (blocks) of the cube scanned around each stall's
     *  origin/chest position for container block entities. Covers the
     *  {@code stall_1} footprint ({@link MarketStall#FOOTPRINT_SIZE} = 5)
     *  with slack for rotation: the placed structure can extend in
     *  negative directions from the placement origin depending on its
     *  rotation, so a symmetric cube around the anchor is the robust
     *  geometry given only {@code stallOrigin} is persisted (the placer's
     *  exact placed box is not stored on the stall record). */
    private static final int STALL_SCAN_RADIUS = MarketStall.FOOTPRINT_SIZE;

    /** Live containers backing every active stall of {@code market}.
     *
     *  <p>Resolution is cache-first: when a fresh {@link CacheEntry} exists we
     *  re-resolve its recorded positions to live {@link Container}s (cheap — a
     *  handful of {@code getBlockEntity} calls), skipping the cube scan. On a
     *  cache miss/expiry, a structural-signature change, or any cached position
     *  failing to resolve, we fall back to the full footprint scan
     *  ({@link #scanAndCache}) and refresh the cache.
     *
     *  <p>Discovery (on the scan path) is by <b>footprint scan</b>, not a single
     *  chest pos: the stall structures place multiple BARRELS (3 each in the
     *  current templates), and barrels are {@code BarrelBlockEntity}, not
     *  {@code ChestBlockEntity}. We collect <b>every</b> {@link Container} block
     *  entity — chests, barrels, trapped chests, any container — deduped by the
     *  {@link Container} identity so a double chest's two halves (one logical
     *  container) aren't counted twice.
     */
    public static List<Container> stallChests(ServerLevel level, Building market) {
        if (market == null) return new ArrayList<>();
        // noon-meal-perf — coarse market-scan attribution (accumulate-always,
        // report-rarely via NoonProfile). Covers both the cheap cache-resolve
        // path and the expensive footprint-scan fallback on a cache miss.
        long t0 = System.nanoTime();
        try {
            VillageSavedData data = VillageSavedData.get(level);
            List<MarketStall> stalls = data.getStallsForMarket(market.getId());
            int activeStalls = 0;
            for (MarketStall s : stalls) if (s.isActive()) activeStalls++;

            long now = level.getGameTime();
            CacheEntry cached = POSITION_CACHE.get(market.getId());
            if (cached != null && now < cached.expiry() && cached.stalls() == activeStalls) {
                List<Container> resolved = resolveCached(level, cached.positions());
                if (resolved != null) return resolved; // null = a position went stale
            }
            return scanAndCache(level, market, stalls, activeStalls, now);
        } finally {
            tterrag1112.life_in_the_village.Events.NoonProfile
                    .addMarketScan(System.nanoTime() - t0);
        }
    }

    /** Re-resolves cached positions to live containers, deduped by identity.
     *  Returns {@code null} (signalling a rescan) if any cached position no
     *  longer holds a {@link Container} — a broken chest or unloaded chunk. */
    private static List<Container> resolveCached(ServerLevel level, List<BlockPos> positions) {
        List<Container> out = new ArrayList<>(positions.size());
        java.util.Set<Container> seen = new java.util.HashSet<>();
        for (BlockPos pos : positions) {
            BlockEntity be = level.getBlockEntity(pos);
            if (!(be instanceof Container c)) return null; // stale → force rescan
            if (seen.add(c)) out.add(c);
        }
        return out;
    }

    /** Full footprint scan across all active stalls; records the hit positions
     *  in {@link #POSITION_CACHE} and returns the resolved containers. */
    private static List<Container> scanAndCache(ServerLevel level, Building market,
                                                List<MarketStall> stalls, int activeStalls,
                                                long now) {
        List<Container> out = new ArrayList<>();
        List<BlockPos> positions = new ArrayList<>();
        java.util.Set<Container> seen = new java.util.HashSet<>();
        for (MarketStall s : stalls) {
            if (!s.isActive()) continue;
            collectStallContainers(level, s, out, positions, seen);
        }
        POSITION_CACHE.put(market.getId(),
                new CacheEntry(List.copyOf(positions), now + CACHE_TTL_TICKS, activeStalls));
        return out;
    }

    /** Live containers backing a single {@code stall} (footprint scan).
     *  Same discovery as {@link #stallChests} for one stall — used by
     *  {@code MerchantStartingStock} so initial stock lands in the same
     *  containers the read paths later see. Not cached (single-stall, called
     *  rarely at stocking time). */
    public static List<Container> stallContainers(ServerLevel level, MarketStall stall) {
        List<Container> out = new ArrayList<>();
        if (stall == null) return out;
        collectStallContainers(level, stall, out, new ArrayList<>(), new java.util.HashSet<>());
        return out;
    }

    /** Appends every {@link Container} block entity in {@code stall}'s
     *  footprint to {@code out} (and its position to {@code positionsOut}).
     *  Anchor for the scan is the recorded chest pos when present, else the
     *  stall origin. Containers are deduped via {@code seen} so adjacent halves
     *  of a double chest (which share one {@code Container}) are added once. */
    private static void collectStallContainers(ServerLevel level, MarketStall stall,
                                               List<Container> out, List<BlockPos> positionsOut,
                                               java.util.Set<Container> seen) {
        BlockPos anchor = stall.getChestPos();
        if (anchor == null || anchor.equals(BlockPos.ZERO)) {
            anchor = stall.getStallOrigin();
        }
        if (anchor == null || anchor.equals(BlockPos.ZERO)) return;
        int r = STALL_SCAN_RADIUS;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos p = anchor.offset(dx, dy, dz);
                    BlockEntity be = level.getBlockEntity(p);
                    if (be instanceof Container c && seen.add(c)) {
                        out.add(c);
                        positionsOut.add(p.immutable());
                    }
                }
            }
        }
    }

    // -- Read -----------------------------------------------------------------

    /** Total of {@code item} across all of the market's stall chests. */
    public static int countItem(ServerLevel level, Building market, Item item) {
        int total = 0;
        for (Container c : stallChests(level, market)) {
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (s.is(item)) total += s.getCount();
            }
        }
        return total;
    }

    public static boolean hasItem(ServerLevel level, Building market, Item item, int count) {
        return countItem(level, market, item) >= count;
    }

    // -- Take -----------------------------------------------------------------

    /**
     * Removes up to {@code count} of {@code item} across the market's stall
     * chests. Returns the number actually taken (may be &lt; count). The
     * caller decides whether a partial take is acceptable.
     */
    public static int takeUpTo(ServerLevel level, Building market, Item item, int count) {
        int remaining = count;
        for (Container c : stallChests(level, market)) {
            for (int i = 0; i < c.getContainerSize() && remaining > 0; i++) {
                ItemStack s = c.getItem(i);
                if (!s.is(item)) continue;
                int take = Math.min(remaining, s.getCount());
                s.shrink(take);
                remaining -= take;
                if (s.isEmpty()) c.setItem(i, ItemStack.EMPTY);
            }
            if (remaining == 0) break;
        }
        return count - remaining;
    }

    /** All-or-nothing take: takes {@code count} only if the market holds at
     *  least that many across its stalls; otherwise takes nothing. */
    public static boolean takeItem(ServerLevel level, Building market, Item item, int count) {
        if (countItem(level, market, item) < count) return false;
        return takeUpTo(level, market, item, count) == count;
    }

    // -- Store ----------------------------------------------------------------

    /**
     * Stores {@code stack} into the market's stall chests (merge then empty
     * slots, across all active stalls). Mutates {@code stack} as it lands.
     *
     * @return {@code true} when the whole stack was absorbed; {@code false}
     *         (stack left with whatever didn't fit) when the market has no
     *         stall chest with room. A {@code false} return is the clean-fail
     *         signal: the caller keeps the goods, nothing is destroyed.
     */
    public static boolean store(ServerLevel level, Building market, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return true;
        List<Container> chests = stallChests(level, market);
        for (Container c : chests) {
            for (int i = 0; i < c.getContainerSize() && !stack.isEmpty(); i++) {
                ItemStack ex = c.getItem(i);
                if (ex.is(stack.getItem()) && ex.getCount() < ex.getMaxStackSize()) {
                    int add = Math.min(ex.getMaxStackSize() - ex.getCount(), stack.getCount());
                    ex.grow(add);
                    stack.shrink(add);
                }
            }
            for (int i = 0; i < c.getContainerSize() && !stack.isEmpty(); i++) {
                if (c.getItem(i).isEmpty()) {
                    c.setItem(i, stack.copy());
                    stack.setCount(0);
                }
            }
            if (stack.isEmpty()) return true;
        }
        if (!stack.isEmpty() && !LOGGED_NO_SINK) {
            LOGGER.warn("[MarketInventory] no stall chest could absorb {} at market {} "
                    + "(active stall chests={}); goods stay with the seller — NOT dropped. "
                    + "Seed/claim a stall or add stall storage.",
                    stack, market == null ? "(null)" : market.getName(), chests.size());
            LOGGED_NO_SINK = true;
        }
        return stack.isEmpty();
    }
}
