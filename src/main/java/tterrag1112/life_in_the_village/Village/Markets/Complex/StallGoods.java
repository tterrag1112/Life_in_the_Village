package tterrag1112.life_in_the_village.Village.Markets.Complex;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Economy.Market.MarketStall;

/**
 * Per-stall goods authority. A merchant reads and writes <b>only its own
 * assigned stall</b> — no cross-stall draw, no market-wide pool
 * (merchant-stall-fixes B de-pooled the old "stall first, then overflow
 * across every stall" behaviour). The market BUILDING's own storage is
 * never an endpoint either (that part of market-stall-unification stays:
 * merchant goods live in stalls, never the building). The {@code market}
 * argument is retained only for call-site symmetry and is no longer read
 * for goods movement.
 *
 * <p>Container resolution uses {@link MarketInventory#stallContainers}, the
 * canonical single-stall footprint scan, so a stall's BARRELS (the stall
 * templates place barrels, not chests) are all covered — not just the one
 * block at {@code getChestPos()}.
 *
 * <p>Goods movement only — money is the settle's job
 * ({@code NpcEconomy.resolveStallEndpoint}), and the two line up on the
 * same stall.
 */
public final class StallGoods {

    private StallGoods() {}

    /**
     * Goods available for a trade: the total of {@code item} across the
     * merchant's OWN stall containers only (merchant-stall-fixes B — no
     * cross-stall pool). {@code market} is unused.
     */
    public static int available(ServerLevel level, Building market,
                                MarketStall stall, Item item) {
        int total = 0;
        for (Container c : stallContainers(level, stall)) {
            total += countIn(c, item);
        }
        return total;
    }

    /**
     * Takes up to {@code qty} of {@code item} for a trade — from the
     * merchant's OWN stall containers only (merchant-stall-fixes B — no
     * overflow across other stalls). Returns the number actually taken.
     * {@code market} is unused.
     */
    public static int take(ServerLevel level, Building market, MarketStall stall,
                           Item item, int qty) {
        int remaining = qty;
        for (Container c : stallContainers(level, stall)) {
            if (remaining <= 0) break;
            remaining -= takeFrom(c, item, remaining);
        }
        return qty - remaining;
    }

    /**
     * Stores {@code stack} for a trade — into the merchant's OWN stall
     * containers only (merchant-stall-fixes B — no overflow to other stalls,
     * and never the market BUILDING). Mutates {@code stack} as it lands.
     * {@code market} is unused.
     *
     * @return {@code true} when the whole stack landed in this stall's
     *         containers; {@code false} (stack left with the remainder) when
     *         this stall had no room — the caller must keep the goods, never
     *         drop them (clean-fail contract). A {@code null}/uninitialised
     *         stall (no containers) also returns {@code false} with the stack
     *         intact.
     */
    public static boolean store(ServerLevel level, Building market, MarketStall stall,
                                ItemStack stack) {
        if (stack == null || stack.isEmpty()) return true;
        for (Container c : stallContainers(level, stall)) {
            storeInto(c, stack);
            if (stack.isEmpty()) return true;
        }
        return stack.isEmpty();
    }

    // ── Internals ──────────────────────────────────────────────────────────

    /** The merchant's own stall containers (footprint scan, barrels included)
     *  via the canonical single-stall resolver. Empty list for a null stall
     *  or one with no discoverable containers. */
    private static java.util.List<Container> stallContainers(ServerLevel level,
                                                             MarketStall stall) {
        if (stall == null) return java.util.List.of();
        return MarketInventory.stallContainers(level, stall);
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
