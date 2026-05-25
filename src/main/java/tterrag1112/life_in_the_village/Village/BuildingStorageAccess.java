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
     * Phase 6.4.7.1 — batched deposit. Scans inventories ONCE then
     * walks the source container, fitting each non-empty stack into
     * the cached inventory list. Replaces N×{@link #storeItem} calls
     * (each of which re-runs the full block scan) for the common
     * "dump my whole personal inventory" deposit case.
     *
     * <p>Cost: 1 block scan (~860 lookups for a typical 12×6×12
     * building) plus per-stack slot iteration on the cached list.
     * Pre-fix the equivalent operation did N scans × N stacks ≈
     * 12k lookups per deposit cycle.</p>
     *
     * <p>Mutates the source container in place: each stored stack is
     * cleared from its slot. Returns the count of stacks
     * successfully stored. Stacks that don't fit (storage full)
     * stay in the source.</p>
     */
    public static int storeAll(ServerLevel level, Building building,
                               net.minecraft.world.SimpleContainer source) {
        List<Container> cached = findInventories(level, building);
        if (cached.isEmpty()) return 0;
        int stored = 0;
        for (int s = 0; s < source.getContainerSize(); s++) {
            ItemStack stack = source.getItem(s);
            if (stack.isEmpty()) continue;
            if (storeIntoCached(cached, stack)) {
                source.setItem(s, ItemStack.EMPTY);
                stored++;
            }
        }
        return stored;
    }

    /** Internal: stores stack into the pre-fetched inventory list using
     *  the same merge-then-empty-slot semantics as {@link #storeItem}. */
    private static boolean storeIntoCached(List<Container> cached, ItemStack stack) {
        for (Container inv : cached) {
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack existing = inv.getItem(i);
                if (existing.is(stack.getItem())
                        && existing.getCount() < existing.getMaxStackSize()) {
                    int space = existing.getMaxStackSize() - existing.getCount();
                    int add = Math.min(space, stack.getCount());
                    existing.grow(add);
                    stack.shrink(add);
                    if (stack.isEmpty()) return true;
                }
            }
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

    /**
     * Phase 6.3.3.q.3 — store with personal-inventory fallback.
     * Tries {@link #storeItem} into the building's containers first;
     * if no container has space (or no containers exist at all), the
     * stack falls back into the supplied {@code fallbackInventory}.
     * Returns {@code true} when the stack landed in either path.
     *
     * <p>Used by homestead handlers (chicken coop, pen, beehives,
     * vegetable garden, etc.) and the farmer's harvest deposit so
     * the produce lands in farmhouse storage when chests exist,
     * falls back to the NPC's inventory when they don't. The j.2-era
     * "always personal inventory" path becomes "personal inventory
     * only when storage isn't available" — making household chests
     * meaningful without breaking homesteads that don't have one
     * placed yet.</p>
     */
    public static boolean storeWithFallback(ServerLevel level,
                                            Building building,
                                            ItemStack stack,
                                            net.minecraft.world.SimpleContainer fallbackInventory) {
        if (stack == null || stack.isEmpty()) return true;
        if (building != null) {
            ItemStack copy = stack.copy();
            if (storeItem(level, building, copy)) return true;
            // storeItem mutates copy; preserve remaining for fallback.
            if (!copy.isEmpty() && fallbackInventory != null) {
                fallbackInventory.addItem(copy);
                return copy.isEmpty();
            }
        }
        if (fallbackInventory != null) {
            fallbackInventory.addItem(stack);
            return stack.isEmpty();
        }
        return false;
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

