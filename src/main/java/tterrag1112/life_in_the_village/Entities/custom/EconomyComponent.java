// src/main/java/tterrag1112/life_in_the_village/Entities/custom/EconomyComponent.java
package tterrag1112.life_in_the_village.Entities.custom;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.NonNullList;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CoinHelper;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;

import javax.annotation.Nullable;

/**
 * Manages all economy-related state for a TownspersonMob:
 * personal inventory, coin wealth, payment operations, and debt.
 *
 * <h3>Why extract this?</h3>
 * TownspersonMob held a SimpleContainer, several wealth-query methods,
 * coin manipulation helpers, and a ranged-attack method that was mixed
 * in with economy code. This component isolates the economic concerns.
 *
 * <h3>Usage</h3>
 * {@code TownspersonMob} holds an {@code EconomyComponent economy} field.
 * Trade handlers, merchant goals, and sell-to-market goals call methods
 * on the component.
 *
 * <h3>Building wealth</h3>
 * Some NPCs can also access their assigned building's storage. The
 * building lookup is done by the caller (TownspersonMob) since it
 * knows the building assignment.
 *
 * <h3>Debt</h3>
 * Merchants (and potentially other NPCs) can borrow coins to purchase
 * expensive goods they intend to resell at a profit. The debt tracker
 * is lazily created — only NPCs that actually take on debt will have
 * one. The tracker is persisted alongside the inventory.
 */
public class EconomyComponent {

    private static final int INVENTORY_SIZE = 27;

    /** Default max debt for a new merchant (in bronze units). */
    private static final long DEFAULT_MERCHANT_MAX_DEBT = 500L;

    private final SimpleContainer personalInventory;

    /**
     * Debt tracker — null for most NPCs, lazily created when a merchant
     * first borrows. Persisted in save/load.
     */
    @Nullable
    private CoinHelper.DebtTracker debtTracker;


    public EconomyComponent() {
        this.personalInventory = new SimpleContainer(INVENTORY_SIZE);
    }

    // =========================================================================
    // Inventory access
    // =========================================================================

    /** Direct access for goals that need to iterate slots. */
    public SimpleContainer getInventory() {
        return personalInventory;
    }

    // =========================================================================
    // Wealth queries
    // =========================================================================

    /** Coin wealth in personal inventory only. */
    public CurrencyValue getWealth() {
        return CoinHelper.getWealth(personalInventory);
    }

    /** Can the NPC afford this from personal inventory alone? */
    public boolean canAfford(CurrencyValue price) {
        return CoinHelper.canAfford(personalInventory, price);
    }

    /**
     * Combines personal wealth with building storage wealth.
     *
     * @param buildingWealth the coin value found in the NPC's assigned
     *                       building (computed by the caller via
     *                       BuildingStorageAccess)
     * @return total combined wealth
     */
    public CurrencyValue getTotalWealthWithBuilding(CurrencyValue buildingWealth) {
        long personal = getWealth().toBronze();
        long building = buildingWealth.toBronze();
        return CurrencyValue.of(personal + building);
    }

    /**
     * Can the NPC afford this when combining personal + building wealth?
     */
    public boolean canAffordWithBuilding(CurrencyValue price,
                                         CurrencyValue buildingWealth) {
        return getTotalWealthWithBuilding(buildingWealth)
                .isAffordable(price);
    }

    // =========================================================================
    // Payment operations
    // =========================================================================

    /** Deduct coins from personal inventory. Returns true on success. */
    public boolean spend(CurrencyValue amount) {
        return CoinHelper.spend(personalInventory, amount);
    }

    /** Add coins to personal inventory. */
    public void receive(CurrencyValue amount) {
        CoinHelper.giveCoins(personalInventory, amount);
    }

