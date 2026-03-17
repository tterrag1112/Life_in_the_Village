package tterrag1112.life_in_the_village.Village.Needs;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Buildings.FarmPlot;
import tterrag1112.life_in_the_village.Village.UpgradeRequirements;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.*;

public class VillageNeedsCalculator {

    // How much nutrition one villager needs per day
    private static final int NUTRITION_PER_VILLAGER_PER_DAY = 20;

    // Seed buffer multiplier — keep enough seeds for 2 full plantings
    private static final int SEED_BUFFER_MULTIPLIER = 2;

    // Items counted as building materials
    private static final Set<Item> BUILDING_MATERIAL_ITEMS = Set.of(
            Items.OAK_LOG, Items.SPRUCE_LOG, Items.BIRCH_LOG,
            Items.OAK_PLANKS, Items.SPRUCE_PLANKS, Items.BIRCH_PLANKS,
            Items.COBBLESTONE, Items.STONE, Items.STONE_BRICKS,
            Items.OAK_SLAB, Items.SPRUCE_SLAB,
            Items.GLASS, Items.GLASS_PANE,
            Items.IRON_INGOT, Items.GRAVEL, Items.SAND
    );

    private static final Set<Item> SEED_ITEMS = Set.of(
            Items.WHEAT_SEEDS, Items.CARROT, Items.POTATO, Items.BEETROOT_SEEDS
    );

    public static Map<NeedCategory, VillageNeed> compute(
            ServerLevel level, Village village, VillageSavedData data) {

        Map<NeedCategory, VillageNeed> needs = new EnumMap<>(NeedCategory.class);

        Building stockpile = findStockpile(level, village, data);

        needs.put(NeedCategory.FOOD,
                computeFoodNeed(level, village, data, stockpile));
        needs.put(NeedCategory.BUILDING_MATERIALS,
                computeMaterialsNeed(level, village, data, stockpile));
        needs.put(NeedCategory.SEEDS,
                computeSeedsNeed(level, village, data, stockpile));

        return needs;
    }

    // --- Food ---

    private static VillageNeed computeFoodNeed(
            ServerLevel level, Village village,
            VillageSavedData data, Building stockpile) {

        // Count townspeople in this village
        int villagerCount = countTownspeople(level, village, data);
        int requiredNutrition = villagerCount * NUTRITION_PER_VILLAGER_PER_DAY;

        // Scan stockpile for food
        Map<Item, Integer> foodItems = new HashMap<>();
        int totalNutrition = 0;

        if (stockpile != null) {
            for (var container : BuildingStorageAccess.findInventories(level, stockpile)) {
                for (int i = 0; i < container.getContainerSize(); i++) {
                    ItemStack stack = container.getItem(i);
                    if (stack.isEmpty()) continue;
                    int nutrition = FoodValueHelper.getStackNutrition(stack);
                    if (nutrition > 0) {
                        foodItems.merge(stack.getItem(), stack.getCount(), Integer::sum);
                        totalNutrition += nutrition;
                    }
                }
            }
        }

        // Build deficit breakdown — which food items and how many more needed
        Map<Item, Integer> breakdown = new HashMap<>();
        if (totalNutrition < requiredNutrition) {
            int deficit = requiredNutrition - totalNutrition;
            // Express deficit in wheat equivalent (nutrition=1) as a simple measure
            breakdown.put(Items.WHEAT, deficit);
        }

        NeedLevel level2 = NeedLevel.fromRatio(
                requiredNutrition == 0 ? 2.0
                        : (double) totalNutrition / requiredNutrition
        );

        return new VillageNeed(NeedCategory.FOOD, level2,
                totalNutrition, requiredNutrition, breakdown);
    }

    // --- Building Materials ---

    private static VillageNeed computeMaterialsNeed(
            ServerLevel level, Village village,
            VillageSavedData data, Building stockpile) {

        // Required materials = sum of upgrade requirements for all buildings
        // that are not at max level
        Map<Item, Integer> required = new HashMap<>();
        for (UUID id : village.getBuildingIds()) {
            data.getBuildingById(id).ifPresent(building -> {
                UpgradeRequirements.compute(level, building).ifPresent(reqs -> {
                    reqs.getRequiredItems().forEach((item, count) ->
                            required.merge(item, count, Integer::sum)
                    );
                });
            });
        }

        // Scan stockpile for materials
        Map<Item, Integer> available = new HashMap<>();
        if (stockpile != null) {
            for (var container : BuildingStorageAccess.findInventories(level, stockpile)) {
                for (int i = 0; i < container.getContainerSize(); i++) {
                    ItemStack stack = container.getItem(i);
                    if (stack.isEmpty()) continue;
                    if (BUILDING_MATERIAL_ITEMS.contains(stack.getItem())) {
                        available.merge(stack.getItem(), stack.getCount(), Integer::sum);
                    }
                }
            }
        }

        int totalRequired = required.values().stream().mapToInt(i -> i).sum();
        int totalAvailable = required.entrySet().stream()
                .mapToInt(e -> Math.min(
                        e.getValue(),
                        available.getOrDefault(e.getKey(), 0)
                ))
                .sum();

        // Deficit = items still needed
        Map<Item, Integer> deficit = new HashMap<>();
        required.forEach((item, count) -> {
            int have = available.getOrDefault(item, 0);
            if (have < count) deficit.put(item, count - have);
        });

        NeedLevel needLevel = NeedLevel.fromRatio(
                totalRequired == 0 ? 2.0
                        : (double) totalAvailable / totalRequired
        );

        return new VillageNeed(NeedCategory.BUILDING_MATERIALS, needLevel,
                totalAvailable, totalRequired, deficit);
    }

