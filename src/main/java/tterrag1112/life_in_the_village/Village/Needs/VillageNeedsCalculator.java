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
import tterrag1112.life_in_the_village.Village.Simulation.ItemResourceClassifier;
import tterrag1112.life_in_the_village.Village.Simulation.ResourceCategory;
import tterrag1112.life_in_the_village.Village.UpgradeRequirements;
import tterrag1112.life_in_the_village.Village.Village;
import tterrag1112.life_in_the_village.World.SeasonTracker;

import java.util.*;

/**
 * Computes the current resource needs of a village across three categories:
 * {@link NeedCategory#FOOD}, {@link NeedCategory#BUILDING_MATERIALS}, and
 * {@link NeedCategory#SEEDS}.
 *
 * <h3>Food need</h3>
 * Base requirement = {@code villagerCount × NUTRITION_PER_VILLAGER_PER_DAY},
 * scaled by the current season's food multiplier. Winter (×1.4) drives CRITICAL
 * urgency; summer (×0.9) eases pressure. Actual stock is measured in nutrition
 * units via {@link FoodValueHelper}.
 *
 * <h3>Seeds need</h3>
 * Computed per {@link FarmPlot} using its {@link FarmPlot.CropType}:
 * <ul>
 *   <li>PASTURE plots consume no seeds — skipped.</li>
 *   <li>In winter, seed need returns SATISFIED — nothing to plant.</li>
 *   <li>Required count per plot = farmlandBlocks × {@code SEED_BUFFER_MULTIPLIER}
 *       × {@link FarmPlot.CropType#nutritionMultiplier()} — GRAIN plots are
 *       weighted higher than BEETROOT plots because their output is more critical
 *       to food supply.</li>
 *   <li>Deficit is broken down by specific seed item so crafting orders and
 *       the stockpile keeper can request the right seeds.</li>
 * </ul>
 *
 * <h3>Building materials need</h3>
 * Derived from {@link UpgradeRequirements} by diffing current and next-level
 * structure templates for every upgradeable building. Stock is measured from
 * the village stockpile.
 */
public class VillageNeedsCalculator {

    // ── Food constants ─────────────────────────────────────────────────────────
    private static final int NUTRITION_PER_VILLAGER_PER_DAY = 20;

    // ── Seed constants ─────────────────────────────────────────────────────────
    /**
     * How many full replantings worth of seeds to keep in stock.
     * 2 = enough to replant the entire village farm twice.
     */
    private static final int SEED_BUFFER_MULTIPLIER = 2;

    // Building-material item membership is now owned by the canonical
    // ItemResourceClassifier (Phase 1c) — the legacy private set moved
    // there verbatim, so this calculator's output is unchanged.

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
    // Food need — season multiplier applied to required nutrition
    // =========================================================================

    private static VillageNeed computeFoodNeed(ServerLevel level,
                                               Village village,
                                               VillageSavedData data,
                                               Building stockpile) {
        int villagerCount = countTownspeople(level, village, data);

        // Season multiplier inflates the required threshold, not the actual stock.
        // The same stockpile looks comfortable in summer and precarious in winter.
        float seasonMultiplier    = SeasonTracker.currentSeason(level).getFoodNeedMultiplier();
        int   baseRequired        = villagerCount * NUTRITION_PER_VILLAGER_PER_DAY;
        int   requiredNutrition   = Math.round(baseRequired * seasonMultiplier);

        Map<Item, Integer> foodItems = new HashMap<>();
        int totalNutrition = 0;

        if (stockpile != null) {
            for (var container : BuildingStorageAccess.findInventories(level, stockpile)) {
                for (int i = 0; i < container.getContainerSize(); i++) {
                    ItemStack stack = container.getItem(i);
                    if (stack.isEmpty()) continue;
                    float nutrition = FoodValueHelper.getStackNutrition(stack);
                    if (nutrition > 0) {
                        foodItems.merge(stack.getItem(), stack.getCount(), Integer::sum);
                        totalNutrition += nutrition;
                    }
                }
            }
        }

        // Deficit expressed as equivalent wheat units for display purposes
        Map<Item, Integer> breakdown = new HashMap<>();
        if (totalNutrition < requiredNutrition) {
            breakdown.put(Items.WHEAT, requiredNutrition - totalNutrition);
        }

        NeedLevel needLevel = NeedLevel.fromRatio(
                requiredNutrition == 0 ? 2.0
                        : (double) totalNutrition / requiredNutrition);

        return new VillageNeed(NeedCategory.FOOD, needLevel,
                totalNutrition, requiredNutrition, breakdown);
    }