    /**
     * Transfer coins from this NPC to another.
     * Returns true if the transfer succeeded.
     */
    public boolean payTo(EconomyComponent receiver, CurrencyValue amount) {
        return CoinHelper.pay(personalInventory,
                receiver.personalInventory, amount);
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

    @javax.annotation.Nullable
    public CoinHelper.DebtTracker getDebtTracker() { return debtTracker; }

    public boolean hasDebt() { return debtTracker != null && debtTracker.hasDebt(); }

    public boolean spendWithDebt(CurrencyValue amount, long currentTick) {
        return CoinHelper.spendWithDebt(personalInventory, amount,
                getOrCreateDebtTracker(), currentTick);
    }

    public void autoRepayDebt() {
        if (debtTracker == null || !debtTracker.hasDebt()) return;
        CoinHelper.autoRepayDebt(personalInventory, debtTracker);
    }

    public void tickDebtDeadline(long currentTick) {
        if (debtTracker != null) debtTracker.tickDeadline(currentTick);
    }

    // =========================================================================
    // Inventory item checks (used by goals)
    // =========================================================================

    /** Check if the NPC has at least one of the given item. */
    public boolean hasItem(net.minecraft.world.item.Item item) {
        for (int i = 0; i < personalInventory.getContainerSize(); i++) {
            if (personalInventory.getItem(i).is(item)) return true;
        }
        return false;
    }

    /** Count how many of the given item the NPC has. */
    public int countItem(net.minecraft.world.item.Item item) {
        int count = 0;
        for (int i = 0; i < personalInventory.getContainerSize(); i++) {
            ItemStack stack = personalInventory.getItem(i);
            if (stack.is(item)) count += stack.getCount();
        }
        return count;
    }

    /**
     * Remove up to {@code count} of the given item.
     * Returns the number actually removed.
     */
    public int consumeItem(net.minecraft.world.item.Item item, int count) {
        int remaining = count;
        for (int i = 0; i < personalInventory.getContainerSize()
                && remaining > 0; i++) {
            ItemStack stack = personalInventory.getItem(i);
            if (!stack.is(item)) continue;
            int take = Math.min(remaining, stack.getCount());
            stack.shrink(take);
            remaining -= take;
            if (stack.isEmpty()) {
                personalInventory.setItem(i, ItemStack.EMPTY);
            }
        }
        return count - remaining;
    }

    // =========================================================================
    // Persistence
    // =========================================================================

    /**
     * Saves inventory and debt state.
     *
     * <p>Layout in NBT:</p>
     * <pre>
     *   Items    → standard ContainerHelper format (slot tags)
     *   debtCurrentDebt      → long (only if tracker exists)
     *   debtMaxDebt          → long
     *   debtRepayDeadline    → long
     *   debtForgiveCount     → int
     * </pre>
     */
    public void save(ValueOutput output) {
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
            output.putLong("debtCurrentDebt", debtTracker.getCurrentDebt());
            output.putLong("debtMaxDebt", debtTracker.getMaxDebt());
            output.putLong("debtRepayDeadline", debtTracker.getRepayDeadlineTick());
            output.putInt("debtForgiveCount", debtTracker.getForgiveCount());
        } else {
            output.putBoolean("hasDebtTracker", false);
        }
    }

    /**
     * Loads inventory and debt state.
     */
    public void load(ValueInput input) {
        NonNullList<ItemStack> items =
                NonNullList.withSize(personalInventory.getContainerSize(),
                        ItemStack.EMPTY);
        ContainerHelper.loadAllItems(input, items);
        for (int i = 0; i < items.size(); i++) {
            personalInventory.setItem(i, items.get(i));
        }

        // Debt tracker
        boolean hadDebt = input.read("hasDebtTracker", Codec.BOOL).orElse(false);
        if (hadDebt) {
            long cd = input.read("debtCurrentDebt", Codec.LONG).orElse(0L);
            long md = input.read("debtMaxDebt", Codec.LONG).orElse(DEFAULT_MERCHANT_MAX_DEBT);
            long rd = input.read("debtRepayDeadline", Codec.LONG).orElse(0L);
            int  fc = input.read("debtForgiveCount", Codec.INT).orElse(0);
            debtTracker = new CoinHelper.DebtTracker(cd, md, rd, fc);
        } else {
            debtTracker = null;
        }
    }
}