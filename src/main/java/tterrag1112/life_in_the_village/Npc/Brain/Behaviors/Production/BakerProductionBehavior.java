package tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import tterrag1112.life_in_the_village.Items.ModItems;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Profession.WorkplaceAssignmentManager;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Economy.Resources.ProductionRecipe;
import tterrag1112.life_in_the_village.Village.Economy.Resources.ProductionStep;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Phase 6.2.d.1 — migrated from {@code BakerGoal}. */
public class BakerProductionBehavior extends AbstractProductionBehavior {

    private static final ProductionRecipe FLOUR_TO_BREAD =
            ProductionRecipe.of(ModItems.WHEAT_FLOUR.get(), 1, Items.BREAD, 1, 60);
    private static final ProductionRecipe WHEAT_TO_BREAD =
            ProductionRecipe.of(Items.WHEAT, 3, Items.BREAD, 1, 200);
    private static final int MAX_BATCH = 8;

    @Override protected BuildingType requiredBuildingType() { return BuildingType.BAKERY; }

    @Override
    protected Optional<BlockPos> findWorkstation(ServerLevel level, Building building) {
        return findBlock(level, building, b -> b instanceof AbstractFurnaceBlock);
    }

    @Override
    protected Optional<ProductionRecipe> chooseRecipe(ServerLevel level, Building building) {
        // Phase 6.3.4.6 — stock-aware preference but no exclusion. When
        // flour is in stock, prefer FLOUR_TO_BREAD (1:1, 60t). When only
        // wheat is in stock, use WHEAT_TO_BREAD (3:1, 200t). When NEITHER
        // is in stock, default to FLOUR_TO_BREAD so the analyze→executeBuy
        // path tries to source flour from MILLER. Output-quota gate via
        // productionTarget still applies.
        Optional<Item> target = productionTarget(level, building);
        if (target.isEmpty()) return Optional.empty();
        Building source = resolveInputSource(level, building);
        int flour = source != null
                ? BuildingStorageAccess.countItem(level, source, ModItems.WHEAT_FLOUR.get()) : 0;
        int wheat = source != null
                ? BuildingStorageAccess.countItem(level, source, Items.WHEAT) : 0;
        if (flour >= 1) return Optional.of(FLOUR_TO_BREAD);
        if (wheat >= 3) return Optional.of(WHEAT_TO_BREAD);
        return Optional.of(FLOUR_TO_BREAD);
    }

    @Override
    protected int calculateBatchSize(ServerLevel level, ProductionRecipe recipe) {
        Building source = resolveInputSource(level, workBuilding);
        if (source == null) return 0;
        int inputItem = recipe.inputs().entrySet().iterator().next().getKey()
                == ModItems.WHEAT_FLOUR.get()
                ? BuildingStorageAccess.countItem(level, source, ModItems.WHEAT_FLOUR.get())
                : BuildingStorageAccess.countItem(level, source, Items.WHEAT);
        int inputCount = recipe.inputs().values().iterator().next();
        return Math.min(inputItem / inputCount, MAX_BATCH);
    }

    @Override
    protected Map<Item, Integer> fuelPerBatch(ProductionRecipe recipe) {
        return Map.of(Items.COAL, 1);
    }

    @Override
    protected List<ProductionStep> buildSteps(ServerLevel level, Building building,
                                              ProductionRecipe recipe, int batchSize) {
        Optional<BlockPos> ovenPos = findWorkstation(level, building);
        if (ovenPos.isEmpty()) return List.of();
        int scaledTicks = Math.max(20,
                (int)(recipe.ticks() * batchSize * productionSpeedMultiplier()));
        Map<Item, Integer> consumes = new LinkedHashMap<>();
        recipe.inputs().forEach((item, count) -> consumes.put(item, count * batchSize));
        consumes.put(Items.COAL, batchSize);
        Map<Item, Integer> produces = Map.of(Items.BREAD, batchSize);
        return List.of(new ProductionStep(ovenPos.get(), scaledTicks,
                SoundEvents.FURNACE_FIRE_CRACKLE, Items.AIR, consumes, produces));
    }

    @Override
    protected Building resolveInputSource(ServerLevel level, Building workBuilding) {
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

    @Override protected Map<Item, Integer> stockQuotas() { return Map.of(Items.BREAD, 32); }
    @Override protected List<Item> sellableOutputs() { return List.of(Items.BREAD); }
    @Override protected boolean canProduceItem(Item item) { return item == Items.BREAD; }
    @Override protected SoundEvent workSound() { return SoundEvents.FURNACE_FIRE_CRACKLE; }

    @Override
    protected Map<Item, Integer> resourcesToBuy(ServerLevel level, Building building) {
        Map<Item, Integer> toBuy = new LinkedHashMap<>();
        Building source = resolveInputSource(level, building);
        int breadStock = BuildingStorageAccess.countItem(level, building, Items.BREAD);
        int quota = stockQuotas().getOrDefault(Items.BREAD, 32);
        if (breadStock >= quota) return Map.of();
        int batchesNeeded = (quota - breadStock);
        int flourAvail = source != null
                ? BuildingStorageAccess.countItem(level, source, ModItems.WHEAT_FLOUR.get()) : 0;
        if (flourAvail < batchesNeeded) toBuy.put(ModItems.WHEAT_FLOUR.get(), batchesNeeded - flourAvail);
        int coalAvail = source != null
                ? BuildingStorageAccess.countItem(level, source, Items.COAL) : 0;
        if (coalAvail < batchesNeeded) toBuy.put(Items.COAL, batchesNeeded - coalAvail);
        return toBuy;
    }

    @Override
    protected void onProductionComplete(ServerLevel level, ProductionRecipe recipe, int batchSize) {
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
