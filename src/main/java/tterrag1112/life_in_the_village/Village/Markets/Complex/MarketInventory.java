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

    // -- Stall-chest enumeration ----------------------------------------------

    /** Live containers backing every active stall of {@code market} that has a
     *  real (non-ZERO) chest position. Order is the stall list order. */
    public static List<Container> stallChests(ServerLevel level, Building market) {
        List<Container> out = new ArrayList<>();
        if (market == null) return out;
        VillageSavedData data = VillageSavedData.get(level);
        for (MarketStall s : data.getStallsForMarket(market.getId())) {
            if (!s.isActive()) continue;
            BlockPos cp = s.getChestPos();
            if (cp == null || cp.equals(BlockPos.ZERO)) continue;
            BlockEntity be = level.getBlockEntity(cp);
            if (be instanceof Container c) out.add(c);
        }
        return out;
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
        for (Container c : stallChests(level, market)) {
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
                    stack, market == null ? "(null)" : market.getName(),
                    stallChests(level, market).size());
            LOGGED_NO_SINK = true;
        }
        return stack.isEmpty();
    }
}
