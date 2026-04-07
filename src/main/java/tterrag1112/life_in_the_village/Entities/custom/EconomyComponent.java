package tterrag1112.life_in_the_village.Entities.custom;

import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CoinHelper;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;
import tterrag1112.life_in_the_village.Village.Economy.Currency.NpcWallet;

import javax.annotation.Nullable;

public class EconomyComponent {

    private static final int  INVENTORY_SIZE           = 27;
    private static final long DEFAULT_MERCHANT_MAX_DEBT = 500L;

    // Goods inventory — coins no longer live here
    private final SimpleContainer personalInventory;

    // Virtual wallet — all personal wealth lives here
    private NpcWallet wallet;

    @Nullable
    private CoinHelper.DebtTracker debtTracker;

    public EconomyComponent() {
        this.personalInventory = new SimpleContainer(INVENTORY_SIZE);
        this.wallet            = NpcWallet.empty();
    }

    // =========================================================================
    // Inventory access (goods only)
    // =========================================================================

    public SimpleContainer getInventory() { return personalInventory; }

    // =========================================================================
    // Wallet access
    // =========================================================================

    public NpcWallet getWallet() { return wallet; }

    // =========================================================================
    // Wealth queries — delegate to wallet
    // =========================================================================

    public CurrencyValue getWealth()               { return wallet.toValue(); }
    public boolean canAfford(CurrencyValue price)  { return wallet.canAfford(price); }
    public boolean canAfford(long bronze)          { return wallet.canAfford(bronze); }

    /**
     * Combined check: personal wallet + building treasury.
     * Used by goals that can draw on both sources for a purchase.
     */
    public boolean canAffordWithBuilding(CurrencyValue price,
                                         long buildingTreasury) {
        return (wallet.toBronze() + buildingTreasury) >= price.toBronze();
    }

    // =========================================================================
    // Payment operations — delegate to wallet
    // =========================================================================

    /** Deduct from personal wallet. Returns true on success. */
    public boolean spend(CurrencyValue amount) { return wallet.spend(amount); }

    /** Add to personal wallet. */
    public void receive(CurrencyValue amount)  { wallet.receive(amount); }

    /**
     * Transfer from this wallet to another.
     * Callers should prefer {@link tterrag1112.life_in_the_village
     * .Village.Economy.Currency.NpcEconomy#npcPay} for transactions
     * that should produce visual feedback.
     */
    public boolean payTo(EconomyComponent receiver, CurrencyValue amount) {
        long transferred = wallet.payTo(receiver.wallet, amount.toBronze());
        return transferred == amount.toBronze();
    }

    // =========================================================================
    // Debt system
    // =========================================================================

    public CoinHelper.DebtTracker getOrCreateDebtTracker(long maxDebt) {
        if (debtTracker == null) debtTracker = CoinHelper.DebtTracker.create(maxDebt);
        return debtTracker;
    }

    public CoinHelper.DebtTracker getOrCreateDebtTracker() {
        return getOrCreateDebtTracker(DEFAULT_MERCHANT_MAX_DEBT);
    }

    @Nullable
    public CoinHelper.DebtTracker getDebtTracker() { return debtTracker; }

    public boolean hasDebt() { return debtTracker != null && debtTracker.hasDebt(); }

    public boolean spendWithDebt(CurrencyValue amount, long currentTick) {
        long wealth = wallet.toBronze();
        long cost   = amount.toBronze();
        if (wealth >= cost) return wallet.spend(amount);
        long shortfall = cost - wealth;
        var tracker = getOrCreateDebtTracker();
        if (!tracker.canBorrow(shortfall)) return false;
        if (wealth > 0) wallet.spend(CurrencyValue.of(wealth));
        tracker.borrow(shortfall, currentTick);
        return true;
    }

    public void autoRepayDebt() {
        if (debtTracker == null || !debtTracker.hasDebt()) return;
        long available   = wallet.toBronze();
        long repayAmount = Math.min(available / 2, debtTracker.getCurrentDebt());
        if (repayAmount <= 0) return;
        if (wallet.spend(repayAmount)) debtTracker.repay(repayAmount);
    }

