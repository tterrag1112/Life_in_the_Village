package tterrag1112.life_in_the_village.Village.Economy.Trade;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.*;
import java.util.stream.Collectors;

public class CaravanGoodsSelector {

    // Minimum stack size to be considered surplus
    private static final int SURPLUS_THRESHOLD = 32;
    // Maximum number of different goods a caravan carries
    private static final int MAX_GOODS_TYPES   = 5;
    // How much of a surplus to actually take
    private static final float TAKE_FRACTION   = 0.25f;

    /**
     * Selects goods from the origin village's surplus that the destination
     * village actually needs (based on its VillageNeed snapshots).
     *
     * Items the destination needs most (CRITICAL / LOW) are picked first.
     * If the destination has no computed needs yet, falls back to raw surplus.
     */
    public static List<ItemStack> selectGoods(ServerLevel level,
                                              Village origin,
                                              Village destination,
                                              VillageSavedData data) {
        Map<Item, Integer> surpluses = getVillageSurpluses(level, origin, data);
        if (surpluses.isEmpty()) return fallbackGoods();

        // Get destination's need breakdown across all categories
        Map<Item, Integer> destDeficits = buildDestinationDeficits(destination);

        List<ItemStack> goods = new ArrayList<>();
        int slotsRemaining = MAX_GOODS_TYPES; // keep existing constant

        if (!destDeficits.isEmpty()) {
            // Priority pass: items destination specifically needs
            List<Item> neededItems = destDeficits.entrySet().stream()
                    .filter(e -> surpluses.containsKey(e.getKey()))
                    // Sort by severity: highest deficit first
                    .sorted((a, b) -> b.getValue() - a.getValue())
                    .map(Map.Entry::getKey)
                    .collect(java.util.stream.Collectors.toList());

            for (Item item : neededItems) {
                if (slotsRemaining <= 0) break;
                int available = surpluses.get(item);
                int needed    = destDeficits.get(item);
                int qty       = Math.min(available, Math.min(needed, 64));
                if (qty <= 0) continue;
                goods.add(new ItemStack(item, qty));
                slotsRemaining--;
            }
        }

        // Fill remaining slots with any surplus (original behaviour)
        if (slotsRemaining > 0) {
            for (Map.Entry<Item, Integer> e : surpluses.entrySet()) {
                if (slotsRemaining <= 0) break;
                // Skip items already included
                if (goods.stream().anyMatch(s -> s.is(e.getKey()))) continue;
                int qty = Math.min(e.getValue(), 64);
                if (qty <= 0) continue;
                goods.add(new ItemStack(e.getKey(), qty));
                slotsRemaining--;
            }
        }

        return goods.isEmpty() ? fallbackGoods() : goods;
    }

    /**
     * Phase 4a — the home village's deficit shopping list: items it needs
     * (need level below SATISFIED), capped per type, as a procurement
     * caravan's buy order. Public wrapper over {@link
     * #buildDestinationDeficits} (the same per-category itemBreakdown read
     * the export selector uses for its destination-priority pass).
     */
    public static List<ItemStack> buildShoppingList(Village home) {
        Map<Item, Integer> deficits = buildDestinationDeficits(home);
        List<ItemStack> list = new ArrayList<>();
        int slots = MAX_GOODS_TYPES;
        // Highest deficit first, capped to MAX_GOODS_TYPES item types.
        for (var e : deficits.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .collect(Collectors.toList())) {
            if (slots <= 0) break;
            int qty = Math.min(e.getValue(), 64);
            if (qty <= 0) continue;
            list.add(new ItemStack(e.getKey(), qty));
            slots--;
        }
        return list;
    }