    // =========================================================================
    // Building materials need
    // =========================================================================

    private static VillageNeed computeMaterialsNeed(ServerLevel level,
                                                    Village village,
                                                    VillageSavedData data,
                                                    Building stockpile) {
        Map<Item, Integer> required = new HashMap<>();
        for (UUID id : village.getBuildingIds()) {
            data.getBuildingById(id).ifPresent(building ->
                    UpgradeRequirements.compute(level, building).ifPresent(reqs ->
                            reqs.getRequiredItems().forEach((item, count) ->
                                    required.merge(item, count, Integer::sum))));
        }

        Map<Item, Integer> available = new HashMap<>();
        if (stockpile != null) {
            for (var container : BuildingStorageAccess.findInventories(level, stockpile)) {
                for (int i = 0; i < container.getContainerSize(); i++) {
                    ItemStack stack = container.getItem(i);
                    if (stack.isEmpty()) continue;
                    if (ItemResourceClassifier.classify(stack.getItem())
                            == ResourceCategory.BUILDING_MATERIALS) {
                        available.merge(stack.getItem(), stack.getCount(), Integer::sum);
                    }
                }
            }
        }

        int totalRequired  = required.values().stream().mapToInt(v -> v).sum();
        int totalAvailable = required.entrySet().stream()
                .mapToInt(e -> Math.min(e.getValue(),
                        available.getOrDefault(e.getKey(), 0)))
                .sum();

        Map<Item, Integer> deficit = new HashMap<>();
        required.forEach((item, count) -> {
            int have = available.getOrDefault(item, 0);
            if (have < count) deficit.put(item, count - have);
        });

        NeedLevel needLevel = NeedLevel.fromRatio(
                totalRequired == 0 ? 2.0
                        : (double) totalAvailable / totalRequired);

        return new VillageNeed(NeedCategory.BUILDING_MATERIALS, needLevel,
                totalAvailable, totalRequired, deficit);
    }

    // =========================================================================
    // Seeds need — per-plot, CropType-aware
    // =========================================================================