    public void tickDebtDeadline(long currentTick) {
        if (debtTracker != null) debtTracker.tickDeadline(currentTick);
    }

    // =========================================================================
    // Inventory item checks (goods only)
    // =========================================================================

    public boolean hasItem(net.minecraft.world.item.Item item) {
        for (int i = 0; i < personalInventory.getContainerSize(); i++) {
            if (personalInventory.getItem(i).is(item)) return true;
        }
        return false;
    }

    public int countItem(net.minecraft.world.item.Item item) {
        int count = 0;
        for (int i = 0; i < personalInventory.getContainerSize(); i++) {
            ItemStack stack = personalInventory.getItem(i);
            if (stack.is(item)) count += stack.getCount();
        }
        return count;
    }

    public int removeItem(net.minecraft.world.item.Item item, int count) {
        int removed = 0;
        for (int i = 0; i < personalInventory.getContainerSize() && removed < count; i++) {
            ItemStack stack = personalInventory.getItem(i);
            if (!stack.is(item)) continue;
            int take = Math.min(count - removed, stack.getCount());
            stack.shrink(take);
            removed += take;
            if (stack.isEmpty()) personalInventory.setItem(i, ItemStack.EMPTY);
        }
        return removed;
    }

    // =========================================================================
// Persistence
// =========================================================================

    public void save(ValueOutput output) {
        // Wallet
        output.putLong("walletBronze", wallet.toBronze());

        // Goods inventory — use ContainerHelper, same as the rest of the codebase
        NonNullList<ItemStack> items =
                NonNullList.withSize(personalInventory.getContainerSize(),
                        ItemStack.EMPTY);
        for (int i = 0; i < personalInventory.getContainerSize(); i++) {
            items.set(i, personalInventory.getItem(i));
        }
        ContainerHelper.saveAllItems(output, items);

        // Debt tracker
        if (debtTracker != null) {
            output.putBoolean("hasDebtTracker", true);
            output.putLong("debtCurrentDebt",  debtTracker.getCurrentDebt());
            output.putLong("debtMaxDebt",      debtTracker.getMaxDebt());
            output.putLong("debtRepayDeadline",debtTracker.getRepayDeadlineTick());
            output.putInt("debtForgiveCount",  debtTracker.getForgiveCount());
        } else {
            output.putBoolean("hasDebtTracker", false);
        }
    }

    public void load(ValueInput input) {
        // Wallet
        wallet = new NpcWallet(
                input.read("walletBronze", com.mojang.serialization.Codec.LONG)
                        .orElse(0L));

        // Goods inventory
        NonNullList<ItemStack> items =
                NonNullList.withSize(personalInventory.getContainerSize(),
                        ItemStack.EMPTY);
        ContainerHelper.loadAllItems(input, items);
        for (int i = 0; i < items.size(); i++) {
            personalInventory.setItem(i, items.get(i));
        }

        // Migration: drain any old physical coins that were saved before wallet
        wallet.migrateFromInventory(personalInventory);

        // Debt tracker
        boolean hadDebt = input.read("hasDebtTracker",
                com.mojang.serialization.Codec.BOOL).orElse(false);
        if (hadDebt) {
            debtTracker = new CoinHelper.DebtTracker(
                    input.read("debtCurrentDebt",  com.mojang.serialization.Codec.LONG)
                            .orElse(0L),
                    input.read("debtMaxDebt",      com.mojang.serialization.Codec.LONG)
                            .orElse(DEFAULT_MERCHANT_MAX_DEBT),
                    input.read("debtRepayDeadline",com.mojang.serialization.Codec.LONG)
                            .orElse(0L),
                    input.read("debtForgiveCount", com.mojang.serialization.Codec.INT)
                            .orElse(0));
        } else {
            debtTracker = null;
        }
    }
}