package tterrag1112.life_in_the_village.Village.Markets.Complex;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Economy.Market.MarketStall;

/**
 * Per-stall goods authority (merchant arc Phase 2c). Finishes the
 * "stall-as-endpoint" decision 1b started: 1b made the stall the money
 * endpoint ({@code NpcEconomy.resolveStallEndpoint}); 2c makes the same
 * stall's chest the <b>goods</b> endpoint. The market-building hub is
 * <b>overflow / backstock</b>: a buy draws from the stall first then the
 * hub; a sell fills the stall first then spills to the hub. A stall-less
 * sale (no stall holds the trade) uses the hub directly.
 *
 * <p>Consolidates the duplicated take/store-from-chest logic that lived in
 * {@code TradeHandler} (stall chest vs {@code storeExcludingStalls}) and
 * {@code MarketChannel} (hub-only via {@code BuildingStorageAccess}).
 * Goods movement only — money is the 1b settle's job, and the two line up
 * on the same stall.
 */
public final class StallGoods {

    private StallGoods() {}

    /**
     * Goods available for a trade: the merchant's own stall chest plus every
     * other active stall chest at the market. The market BUILDING's own
     * storage is NEVER counted (market-stall-unification: stalls are the only
     * merchant inventory).
     */
    public static int available(ServerLevel level, Building market,
                                MarketStall stall, Item item) {
        // The whole market's stall inventory already includes this stall's
        // chest, so a separate stall count would double-count it.
        if (market != null) return MarketInventory.countItem(level, market, item);
        return countIn(stallContainer(level, stall), item);
    }

    /**
     * Takes up to {@code qty} of {@code item} for a trade — stall chest
     * first, then the hub backstock. Returns the number actually taken.
     */
    public static int take(ServerLevel level, Building market, MarketStall stall,
                           Item item, int qty) {
        int remaining = qty;
        Container sc = stallContainer(level, stall);
        if (sc != null) remaining -= takeFrom(sc, item, remaining);
        // Overflow draws from the market's OTHER stall chests, never the
        // building's own storage (market-stall-unification).
        if (remaining > 0 && market != null) {
            remaining -= MarketInventory.takeUpTo(level, market, item, remaining);
        }
        return qty - remaining;
    }

    /**
     * Stores {@code stack} for a trade — into the stall chest first,
     * overflowing to the hub only when the stall is full. A {@code null}
     * stall stores straight to the hub (stall-less sale).
     */
    /**
     * Stores {@code stack} for a trade — into the merchant's own stall chest
     * first, overflowing to the market's OTHER stall chests when it is full.
     * The market BUILDING's storage is NEVER a sink. A {@code null} stall
     * stores straight across the market's stalls.
     *
     * @return {@code true} when the whole stack landed in a stall chest;
     *         {@code false} (stack left with the remainder) when no stall
     *         chest had room — the caller must keep the goods, never drop
     *         them (market-stall-unification clean-fail contract).
     */
    public static boolean store(ServerLevel level, Building market, MarketStall stall,
                                ItemStack stack) {
        if (stack == null || stack.isEmpty()) return true;
        Container sc = stallContainer(level, stall);
        if (sc != null) storeInto(sc, stack);
        if (!stack.isEmpty() && market != null) {
            return MarketInventory.store(level, market, stack);
        }
        return stack.isEmpty();
    }

    // ── Internals ──────────────────────────────────────────────────────────

    private static Container stallContainer(ServerLevel level, MarketStall stall) {
        if (stall == null) return null;
        BlockPos cp = stall.getChestPos();
        if (cp == null || cp.equals(BlockPos.ZERO)) return null;
        BlockEntity be = level.getBlockEntity(cp);
        return be instanceof Container c ? c : null;
    }

    private static int countIn(Container c, Item item) {
        if (c == null) return 0;
        int n = 0;
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack s = c.getItem(i);
            if (s.is(item)) n += s.getCount();
        }
        return n;
    }

    /** Removes up to {@code max} of {@code item} from {@code c}; returns taken. */
    private static int takeFrom(Container c, Item item, int max) {
        if (c == null || max <= 0) return 0;
        int taken = 0;
        for (int i = 0; i < c.getContainerSize() && taken < max; i++) {
            ItemStack s = c.getItem(i);
            if (!s.is(item)) continue;
            int t = Math.min(max - taken, s.getCount());
            s.shrink(t);
            taken += t;
            if (s.isEmpty()) c.setItem(i, ItemStack.EMPTY);
        }
        return taken;
    }

    /** Fills existing matching stacks then empty slots; mutates {@code stack}. */
    private static void storeInto(Container c, ItemStack stack) {
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
    }
}
