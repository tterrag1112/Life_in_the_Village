package tterrag1112.life_in_the_village.Village.Economy.Currency;

import net.minecraft.nbt.CompoundTag;

/**
 * Virtual wallet for NPC personal wealth.
 *
 * <p>Replaces loose coin items in the NPC's personal inventory. The value
 * is a plain long so wealth queries and transfers are O(1). Physical coin
 * items are only materialised visually during transactions via
 * {@link NpcTransactionVisual}.</p>
 *
 * <h3>Persistence</h3>
 * Save/load via {@link #save}/{@link #load}. Called from
 * {@code EconomyComponent.save/load}.
 *
 * <h3>Migration</h3>
 * On first load of an NPC that still has physical coins in their inventory,
 * {@link #migrateFromInventory} pulls the value out and zeros the slots.
 */
public final class NpcWallet {

    private long bronze;

    public NpcWallet(long bronze) {
        this.bronze = Math.max(0, bronze);
    }

    public static NpcWallet empty() { return new NpcWallet(0); }

    // ── Queries ───────────────────────────────────────────────────────
    public long toBronze()               { return bronze; }
    public CurrencyValue toValue()       { return CurrencyValue.of(bronze); }
    public boolean canAfford(CurrencyValue v) { return bronze >= v.toBronze(); }
    public boolean canAfford(long b)         { return bronze >= b; }

    // ── Mutations ─────────────────────────────────────────────────────
    /** Returns true if deducted, false if insufficient. */
    public boolean spend(CurrencyValue amount) {
        long cost = amount.toBronze();
        if (bronze < cost) return false;
        bronze -= cost;
        return true;
    }

    public boolean spend(long bronzeAmount) {
        if (bronze < bronzeAmount) return false;
        bronze -= bronzeAmount;
        return true;
    }

    public void receive(CurrencyValue amount) {
        bronze += amount.toBronze();
    }

    public void receive(long bronzeAmount) {
        bronze += Math.max(0, bronzeAmount);
    }

    /**
     * Transfer from this wallet to another.
     * Returns the amount actually transferred (may be less if insufficient).
     */
    public long payTo(NpcWallet receiver, long bronzeAmount) {
        long actual = Math.min(bronzeAmount, bronze);
        bronze   -= actual;
        receiver.bronze += actual;
        return actual;
    }

    // ── Persistence ───────────────────────────────────────────────────
    public void save(CompoundTag tag, String key) {
        tag.putLong(key, bronze);
    }

    public static NpcWallet load(CompoundTag tag, String key) {
        return new NpcWallet(tag.getLong(key).orElse(0L));
    }

    /**
     * One-time migration: drains physical coin items from an inventory
     * into this wallet. Safe to call every load — does nothing if already
     * migrated (inventory contains no coins).
     */
    public void migrateFromInventory(net.minecraft.world.SimpleContainer inv) {
        for (int i = 0; i < inv.getContainerSize(); i++) {
            net.minecraft.world.item.ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            int value = CurrencyValue.valuePerCoin(stack.getItem());
            if (value <= 0) continue;
            bronze += (long) value * stack.getCount();
            inv.setItem(i, net.minecraft.world.item.ItemStack.EMPTY);
        }
    }

    @Override
    public String toString() {
        long g = bronze / CurrencyValue.GOLD_VALUE;
        long s = (bronze % CurrencyValue.GOLD_VALUE) / CurrencyValue.SILVER_VALUE;
        long b = bronze % CurrencyValue.SILVER_VALUE;
        return (g > 0 ? g + "g " : "") + (s > 0 ? s + "s " : "") + b + "b";
    }
}