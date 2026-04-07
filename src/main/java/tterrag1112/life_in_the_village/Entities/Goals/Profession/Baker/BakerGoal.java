package tterrag1112.life_in_the_village.Entities.Goals.Profession.Baker;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.Workshop.AbstractWorkstationProductionGoal;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Items.ModItems;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Profession.WorkplaceAssignmentManager;
import tterrag1112.life_in_the_village.Village.Economy.Resources.ProductionRecipe;
import tterrag1112.life_in_the_village.Village.Economy.Resources.ProductionStep;

import java.util.*;

/**
 * Baker bakes bread in the bakery's oven (furnace/smoker block).
 *
 * <h3>Two-recipe fallback</h3>
 * Flour (from the miller) is preferred — it's faster and requires less wheat
 * per loaf. If no flour is available, the baker falls back to grinding wheat
 * directly, which is less efficient but keeps bread production running.
 *
 * <h3>Fuel</h3>
 * One coal per batch unit, gathered from the stockpile alongside ingredients.
 */
public class BakerGoal extends AbstractWorkstationProductionGoal {

    // Flour path: 1 flour → 1 bread, 60 ticks — fast and efficient
    private static final ProductionRecipe FLOUR_TO_BREAD =
            ProductionRecipe.of(ModItems.WHEAT_FLOUR.get(), 1, Items.BREAD, 1, 60);

    // Wheat fallback: 3 wheat → 1 bread, 200 ticks — slow, same yield as vanilla
    private static final ProductionRecipe WHEAT_TO_BREAD =
            ProductionRecipe.of(Items.WHEAT, 3, Items.BREAD, 1, 200);

    private static final int MAX_BATCH = 8;

    /** True when using the flour recipe; false when using wheat fallback. */
    private boolean usingFlour = false;

    public BakerGoal(TownspersonMob entity) { super(entity); }

    @Override protected BuildingType requiredBuildingType() { return BuildingType.BAKERY; }

    @Override
    protected Optional<BlockPos> findWorkstation(ServerLevel level, Building building) {
        return findBlock(level, building, b -> b instanceof AbstractFurnaceBlock);
    }

    /** Flour is always preferred; wheat is a fallback when no flour is available. */
    @Override
    protected Optional<ProductionRecipe> chooseRecipe(ServerLevel level, Building building) {
        // Only produce if bread is below quota
        Optional<Item> target = productionTarget(level, building);
        if (target.isEmpty()) return Optional.empty();

        Building source = resolveInputSource(level, building);
        if (source == null) return Optional.empty();

        // Prefer flour
        int flour = BuildingStorageAccess.countItem(level, source, ModItems.WHEAT_FLOUR.get());
        if (flour >= 1) {
            usingFlour = true;
            return Optional.of(FLOUR_TO_BREAD);
        }

        // Fallback to wheat
        int wheat = BuildingStorageAccess.countItem(level, source, Items.WHEAT);
        if (wheat >= 3) {
            usingFlour = false;
            return Optional.of(WHEAT_TO_BREAD);
        }

        return Optional.empty();
    }

    @Override
    protected int calculateBatchSize(ServerLevel level, ProductionRecipe recipe) {
        Building source = resolveInputSource(level, workBuilding);
        if (source == null) return 0;
        int inputItem  = recipe.inputs().entrySet().iterator().next().getKey()
                == ModItems.WHEAT_FLOUR.get()
                ? BuildingStorageAccess.countItem(level, source, ModItems.WHEAT_FLOUR.get())
                : BuildingStorageAccess.countItem(level, source, Items.WHEAT);
        int inputCount = recipe.inputs().values().iterator().next();
        return Math.min(inputItem / inputCount, MAX_BATCH);
    }

    /** Oven always needs coal — 1 per batch unit. */
    @Override
    protected Map<Item, Integer> fuelPerBatch(ProductionRecipe recipe) {
        return Map.of(Items.COAL, 1);
    }

