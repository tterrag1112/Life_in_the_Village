package tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Profession.WorkplaceAssignmentManager;
import tterrag1112.life_in_the_village.Village.Economy.Resources.ProductionRecipe;

import java.util.*;

/**
 * Candlemaker crafts candles and torches from honeycomb and string.
 * No specific workstation block required — works at the building.
 *
 * <h3>Multi-ingredient recipes</h3>
 * Candles require both honeycomb and string, making this the first goal to
 * exercise the multi-input path in AbstractWorkstationProductionGoal.
 */
public class CandlemakerProductionBehavior extends AbstractProductionBehavior {

    // 1 honeycomb + 1 string → 1 candle (vanilla recipe, 60 ticks)
    private static final ProductionRecipe MAKE_CANDLE = ProductionRecipe.of(
            Map.of(Items.HONEYCOMB, 1, Items.STRING, 1),
            Items.CANDLE, 1, 60);

    // 1 stick + 1 coal → 4 torches (vanilla 1 stick + 1 coal = 4 torches, 40 ticks)
    private static final ProductionRecipe MAKE_TORCH = ProductionRecipe.of(
            Map.of(Items.STICK, 1, Items.COAL, 1),
            Items.TORCH, 4, 40);

    private static final int MAX_BATCH = 8;

    

    @Override protected BuildingType requiredBuildingType() { return BuildingType.CANDLEMAKER; }

    // No specific workstation — works at building origin (findWorkstation returns empty)

    @Override
    protected Optional<ProductionRecipe> chooseRecipe(ServerLevel level, Building building) {
        Optional<Item> target = productionTarget(level, building);
        if (target.isEmpty()) return Optional.empty();

        Building source = resolveInputSource(level, building);
        if (source == null) return Optional.empty();

        Item t = target.get();

        if (t == Items.CANDLE && hasAllInputs(level, source, MAKE_CANDLE)) {
            return Optional.of(MAKE_CANDLE);
        }
        if (t == Items.TORCH && hasAllInputs(level, source, MAKE_TORCH)) {
            return Optional.of(MAKE_TORCH);
        }
        return Optional.empty();
    }

    @Override
    protected int calculateBatchSize(ServerLevel level, ProductionRecipe recipe) {
        Building source = resolveInputSource(level, workBuilding);
        if (source == null) return 0;
        int batches = recipe.inputs().entrySet().stream()
                .mapToInt(e -> BuildingStorageAccess.countItem(level, source, e.getKey())
                        / e.getValue())
                .min().orElse(0);
        return Math.min(batches, MAX_BATCH);
    }

    @Override
    protected Map<Item, Integer> stockQuotas() {
        return Map.of(
                Items.CANDLE, 16,
                Items.TORCH,  32);
    }

    @Override
    protected List<Item> sellableOutputs() { return List.of(Items.CANDLE, Items.TORCH); }

    @Override
    protected boolean canProduceItem(Item item) {
        return item == Items.CANDLE || item == Items.TORCH;
    }

    @Override
    protected SoundEvent workSound() { return SoundEvents.BEEHIVE_DRIP; }

    @Override
    protected Map<Item, Integer> resourcesToBuy(ServerLevel level, Building building) {
        Map<Item, Integer> toBuy = new LinkedHashMap<>();
        Building source = resolveInputSource(level, building);

        // Buy honeycomb and string when short (primary candle inputs)
        for (Item item : List.of(Items.HONEYCOMB, Items.STRING, Items.STICK, Items.COAL)) {
            int avail = source != null
                    ? BuildingStorageAccess.countItem(level, source, item) : 0;
            if (avail < 8) toBuy.put(item, 16 - avail);
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

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean hasAllInputs(ServerLevel level, Building source,
                                 ProductionRecipe recipe) {
        return recipe.inputs().entrySet().stream().allMatch(e ->
                BuildingStorageAccess.countItem(level, source, e.getKey())
                        >= e.getValue());
    }
}