    private static VillageNeed computeSeedsNeed(ServerLevel level,
                                                Village village,
                                                VillageSavedData data,
                                                Building stockpile) {
        // Nothing to plant in winter — return SATISFIED silently
        if (SeasonTracker.isWinter(level)) {
            return new VillageNeed(NeedCategory.SEEDS, NeedLevel.SATISFIED,
                    0, 0, Collections.emptyMap());
        }

        // ── Required seeds: per-plot calculation ──────────────────────────────
        // Each crop plot contributes a seed requirement weighted by:
        //   • its farmland block count (raw acreage)
        //   • SEED_BUFFER_MULTIPLIER (keep two full replantings in stock)
        //   • CropType.nutritionMultiplier() — GRAIN (1.0×) gets full buffer,
        //     BEETROOT (0.7×) slightly less, POTATOES (1.2×) slightly more.
        //     This naturally prioritises wheat and potato seeds over beetroot
        //     when generating crafting orders from the stockpile keeper.
        Map<Item, Integer> required = new HashMap<>();

        for (FarmPlot plot : data.getFarmPlotsInVillage(village, data)) {
            FarmPlot.CropType cropType = plot.getCropType();

            // PASTURE: no seeds ever
            if (!cropType.isCropPlot()) continue;

            Item seedItem = getSeedItemForCropType(cropType);
            if (seedItem == null) continue;

            int farmlandCount = plot.getFarmlandBlocks(level).size();
            if (farmlandCount == 0) continue;

            int needed = Math.round(
                    farmlandCount
                            * SEED_BUFFER_MULTIPLIER
                            * cropType.nutritionMultiplier());

            required.merge(seedItem, needed, Integer::sum);
        }

        // No farm plots — no seed need
        if (required.isEmpty()) {
            return new VillageNeed(NeedCategory.SEEDS, NeedLevel.SATISFIED,
                    0, 0, Collections.emptyMap());
        }

        // ── Available seeds in stockpile ──────────────────────────────────────
        Map<Item, Integer> available = new HashMap<>();
        if (stockpile != null) {
            for (var container : BuildingStorageAccess.findInventories(level, stockpile)) {
                for (int i = 0; i < container.getContainerSize(); i++) {
                    ItemStack stack = container.getItem(i);
                    if (stack.isEmpty()) continue;
                    // Only count seed types we actually need — avoids inflating
                    // the available figure with irrelevant items
                    if (required.containsKey(stack.getItem())) {
                        available.merge(stack.getItem(), stack.getCount(), Integer::sum);
                    }
                }
            }
        }

        int totalRequired  = required.values().stream().mapToInt(v -> v).sum();
        int totalAvailable = required.entrySet().stream()
                .mapToInt(e -> Math.min(e.getValue(),
                        available.getOrDefault(e.getKey(), 0)))
                .sum();

        // Per-seed deficit so crafting orders and the stockpile keeper
        // can request exactly the right type
        Map<Item, Integer> deficit = new HashMap<>();
        required.forEach((seed, count) -> {
            int have = available.getOrDefault(seed, 0);
            if (have < count) deficit.put(seed, count - have);
        });

        NeedLevel needLevel = NeedLevel.fromRatio(
                totalRequired == 0 ? 2.0
                        : (double) totalAvailable / totalRequired);

        return new VillageNeed(NeedCategory.SEEDS, needLevel,
                totalAvailable, totalRequired, deficit);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static Building findStockpile(ServerLevel level,
                                          Village village,
                                          VillageSavedData data) {
        return village.getBuildingIds().stream()
                .map(data::getBuildingById)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .filter(b -> b.getType() == BuildingType.STOCKPILE)
                .findFirst()
                .orElse(null);
    }

    private static int countTownspeople(ServerLevel level,
                                        Village village,
                                        VillageSavedData data) {
        return (int) level.getEntitiesOfClass(
                TownspersonMob.class,
                village.getBounds(data)
                        .map(b -> new net.minecraft.world.phys.AABB(
                                b.minX - 32, b.minY - 32, b.minZ - 32,
                                b.maxX + 32, b.maxY + 32, b.maxZ + 32))
                        .orElse(new net.minecraft.world.phys.AABB(0, 0, 0, 0, 0, 0)),
                mob -> mob.getAssignedVillageName()
                        .map(name -> name.equals(village.getName()))
                        .orElse(false)
        ).size();
    }

    /**
     * Returns the primary seed item for a crop type.
     *
     * <ul>
     *   <li>WHEAT / GRAIN / MIXED / ORCHARD → wheat seeds (wheat-based buffer)</li>
     *   <li>CARROTS / VEGETABLE → carrot (highest nutrition per block)</li>
     *   <li>POTATOES → potato</li>
     *   <li>BEETROOT → beetroot seeds</li>
     *   <li>PASTURE → {@code null} (caller skips these)</li>
     * </ul>
     *
     * VEGETABLE plots use carrot as the representative seed because carrot has
     * the highest individual nutrition in the vegetable rotation. The FarmerGoal
     * will plant a mix; this method only determines stockpile buffer priority.
     */
    private static Item getSeedItemForCropType(FarmPlot.CropType cropType) {
        return switch (cropType) {
            case WHEAT, GRAIN, MIXED, ORCHARD -> Items.WHEAT_SEEDS;
            case CARROTS, VEGETABLE            -> Items.CARROT;
            case POTATOES                      -> Items.POTATO;
            case BEETROOT                      -> Items.BEETROOT_SEEDS;
            case PASTURE                       -> null;
        };
    }
}