    /**
     * Custom buildSteps so fuel is consumed at the oven step rather than
     * being pre-gathered but only notionally consumed by the default path.
     */
    @Override
    protected List<ProductionStep> buildSteps(ServerLevel level, Building building,
                                              ProductionRecipe recipe, int batchSize) {
        Optional<BlockPos> ovenPos = findWorkstation(level, building);
        if (ovenPos.isEmpty()) return List.of();

        int scaledTicks = Math.max(20,
                (int)(recipe.ticks() * batchSize * productionSpeedMultiplier()));

        Map<Item, Integer> consumes = new LinkedHashMap<>();
        recipe.inputs().forEach((item, count) -> consumes.put(item, count * batchSize));
        consumes.put(Items.COAL, batchSize); // fuel consumed at the oven

        Map<Item, Integer> produces = Map.of(Items.BREAD, batchSize);

        return List.of(new ProductionStep(
                ovenPos.get(), scaledTicks,
                SoundEvents.FURNACE_FIRE_CRACKLE, Items.AIR,
                consumes, produces));
    }

    @Override
    protected Building resolveInputSource(ServerLevel level, Building workBuilding) {
        // Prefer stockpile (flour lives there after miller deposits it)
        Building stockpile = findStockpile(level);
        if (stockpile != null) {
            boolean hasFlour = BuildingStorageAccess.countItem(
                    level, stockpile, ModItems.WHEAT_FLOUR.get()) > 0;
            boolean hasWheat = BuildingStorageAccess.countItem(
                    level, stockpile, Items.WHEAT) >= 3;
            if (hasFlour || hasWheat) return stockpile;
        }
        return workBuilding;
    }

    @Override
    protected Map<Item, Integer> stockQuotas() {
        return Map.of(Items.BREAD, 32);
    }

    @Override
    protected List<Item> sellableOutputs() { return List.of(Items.BREAD); }

    @Override
    protected boolean canProduceItem(Item item) { return item == Items.BREAD; }

    @Override
    protected SoundEvent workSound() { return SoundEvents.FURNACE_FIRE_CRACKLE; }

    /** Buy flour first, coal second if short. */
    @Override
    protected Map<Item, Integer> resourcesToBuy(ServerLevel level, Building building) {
        Map<Item, Integer> toBuy = new LinkedHashMap<>();
        Building source = resolveInputSource(level, building);

        int breadStock = BuildingStorageAccess.countItem(level, building, Items.BREAD);
        int quota = stockQuotas().getOrDefault(Items.BREAD, 32);
        if (breadStock >= quota) return Map.of();

        int batchesNeeded = (quota - breadStock);

        // Prefer flour
        int flourAvail = source != null
                ? BuildingStorageAccess.countItem(level, source, ModItems.WHEAT_FLOUR.get()) : 0;
        if (flourAvail < batchesNeeded) {
            toBuy.put(ModItems.WHEAT_FLOUR.get(), batchesNeeded - flourAvail);
        }

        // Coal for the oven
        int coalAvail = source != null
                ? BuildingStorageAccess.countItem(level, source, Items.COAL) : 0;
        if (coalAvail < batchesNeeded) {
            toBuy.put(Items.COAL, batchesNeeded - coalAvail);
        }

        return toBuy;
    }

    @Override
    protected void onProductionComplete(ServerLevel level,
                                        ProductionRecipe recipe, int batchSize) {
        if (workBuilding == null) return;
        WorkplaceAssignmentManager.onWorkplaceProduction(
                level, workBuilding.getId(),
                net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .getKey(recipe.output()).toString(),
                batchSize * recipe.outputCount());
    }

    private Building findStockpile(ServerLevel level) {
        VillageSavedData data = VillageSavedData.get(level);
        return entity.getAssignedVillageName()
                .flatMap(data::getVillageByName)
                .flatMap(village -> village.getBuildingIds().stream()
                        .map(data::getBuildingById)
                        .filter(Optional::isPresent).map(Optional::get)
                        .filter(b -> b.getType() == BuildingType.STOCKPILE)
                        .findFirst())
                .orElse(null);
    }
}