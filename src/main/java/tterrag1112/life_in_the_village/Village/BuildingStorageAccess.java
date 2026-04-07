package tterrag1112.life_in_the_village.Village;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import tterrag1112.life_in_the_village.Blocks.Entity.custom.VillageFoundationBlockEntity;
import tterrag1112.life_in_the_village.Networking.CraftingOrderInteraction;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Profession.WorkplaceAssignmentManager;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CoinHelper;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;

import java.util.*;

public class BuildingStorageAccess {
    public record StoredItemInfo(Item item, int count) {}


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





    public static void storeItemFromPlayer(
            ServerLevel level,
            Building building,
            ItemStack stack,
            ServerPlayer player) {

        storeItem(level, building, stack);

        String itemId    = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        int    count     = stack.getCount();

        // ── Workplace quota hook (unchanged) ──────────────────────────────────
        WorkplaceAssignmentManager.onItemDelivered(player, itemId, count, level);

        // ── Crafting order delivery hook (new) ───────────────────────────────
        // Resolve the village that owns this building and notify the order system.
        VillageSavedData data = VillageSavedData.get(level);
        data.getVillageAt(building.getShape().getOrigin())
                .ifPresent(village ->
                        CraftingOrderInteraction.onItemsDeposited(
                                player, village.getId(), itemId, count, level));
    }

    public static List<StoredItemInfo> listItems(ServerLevel level, Building building) {
        Map<Item, Integer> totals = new LinkedHashMap<>();
        for (Container inv : findInventories(level, building)) {
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack stack = inv.getItem(i);
                if (!stack.isEmpty())
                    totals.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        return totals.entrySet().stream()
                .map(e -> new StoredItemInfo(e.getKey(), e.getValue()))
                .toList();
    }
}

