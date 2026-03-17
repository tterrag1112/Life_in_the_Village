package tterrag1112.life_in_the_village.Village.Economy.Currency;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Needs.NeedCategory;
import tterrag1112.life_in_the_village.Village.Needs.NeedLevel;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.Optional;

public class DynamicPriceCalculator {

    // Price multiplier bounds
    private static final double MIN_MULTIPLIER = 0.5;
    private static final double MAX_MULTIPLIER = 3.0;

    /**
     * Returns the dynamic sell price (what player pays) for an item
     * in bronze units.
     */
    public static long getSellPrice(ServerLevel level, Village village,
                                    VillageSavedData data, Item item,
                                    long basePrice) {
        double multiplier = computeMultiplier(level, village, data, item);
        return Math.max(1, Math.round(basePrice * multiplier));
    }

    /**
     * Returns the dynamic buy price (what player receives) for an item
     * in bronze units.
     */
    public static long getBuyPrice(ServerLevel level, Village village,
                                   VillageSavedData data, Item item,
                                   long basePrice) {
        // Buy price is always lower than sell — merchant makes margin
        double multiplier = computeMultiplier(level, village, data, item);
        return Math.max(1, Math.round(basePrice * multiplier * 0.6));
    }

    private static double computeMultiplier(ServerLevel level, Village village,
                                            VillageSavedData data, Item item) {
        double multiplier = 1.0;

        // --- Supply factor ---
        // Count item in stockpile — more supply = lower price
        int stockpileCount = countInStockpile(level, village, data, item);
        if (stockpileCount == 0) {
            multiplier *= 2.0; // scarce — double price
        } else if (stockpileCount < 16) {
            multiplier *= 1.5; // low stock
        } else if (stockpileCount > 128) {
            multiplier *= 0.7; // oversupplied
        } else if (stockpileCount > 64) {
            multiplier *= 0.85; // well stocked
        }

        // --- Demand factor from village needs ---
        NeedLevel foodNeed = village.getNeedLevel(NeedCategory.FOOD);
        NeedLevel materialNeed =
                village.getNeedLevel(NeedCategory.BUILDING_MATERIALS);

        boolean isFood = isFoodItem(item);
        boolean isMaterial = isMaterialItem(item);

        if (isFood) {
            multiplier *= switch (foodNeed) {
                case CRITICAL -> 2.5;
                case LOW      -> 1.5;
                case SATISFIED -> 1.0;
                case SURPLUS  -> 0.8;
            };
        }

        if (isMaterial) {
            multiplier *= switch (materialNeed) {
                case CRITICAL -> 2.0;
                case LOW      -> 1.4;
                case SATISFIED -> 1.0;
                case SURPLUS  -> 0.9;
            };
        }

        return Math.max(MIN_MULTIPLIER, Math.min(MAX_MULTIPLIER, multiplier));
    }

    private static int countInStockpile(ServerLevel level, Village village,
                                        VillageSavedData data, Item item) {
        return village.getBuildingIds().stream()
                .map(data::getBuildingById)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .filter(b -> b.getType() == Building.BuildingType.STOCKPILE)
                .mapToInt(stockpile ->
                        BuildingStorageAccess.countItem(level, stockpile, item))
                .sum();
    }

    private static boolean isFoodItem(Item item) {
        return item.components()
                .has(net.minecraft.core.component.DataComponents.FOOD);
    }

    private static boolean isMaterialItem(Item item) {
        var key = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item);
        if (key == null) return false;
        String path = key.getPath();
        return path.contains("log") || path.contains("planks")
                || path.contains("stone") || path.contains("cobblestone")
                || path.contains("ingot") || path.contains("glass");
    }
}
