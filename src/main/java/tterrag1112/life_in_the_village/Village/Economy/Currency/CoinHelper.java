package tterrag1112.life_in_the_village.Village.Economy.Currency;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import tterrag1112.life_in_the_village.Items.ModItems;
import tterrag1112.life_in_the_village.Items.PurseItem;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;

import java.util.ArrayList;
import java.util.List;

public class CoinHelper {

    // Denominations in descending order for greedy breakdown
    private static final List<Item> DENOMINATIONS = List.of(
            ModItems.DENIER_OR.get(),
            ModItems.DENIER_ARGENT.get(),
            ModItems.DENIER.get()
    );

    // --- Wealth calculation ---

    public static CurrencyValue getWealth(SimpleContainer inventory) {
        long total = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) continue;
            total += getStackValue(stack);
        }
        return CurrencyValue.of(total);
    }

    private static long getStackValue(ItemStack stack) {
        // Loose coins
        int perCoin = CurrencyValue.valuePerCoin(stack.getItem());
        if (perCoin > 0) return (long) perCoin * stack.getCount();

        // Coins inside a purse
        if (stack.getItem() instanceof PurseItem) {
            long purseTotal = 0;
            PurseItem.PouchContents contents = PurseItem.getContents(stack);
            for (ItemStack coinStack : contents.getContents()) {
                int cv = CurrencyValue.valuePerCoin(coinStack.getItem());
                if (cv > 0) purseTotal += (long) cv * coinStack.getCount();
            }
            return purseTotal;
        }

        return 0;
    }

    // --- Payment ---

    /**
     * Attempts to pay exactly `amount` from `payer` into `receiver`.
     * Breaks larger denominations if needed.
     * Returns true if payment succeeded, false if insufficient funds.
     */
    public static boolean pay(SimpleContainer payer, SimpleContainer receiver, CurrencyValue amount) {
        if (!getWealth(payer).isAffordable(amount)) return false;

        long remaining = amount.toBronze();
        remaining = payFromLooseCoins(payer, receiver, remaining);
        if (remaining > 0) remaining = payFromPurses(payer, receiver, remaining);
        if (remaining > 0) remaining = breakAndPay(payer, receiver, remaining);

        return remaining == 0;
    }

    /**
     * Same as pay() but receiver is null — coins are simply destroyed (e.g. paying a tax).
     */
    public static boolean spend(SimpleContainer payer, CurrencyValue amount) {
        if (!getWealth(payer).isAffordable(amount)) return false;

        long remaining = amount.toBronze();
        remaining = removeFromLooseCoins(payer, remaining);
        if (remaining > 0) remaining = removeFromPurses(payer, remaining);
        if (remaining > 0) remaining = breakAndRemove(payer, remaining);

        return remaining == 0;
    }

    // --- Private helpers ---

    private static long payFromLooseCoins(SimpleContainer payer, SimpleContainer receiver, long remaining) {
        // Try exact denomination matches first (smallest first for exact change)
        for (Item denom : List.of(
                ModItems.DENIER.get(),
                ModItems.DENIER_ARGENT.get(),
                ModItems.DENIER_OR.get())) {
            int value = CurrencyValue.valuePerCoin(denom);
            if (value > remaining) continue; // would overpay

            for (int i = 0; i < payer.getContainerSize() && remaining >= value; i++) {
                ItemStack stack = payer.getItem(i);
                if (!stack.is(denom)) continue;

                int canTake = (int) Math.min(stack.getCount(), remaining / value);
                if (canTake == 0) continue;

                stack.shrink(canTake);
                if (stack.isEmpty()) payer.setItem(i, ItemStack.EMPTY);

                if (receiver != null) {
                    receiver.addItem(new ItemStack(denom, canTake));
                }
                remaining -= (long) canTake * value;
            }
        }
        return remaining;
    }

    private static long removeFromLooseCoins(SimpleContainer payer, long remaining) {
        return payFromLooseCoins(payer, null, remaining);
    }

    private static long payFromPurses(SimpleContainer payer, SimpleContainer receiver, long remaining) {
        for (int i = 0; i < payer.getContainerSize() && remaining > 0; i++) {
            ItemStack stack = payer.getItem(i);
            if (!(stack.getItem() instanceof PurseItem)) continue;

            final long remainingBefore = remaining;
            remaining = PurseItem.mutateContents(stack, contents -> {
                long rem = remainingBefore;
                List<ItemStack> toRemove = new ArrayList<>();

                for (ItemStack coinStack : contents.getContents()) {
                    int value = CurrencyValue.valuePerCoin(coinStack.getItem());
                    if (value <= 0 || value > rem) continue;

                    int canTake = (int) Math.min(coinStack.getCount(), rem / value);
                    if (canTake == 0) continue;

                    coinStack.shrink(canTake);
                    if (coinStack.isEmpty()) toRemove.add(coinStack);

                    if (receiver != null) {
                        receiver.addItem(new ItemStack(coinStack.getItem(), canTake));
                    }
                    rem -= (long) canTake * value;
                }
                contents.getContents().removeAll(toRemove);
                return rem;
            });
        }
        return remaining;
    }

    private static long removeFromPurses(SimpleContainer payer, long remaining) {
        return payFromPurses(payer, null, remaining);
    }

    /**
     * Break a larger denomination into smaller ones to make exact change,
     * then pay the remainder.
     */
    private static long breakAndPay(SimpleContainer payer, SimpleContainer receiver, long remaining) {
        // Only break gold into silver if we actually need to
        // and can't cover it with existing silver/bronze
        long existingSilverBronzeValue = 0;
        for (int i = 0; i < payer.getContainerSize(); i++) {
            ItemStack stack = payer.getItem(i);
            int v = CurrencyValue.valuePerCoin(stack.getItem());
            if (v > 0 && v < CurrencyValue.GOLD_VALUE) {
                existingSilverBronzeValue += (long) v * stack.getCount();
            }
        }

        // Only break gold if existing smaller coins can't cover the remainder
        if (existingSilverBronzeValue < remaining) {
            remaining = breakDenomination(payer, receiver, remaining,
                    ModItems.DENIER_OR.get(), CurrencyValue.GOLD_VALUE,
                    ModItems.DENIER_ARGENT.get(), CurrencyValue.SILVER_VALUE);
        }

        // After potentially breaking gold, try paying with silver/bronze again
        remaining = payFromLooseCoins(payer, receiver, remaining);
        if (remaining <= 0) return remaining;

        // Only break silver into bronze if still needed
        long existingBronzeValue = 0;
        for (int i = 0; i < payer.getContainerSize(); i++) {
            ItemStack stack = payer.getItem(i);
            if (stack.is(ModItems.DENIER.get())) {
                existingBronzeValue += stack.getCount();
            }
        }

        if (existingBronzeValue < remaining) {
            remaining = breakDenomination(payer, receiver, remaining,
                    ModItems.DENIER_ARGENT.get(), CurrencyValue.SILVER_VALUE,
                    ModItems.DENIER.get(), CurrencyValue.BRONZE_VALUE);
        }

        // Final attempt with loose coins after breaking
        remaining = payFromLooseCoins(payer, receiver, remaining);
        return remaining;
    }

    private static long breakAndRemove(SimpleContainer payer, long remaining) {
        return breakAndPay(payer, null, remaining);
    }

    private static long breakDenomination(
            SimpleContainer payer, SimpleContainer receiver, long remaining,
            Item largeCoin, int largeValue, Item smallCoin, int smallValue) {

        if (remaining <= 0) return remaining;

        for (int i = 0; i < payer.getContainerSize(); i++) {
            ItemStack stack = payer.getItem(i);
            if (!stack.is(largeCoin)) continue;

            // Remove one large coin
            stack.shrink(1);
            if (stack.isEmpty()) payer.setItem(i, ItemStack.EMPTY);

            // Convert to small coins
            int smallCoins = largeValue / smallValue;
            int toPaySmall = (int) Math.min(smallCoins, remaining / smallValue);
            int change = smallCoins - toPaySmall;

            if (receiver != null && toPaySmall > 0) {
                receiver.addItem(new ItemStack(smallCoin, toPaySmall));
            }
            remaining -= (long) toPaySmall * smallValue;

            if (change > 0) {
                payer.addItem(new ItemStack(smallCoin, change));
            }

            // Break exactly one coin — caller handles remaining
            return remaining;
        }

        return remaining;
    }

    // --- Also check purses for breaking ---

    /**
     * Convenience: check if an NPC can afford a price.
     */
    public static boolean canAfford(SimpleContainer inventory, CurrencyValue price) {
        return getWealth(inventory).isAffordable(price);
    }

    /**
     * Give coins to an NPC inventory, preferring to put them in a purse if one exists.
     */
    public static void giveCoins(SimpleContainer inventory, CurrencyValue amount) {
        long remaining = amount.toBronze();

        // Try to put into an existing purse first
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!(stack.getItem() instanceof PurseItem)) continue;

            long rem = remaining;
            remaining = PurseItem.mutateContents(stack, contents -> {
                long r = rem;
                r = depositIntoPurse(contents, ModItems.DENIER_OR.get(),
                        CurrencyValue.GOLD_VALUE, r);
                r = depositIntoPurse(contents, ModItems.DENIER_ARGENT.get(),
                        CurrencyValue.SILVER_VALUE, r);
                r = depositIntoPurse(contents, ModItems.DENIER.get(),
                        CurrencyValue.BRONZE_VALUE, r);
                return r;
            });

            if (remaining == 0) return;
        }

        // Fall back to loose coins in inventory
        if (remaining > 0) {
            long gold = remaining / CurrencyValue.GOLD_VALUE;
            remaining %= CurrencyValue.GOLD_VALUE;
            long silver = remaining / CurrencyValue.SILVER_VALUE;
            remaining %= CurrencyValue.SILVER_VALUE;
            long bronze = remaining;

            if (gold > 0) inventory.addItem(
                    new ItemStack(ModItems.DENIER_OR.get(), (int) gold));
            if (silver > 0) inventory.addItem(
                    new ItemStack(ModItems.DENIER_ARGENT.get(), (int) silver));
            if (bronze > 0) inventory.addItem(
                    new ItemStack(ModItems.DENIER.get(), (int) bronze));
        }
    }
    public static void giveCoins(ServerPlayer player, CurrencyValue amount) {
        long remaining = amount.toBronze();

        // Check player inventory for a purse first
        net.minecraft.world.entity.player.Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!(stack.getItem() instanceof PurseItem)) continue;

            long rem = remaining;
            remaining = PurseItem.mutateContents(stack, contents -> {
                long r = rem;
                r = depositIntoPurse(contents, ModItems.DENIER_OR.get(),
                        CurrencyValue.GOLD_VALUE, r);
                r = depositIntoPurse(contents, ModItems.DENIER_ARGENT.get(),
                        CurrencyValue.SILVER_VALUE, r);
                r = depositIntoPurse(contents, ModItems.DENIER.get(),
                        CurrencyValue.BRONZE_VALUE, r);
                return r;
            });

            if (remaining == 0) return;
        }

        // Fall back to loose coins added directly to inventory
        if (remaining > 0) {
            long gold   = remaining / CurrencyValue.GOLD_VALUE;
            remaining  %= CurrencyValue.GOLD_VALUE;
            long silver = remaining / CurrencyValue.SILVER_VALUE;
            remaining  %= CurrencyValue.SILVER_VALUE;
            long bronze = remaining;

            if (gold   > 0) player.getInventory().add(
                    new ItemStack(ModItems.DENIER_OR.get(),     (int) gold));
            if (silver > 0) player.getInventory().add(
                    new ItemStack(ModItems.DENIER_ARGENT.get(), (int) silver));
            if (bronze > 0) player.getInventory().add(
                    new ItemStack(ModItems.DENIER.get(),        (int) bronze));
        }
    }

    private static long depositIntoPurse(
            PurseItem.PouchContents contents, Item coin, int value, long remaining) {
        if (remaining < value) return remaining;

        long count = remaining / value;
        ItemStack toDeposit = new ItemStack(coin, (int) Math.min(count, 64));
        if (contents.absorb(toDeposit)) {
            remaining -= (long)(Math.min(count, 64)) * value;
        }
        return remaining;
    }

    // In CoinHelper.java
    public static CurrencyValue getTotalWealth(SimpleContainer personalInv,
                                               ServerLevel level,
                                               tterrag1112.life_in_the_village.Village.Building building) {
        long total = getWealth(personalInv).toBronze();

        if (building != null) {
            for (var container : BuildingStorageAccess.findInventories(level, building)) {
                for (int i = 0; i < container.getContainerSize(); i++) {
                    ItemStack stack = container.getItem(i);
                    int value = CurrencyValue.valuePerCoin(stack.getItem());
                    if (value > 0) total += (long) value * stack.getCount();
                }
            }
        }

        return CurrencyValue.of(total);
    }

    public static boolean payWithBuilding(SimpleContainer personalInv,
                                          CurrencyValue amount,
                                          ServerLevel level,
                                          Building building) {
        if (canAfford(personalInv, amount)) {
            return spend(personalInv, amount);
        }
        if (building != null) {
            long needed = amount.toBronze() - getWealth(personalInv).toBronze();
            pullCoinsFromBuilding(personalInv, level, building, needed);
        }
        return spend(personalInv, amount);
    }

    private static void pullCoinsFromBuilding(SimpleContainer personalInv,
                                              ServerLevel level,
                                              tterrag1112.life_in_the_village.Village.Building building,
                                              long bronzeNeeded) {
        long remaining = bronzeNeeded;

        for (var container : BuildingStorageAccess.findInventories(level, building)) {
            for (int i = 0; i < container.getContainerSize() && remaining > 0; i++) {
                ItemStack stack = container.getItem(i);
                int value = CurrencyValue.valuePerCoin(stack.getItem());
                if (value <= 0) continue;

                long canTake = Math.min(stack.getCount(),
                        (remaining + value - 1) / value); // ceiling division
                stack.shrink((int) canTake);
                if (stack.isEmpty()) container.setItem(i, ItemStack.EMPTY);

                personalInv.addItem(new ItemStack(stack.getItem(), (int) canTake));
                remaining -= canTake * value;
            }
            if (remaining <= 0) break;
        }
    }

    // In CoinHelper.java
    public static SimpleContainer snapshotInventory(ServerPlayer player) {
        SimpleContainer c = new SimpleContainer(
                player.getInventory().getContainerSize());
        for (int i = 0; i < player.getInventory().getContainerSize(); i++)
            c.setItem(i, player.getInventory().getItem(i).copy());
        return c;
    }

    public static void syncInventory(ServerPlayer player, SimpleContainer snapshot) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++)
            player.getInventory().setItem(i, snapshot.getItem(i));
        player.inventoryMenu.broadcastChanges();
    }
}