    // --- Seeds ---

    private static VillageNeed computeSeedsNeed(
            ServerLevel level, Village village,
            VillageSavedData data, Building stockpile) {

        // Required = total farmland blocks across all farm plots * buffer
        Map<Item, Integer> required = new HashMap<>();
        for (FarmPlot plot : data.getFarmPlotsInVillage(village, data)) {
            int farmlandCount = plot.getFarmlandBlocks(level).size();
            Item seedItem = getSeedItemForCropType(plot.getCropType());
            required.merge(seedItem, farmlandCount * SEED_BUFFER_MULTIPLIER, Integer::sum);
        }

        // Scan all farm building chests and stockpile for seeds
        Map<Item, Integer> available = new HashMap<>();
        List<Building> farmBuildings = village.getBuildingIds().stream()
                .map(data::getBuildingById)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .filter(b -> b.getType() == Building.BuildingType.FARMHOUSE)
                .collect(java.util.stream.Collectors.toList());

        List<Building> toScan = new ArrayList<>(farmBuildings);
        if (stockpile != null) toScan.add(stockpile);

        for (Building building : toScan) {
            for (var container : BuildingStorageAccess.findInventories(level, building)) {
                for (int i = 0; i < container.getContainerSize(); i++) {
                    ItemStack stack = container.getItem(i);
                    if (stack.isEmpty()) continue;
                    if (SEED_ITEMS.contains(stack.getItem())) {
                        available.merge(stack.getItem(), stack.getCount(), Integer::sum);
                    }
                }
            }
        }

        int totalRequired = required.values().stream().mapToInt(i -> i).sum();
        int totalAvailable = required.entrySet().stream()
                .mapToInt(e -> Math.min(
                        e.getValue(),
                        available.getOrDefault(e.getKey(), 0)
                ))
                .sum();

        Map<Item, Integer> deficit = new HashMap<>();
        required.forEach((item, count) -> {
            int have = available.getOrDefault(item, 0);
            if (have < count) deficit.put(item, count - have);
        });

        NeedLevel needLevel = NeedLevel.fromRatio(
                totalRequired == 0 ? 2.0
                        : (double) totalAvailable / totalRequired
        );

        return new VillageNeed(NeedCategory.SEEDS, needLevel,
                totalAvailable, totalRequired, deficit);
    }

    // --- Helpers ---

    private static Building findStockpile(
            ServerLevel level, Village village, VillageSavedData data) {
        return village.getBuildingIds().stream()
                .map(data::getBuildingById)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .filter(b -> b.getType() == Building.BuildingType.STOCKPILE)
                .findFirst()
                .orElse(null);
    }

    private static int countTownspeople(
            ServerLevel level, Village village, VillageSavedData data) {
        // Count TownspersonMob entities whose assigned village matches
        return (int) level.getEntitiesOfClass(
                TownspersonMob.class,
                village.getBounds(data)
                        .map(bounds -> new net.minecraft.world.phys.AABB(
                                bounds.minX - 32, bounds.minY - 32, bounds.minZ - 32,
                                bounds.maxX + 32, bounds.maxY + 32, bounds.maxZ + 32
                        ))
                        .orElse(new net.minecraft.world.phys.AABB(0, 0, 0, 0, 0, 0)),
                mob -> mob.getAssignedVillageName()
                        .map(name -> name.equals(village.getName()))
                        .orElse(false)
        ).size();
    }

    private static Item getSeedItemForCropType(FarmPlot.CropType cropType) {
        return switch (cropType) {
            case WHEAT    -> Items.WHEAT_SEEDS;
            case CARROTS  -> Items.CARROT;
            case POTATOES -> Items.POTATO;
            case BEETROOT -> Items.BEETROOT_SEEDS;
            case MIXED    -> Items.WHEAT_SEEDS;
        };
    }
}
