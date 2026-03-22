// src/main/java/tterrag1112/life_in_the_village/Village/Needs/VillageNeedsCalculator.java
package tterrag1112.life_in_the_village.Village.Needs;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Buildings.FarmPlot;
import tterrag1112.life_in_the_village.Village.UpgradeRequirements;
import tterrag1112.life_in_the_village.Village.Village;
import tterrag1112.life_in_the_village.World.SeasonTracker;

import java.util.*;

public class VillageNeedsCalculator {

    // How much nutrition one villager needs per day (base, before season scaling)
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

    // =========================================================================
    // Entry point
    // =========================================================================

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

    // =========================================================================
    // Food need  — season multiplier applied to the required nutrition figure
    // =========================================================================

    private static VillageNeed computeFoodNeed(
            ServerLevel level, Village village,
            VillageSavedData data, Building stockpile) {

        int villagerCount = countTownspeople(level, village, data);

        // ── Season scaling ────────────────────────────────────────────────────
        // In winter, consumption pressure is higher (1.4×) because nothing grows.
        // In summer, lower pressure (0.9×) because fields are producing.
        // The multiplier inflates the *required* nutrition figure, not the
        // actual stock — so the same stockpile looks more or less comfortable
        // depending on the time of year.
        float seasonMultiplier = SeasonTracker.currentSeason(level)
                .getFoodNeedMultiplier();

        int baseRequired    = villagerCount * NUTRITION_PER_VILLAGER_PER_DAY;
        int requiredNutrition = Math.round(baseRequired * seasonMultiplier);

        // Scan stockpile for food
        Map<Item, Integer> foodItems   = new HashMap<>();
        int                totalNutrition = 0;

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

        // Build deficit breakdown
        Map<Item, Integer> breakdown = new HashMap<>();
        if (totalNutrition < requiredNutrition) {
            int deficit = requiredNutrition - totalNutrition;
            breakdown.put(Items.WHEAT, deficit);
        }

        NeedLevel needLevel = NeedLevel.fromRatio(
                requiredNutrition == 0
                        ? 2.0
                        : (double) totalNutrition / requiredNutrition
        );

        return new VillageNeed(NeedCategory.FOOD, needLevel,
                totalNutrition, requiredNutrition, breakdown);
    }

    // =========================================================================
    // Building materials need  — unchanged from original
    // =========================================================================

    private static VillageNeed computeMaterialsNeed(
            ServerLevel level, Village village,
            VillageSavedData data, Building stockpile) {

        Map<Item, Integer> required = new HashMap<>();
        for (UUID id : village.getBuildingIds()) {
            data.getBuildingById(id).ifPresent(building -> {
                UpgradeRequirements.compute(level, building).ifPresent(reqs ->
                        reqs.getRequiredItems().forEach((item, count) ->
                                required.merge(item, count, Integer::sum))
                );
            });
        }

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

        int totalRequired  = required.values().stream().mapToInt(i -> i).sum();
        int totalAvailable = required.entrySet().stream()
                .mapToInt(e -> Math.min(
                        e.getValue(),
                        available.getOrDefault(e.getKey(), 0)))
                .sum();

        Map<Item, Integer> deficit = new HashMap<>();
        required.forEach((item, count) -> {
            int have = available.getOrDefault(item, 0);
            if (have < count) deficit.put(item, count - have);
        });

        NeedLevel needLevel = NeedLevel.fromRatio(
                totalRequired == 0
                        ? 2.0
                        : (double) totalAvailable / totalRequired
        );

        return new VillageNeed(NeedCategory.BUILDING_MATERIALS, needLevel,
                totalAvailable, totalRequired, deficit);
    }

    // =========================================================================
    // Seeds need  — unchanged from original
    // =========================================================================

    private static VillageNeed computeSeedsNeed(
            ServerLevel level, Village village,
            VillageSavedData data, Building stockpile) {

        // Count farmland blocks across all plots
        int totalFarmland = data.getFarmPlotsInVillage(village, data)
                .stream()
                .mapToInt(plot -> plot.getFarmlandBlocks(level).size())
                .sum();

        int totalRequired  = totalFarmland * SEED_BUFFER_MULTIPLIER;
        int totalAvailable = 0;
        Map<Item, Integer> deficit = new HashMap<>();

        if (stockpile != null) {
            for (var container : BuildingStorageAccess.findInventories(level, stockpile)) {
                for (int i = 0; i < container.getContainerSize(); i++) {
                    ItemStack stack = container.getItem(i);
                    if (stack.isEmpty()) continue;
                    if (SEED_ITEMS.contains(stack.getItem())) {
                        totalAvailable += stack.getCount();
                    }
                }
            }
        }

        if (totalAvailable < totalRequired) {
            deficit.put(Items.WHEAT_SEEDS, totalRequired - totalAvailable);
        }

        NeedLevel needLevel = NeedLevel.fromRatio(
                totalRequired == 0
                        ? 2.0
                        : (double) totalAvailable / totalRequired
        );

        return new VillageNeed(NeedCategory.SEEDS, needLevel,
                totalAvailable, totalRequired, deficit);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static Building findStockpile(
            ServerLevel level, Village village, VillageSavedData data) {
        return village.getBuildingIds().stream()
                .map(data::getBuildingById)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .filter(b -> b.getType() == BuildingType.STOCKPILE)
                .findFirst()
                .orElse(null);
    }

    private static int countTownspeople(
            ServerLevel level, Village village, VillageSavedData data) {
        return (int) level.getEntitiesOfClass(
                TownspersonMob.class,
                village.getBounds(data)
                        .map(bounds -> new net.minecraft.world.phys.AABB(
                                bounds.minX - 32, bounds.minY - 32, bounds.minZ - 32,
                                bounds.maxX + 32, bounds.maxY + 32, bounds.maxZ + 32))
                        .orElse(new net.minecraft.world.phys.AABB(0, 0, 0, 0, 0, 0)),
                mob -> mob.getAssignedVillageName()
                        .map(name -> name.equals(village.getName()))
                        .orElse(false)
        ).size();
    }
    private static Item getSeedItemForCropType(FarmPlot.CropType cropType) {
        return switch (cropType) {
            case WHEAT, GRAIN   -> Items.WHEAT_SEEDS;
            case CARROTS        -> Items.CARROT;
            case POTATOES       -> Items.POTATO;
            case BEETROOT       -> Items.BEETROOT_SEEDS;
            case MIXED          -> Items.WHEAT_SEEDS;
            case VEGETABLE      -> Items.CARROT;
            case ORCHARD        -> Items.WHEAT_SEEDS;
            case PASTURE        -> null; // PASTURE consumes no seeds — skip in caller
        };
    }
}