package tterrag1112.life_in_the_village.Village.Economy.Trade;

import net.minecraft.server.level.ServerLevel;
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
     * Selects goods for a caravan based on origin surplus
     * weighted toward destination needs.
     */
    public static List<ItemStack> selectGoods(
            ServerLevel level,
            UUID originId, UUID destId,
            VillageSavedData data) {

        Village origin = data.getVillageById(originId)
                .orElse(null);
        Village dest   = data.getVillageById(destId)
                .orElse(null);
        if (origin == null || dest == null) {
            return fallbackGoods();
        }

        // Get origin surpluses
        Map<net.minecraft.world.item.Item, Integer> surpluses =
                getVillageSurpluses(level, origin, data);

        // Get destination needs
        Set<net.minecraft.world.item.Item> needs =
                getVillageNeeds(level, dest, data);

        if (surpluses.isEmpty()) return fallbackGoods();

        // Sort surpluses — items the destination needs come first
        List<Map.Entry<net.minecraft.world.item.Item,
                Integer>> sorted = surpluses.entrySet()
                .stream()
                .sorted((a, b) -> {
                    boolean aNeed = needs.contains(a.getKey());
                    boolean bNeed = needs.contains(b.getKey());
                    if (aNeed && !bNeed) return -1;
                    if (!aNeed && bNeed) return 1;
                    return b.getValue() - a.getValue();
                })
                .limit(MAX_GOODS_TYPES)
                .collect(Collectors.toList());

        // Build ItemStack list
        List<ItemStack> goods = new ArrayList<>();
        for (var entry : sorted) {
            int takeAmount = Math.max(1,
                    (int)(entry.getValue() * TAKE_FRACTION));
            takeAmount = Math.min(takeAmount, 64);
            goods.add(new ItemStack(
                    entry.getKey(), takeAmount));
        }

        return goods.isEmpty() ? fallbackGoods() : goods;
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