    /**
     * Builds a map of item → deficit quantity from the destination village's
     * computed needs. Reads itemBreakdown from each VillageNeed category.
     */
    private static Map<Item, Integer> buildDestinationDeficits(Village destination) {
        Map<Item, Integer> deficits = new java.util.HashMap<>();
        if (destination == null || destination.getNeeds() == null) return deficits;

        for (var need : destination.getNeeds().values()) {
            // Only pull from categories that are below SATISFIED
            if (need.getLevel() == tterrag1112.life_in_the_village.Village.Needs.NeedLevel.SATISFIED
                    || need.getLevel() == tterrag1112.life_in_the_village.Village.Needs.NeedLevel.SURPLUS) {
                continue;
            }
            need.getItemBreakdown().forEach((item, deficit) -> {
                if (deficit > 0) {
                    deficits.merge(item, deficit, Integer::sum);
                }
            });
        }
        return deficits;
    }

    private static Map<net.minecraft.world.item.Item, Integer>
    getVillageSurpluses(ServerLevel level,
                        Village village,
                        VillageSavedData data) {
        Map<net.minecraft.world.item.Item, Integer> totals =
                new HashMap<>();

        village.getBuildingIds().stream()
                .map(data::getBuildingById)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .forEach(building -> {
                    for (var inv : BuildingStorageAccess
                            .findInventories(level, building)) {
                        for (int i = 0; i < inv
                                .getContainerSize(); i++) {
                            ItemStack stack = inv.getItem(i);
                            if (stack.isEmpty()) continue;
                            totals.merge(stack.getItem(),
                                    stack.getCount(),
                                    Integer::sum);
                        }
                    }
                });

        // Only return items above surplus threshold
        return totals.entrySet().stream()
                .filter(e -> e.getValue() >= SURPLUS_THRESHOLD)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue));
    }

    private static Set<net.minecraft.world.item.Item>
    getVillageNeeds(ServerLevel level,
                    Village village,
                    VillageSavedData data) {
        Map<net.minecraft.world.item.Item, Integer> totals =
                new HashMap<>();

        village.getBuildingIds().stream()
                .map(data::getBuildingById)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .forEach(building -> {
                    for (var inv : BuildingStorageAccess
                            .findInventories(level, building)) {
                        for (int i = 0; i < inv
                                .getContainerSize(); i++) {
                            ItemStack stack = inv.getItem(i);
                            if (stack.isEmpty()) continue;
                            totals.merge(stack.getItem(),
                                    stack.getCount(),
                                    Integer::sum);
                        }
                    }
                });

        // Items below threshold are considered needs
        return totals.entrySet().stream()
                .filter(e -> e.getValue() < SURPLUS_THRESHOLD)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    private static List<ItemStack> fallbackGoods() {
        // Default goods if no surplus found
        return List.of(
                new ItemStack(Items.WHEAT, 16),
                new ItemStack(Items.OAK_LOG, 16),
                new ItemStack(Items.COBBLESTONE, 32)
        );
    }

    /**
     * Deposits caravan goods into the destination stockpile.
     * Returns the actual amount transferred based on
     * trade efficiency.
     */
    public static void deliverGoods(ServerLevel level,
                                    List<ItemStack> goods,
                                    UUID destVillageId,
                                    double efficiency,
                                    VillageSavedData data) {
        Village dest = data.getVillageById(destVillageId)
                .orElse(null);
        if (dest == null) return;

        // Find destination stockpile
        Building stockpile = dest.getBuildingIds().stream()
                .map(data::getBuildingById)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .filter(b -> b.getType()
                        == BuildingType.STOCKPILE)
                .findFirst()
                .orElse(null);
        if (stockpile == null) return;

        for (ItemStack stack : goods) {
            // Apply efficiency — lower quality road
            // means less arrives
            int deliveredAmount = (int)(
                    stack.getCount() * efficiency);
            if (deliveredAmount <= 0) continue;

            ItemStack delivery = new ItemStack(
                    stack.getItem(), deliveredAmount);
            BuildingStorageAccess.storeItem(
                    level, stockpile, delivery);
        }

        System.out.println("CaravanGoodsSelector: delivered "
                + goods.size() + " item types to "
                + dest.getName()
                + " at " + (int)(efficiency * 100)
                + "% efficiency");
    }
}