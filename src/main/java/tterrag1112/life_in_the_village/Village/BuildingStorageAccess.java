package tterrag1112.life_in_the_village.Village;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import tterrag1112.life_in_the_village.Blocks.Entity.custom.VillageFoundationBlockEntity;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CoinHelper;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class BuildingStorageAccess {

    /**
     * Gets the inventory of the block entity at the building's origin.
     */
    /**
     * Finds all Container block entities within the building's bounds.
     * Works with any chest, barrel, or custom storage block.
     */
    public static List<Container> findInventories(ServerLevel level, Building building) {
        List<Container> inventories = new ArrayList<>();
        BlockPos min = building.getShape().getMin();
        BlockPos max = building.getShape().getMax();

        for (int x = min.getX(); x <= max.getX(); x++) {
            for (int y = min.getY(); y <= max.getY(); y++) {
                for (int z = min.getZ(); z <= max.getZ(); z++) {
                    BlockEntity be = level.getBlockEntity(new BlockPos(x, y, z));
                    if (be instanceof Container container) {
                        inventories.add(container);
                    }
                }
            }
        }
        return inventories;
    }

    /**
     * Returns true if any storage in the building contains
     * at least `count` of the given item.
     */
    public static boolean hasItem(ServerLevel level, Building building, Item item, int count) {
        int found = 0;
        for (Container inv : findInventories(level, building)) {
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack stack = inv.getItem(i);
                if (stack.is(item)) found += stack.getCount();
            }
        }
        return found >= count;
    }

    /**
     * Removes `count` of the given item from building storage.
     * Searches all containers in the building bounds.
     */
    public static boolean takeItem(ServerLevel level, Building building, Item item, int count) {
        int remaining = count;
        for (Container inv : findInventories(level, building)) {
            for (int i = 0; i < inv.getContainerSize() && remaining > 0; i++) {
                ItemStack stack = inv.getItem(i);
                if (stack.is(item)) {
                    int take = Math.min(remaining, stack.getCount());
                    stack.shrink(take);
                    remaining -= take;
                    if (stack.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
                }
            }
            if (remaining == 0) return true;
        }
        return remaining == 0;
    }

    /**
     * Stores an item into the first available space across all
     * containers in the building bounds.
     */
    public static boolean storeItem(ServerLevel level, Building building, ItemStack stack) {
        for (Container inv : findInventories(level, building)) {
            // Try merging with existing stacks first
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack existing = inv.getItem(i);
                if (existing.is(stack.getItem()) && existing.getCount() < existing.getMaxStackSize()) {
                    int space = existing.getMaxStackSize() - existing.getCount();
                    int add = Math.min(space, stack.getCount());
                    existing.grow(add);
                    stack.shrink(add);
                    if (stack.isEmpty()) return true;
                }
            }
            // Then try empty slots
            for (int i = 0; i < inv.getContainerSize(); i++) {
                if (inv.getItem(i).isEmpty()) {
                    inv.setItem(i, stack.copy());
                    stack.setCount(0);
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Returns the total count of a given item across all
     * containers in the building.
     */
    public static int countItem(ServerLevel level, Building building, Item item) {
        int total = 0;
        for (Container inv : findInventories(level, building)) {
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack stack = inv.getItem(i);
                if (stack.is(item)) total += stack.getCount();
            }
        }
        return total;
    }

    public static boolean canAfford(ServerLevel level,
                                    Building building,
                                    CurrencyValue amount) {
        for (Container inv : findInventories(level, building)) {
            // Copy to SimpleContainer for CoinHelper compatibility
            SimpleContainer copy = new SimpleContainer(
                    inv.getContainerSize());
            for (int i = 0; i < inv.getContainerSize(); i++) {
                copy.setItem(i, inv.getItem(i).copy());
            }
            if (CoinHelper.canAfford(copy, amount)) return true;
        }
        return false;
    }

    public static void deductCurrency(ServerLevel level,
                                      Building building,
                                      CurrencyValue amount) {
        for (Container inv : findInventories(level, building)) {
            SimpleContainer copy = new SimpleContainer(
                    inv.getContainerSize());
            for (int i = 0; i < inv.getContainerSize(); i++) {
                copy.setItem(i, inv.getItem(i).copy());
            }
            if (CoinHelper.canAfford(copy, amount)) {
                CoinHelper.spend(copy, amount);
                // Write changes back to the real inventory
                for (int i = 0; i < inv.getContainerSize(); i++) {
                    inv.setItem(i, copy.getItem(i));
                }
                return;
            }
        }
    }
}

