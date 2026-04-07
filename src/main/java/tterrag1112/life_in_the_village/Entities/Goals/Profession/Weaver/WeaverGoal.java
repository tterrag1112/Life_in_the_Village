package tterrag1112.life_in_the_village.Entities.Goals.Profession.Weaver;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.LoomBlock;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.Workshop.AbstractWorkstationProductionGoal;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Profession.WorkplaceAssignmentManager;
import tterrag1112.life_in_the_village.Village.Economy.Resources.ProductionRecipe;

import java.util.*;

/**
 * Weaver processes wool and string into carpets and spun wool at the loom.
 * Inputs come from the stockpile (sheep wool from the farm). Outputs are
 * deposited to the weaver building for sale at market.
 *
 * <h3>Future</h3>
 * The loom workstation and quota system are already wired to accept clothing
 * recipes when that system is ready. Add new {@link WeaverRecipe} entries and
 * new {@link #stockQuotas()} entries without touching the abstract class.
 */
public class WeaverGoal extends AbstractWorkstationProductionGoal {

    private static final int MAX_BATCH = 8;

    // String spinning: 4 string → 1 white wool (useful when sheep are scarce)
    private static final WeaverRecipe SPIN_STRING =
            new WeaverRecipe(Items.STRING, 4, Items.WHITE_WOOL, 1, 60);

    private static final List<WeaverRecipe> RECIPES = buildRecipes();

    // All wool items the weaver accepts as inputs
    private static final List<Item> WOOL_TYPES = List.of(
            Items.WHITE_WOOL, Items.ORANGE_WOOL, Items.MAGENTA_WOOL,
            Items.LIGHT_BLUE_WOOL, Items.YELLOW_WOOL, Items.LIME_WOOL,
            Items.PINK_WOOL, Items.GRAY_WOOL, Items.LIGHT_GRAY_WOOL,
            Items.CYAN_WOOL, Items.PURPLE_WOOL, Items.BLUE_WOOL,
            Items.BROWN_WOOL, Items.GREEN_WOOL, Items.RED_WOOL,
            Items.BLACK_WOOL);

    public WeaverGoal(TownspersonMob entity) { super(entity); }

    @Override protected BuildingType requiredBuildingType() { return BuildingType.WEAVER; }

    @Override
    protected Optional<BlockPos> findWorkstation(ServerLevel level, Building building) {
        return findBlock(level, building, b -> b instanceof LoomBlock);
    }

    @Override
    protected Optional<ProductionRecipe> chooseRecipe(ServerLevel level, Building building) {
        Building source = resolveInputSource(level, building);
        if (source == null) return Optional.empty();

        Optional<Item> target = productionTarget(level, building);

        // Try to produce the priority target
        if (target.isPresent()) {
            Optional<ProductionRecipe> specific =
                    findRecipeForOutput(level, source, target.get());
            if (specific.isPresent()) return specific;
        }

        if (target.isEmpty()) return Optional.empty();

        // Opportunistic: any below-quota recipe with available inputs
        return findBestAvailable(level, building, source);
    }

    @Override
    protected int calculateBatchSize(ServerLevel level, ProductionRecipe recipe) {
        Building source = resolveInputSource(level, workBuilding);
        if (source == null) return 0;
        int available = recipe.inputs().entrySet().stream()
                .mapToInt(e -> BuildingStorageAccess.countItem(level, source, e.getKey())
                        / e.getValue())
                .min().orElse(0);
        return Math.min(available, MAX_BATCH);
    }

    @Override
    protected Building resolveInputSource(ServerLevel level, Building workBuilding) {
        VillageSavedData data = tterrag1112.life_in_the_village.Networking.VillageSavedData.get(level);
        return entity.getAssignedVillageName()
                .flatMap(data::getVillageByName)
                .flatMap(village -> village.getBuildingIds().stream()
                        .map(data::getBuildingById).filter(Optional::isPresent)
                        .map(Optional::get)
                        .filter(b -> b.getType() == BuildingType.STOCKPILE)
                        .findFirst())
                .orElse(workBuilding);
    }

    @Override
    protected Map<Item, Integer> stockQuotas() {
        Map<Item, Integer> q = new LinkedHashMap<>();
        // Carpet — moderate stock of each color
        Map.of(Items.WHITE_CARPET, 32, Items.GRAY_CARPET, 16,
                Items.LIGHT_GRAY_CARPET, 16, Items.BLACK_CARPET, 8,
                Items.BROWN_CARPET, 8, Items.RED_CARPET, 8,
                Items.BLUE_CARPET, 8, Items.GREEN_CARPET, 8,
                Items.YELLOW_CARPET, 8, Items.ORANGE_CARPET, 8).forEach(q::put);
        // Lead — useful for farmers/stable
        q.put(Items.LEAD, 8);
        return q;
    }

    @Override
    protected List<Item> sellableOutputs() {
        return RECIPES.stream().map(WeaverRecipe::output).distinct().toList();
    }

    @Override
    protected boolean canProduceItem(Item item) {
        return RECIPES.stream().anyMatch(r -> r.output() == item);
    }

    @Override protected SoundEvent workSound() { return SoundEvents.UI_LOOM_TAKE_RESULT; }

    @Override
    protected Map<Item, Integer> resourcesToBuy(ServerLevel level, Building building) {
        Map<Item, Integer> toBuy = new LinkedHashMap<>();
        Building source = resolveInputSource(level, building);

        // Buy string when low (for spinning and leads)
        int string = source != null
                ? BuildingStorageAccess.countItem(level, source, Items.STRING) : 0;
        if (string < 8) toBuy.put(Items.STRING, 16 - string);

        // Buy white wool when low (base for most carpet production)
        int white = source != null
                ? BuildingStorageAccess.countItem(level, source, Items.WHITE_WOOL) : 0;
        if (white < 4) toBuy.put(Items.WHITE_WOOL, 16 - white);

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

    // =========================================================================
    // Helpers
    // =========================================================================

    private Optional<ProductionRecipe> findRecipeForOutput(ServerLevel level,
                                                           Building source, Item output) {
        return RECIPES.stream()
                .filter(r -> r.output() == output)
                .filter(r -> BuildingStorageAccess.countItem(level, source, r.input())
                        >= r.inputCount())
                .findFirst()
                .map(WeaverRecipe::toProduction);
    }

    private Optional<ProductionRecipe> findBestAvailable(ServerLevel level,
                                                         Building building,
                                                         Building source) {
        Map<Item, Integer> quotas = stockQuotas();
        WeaverRecipe best = null;
        double lowestRatio = Double.MAX_VALUE;

        for (WeaverRecipe r : RECIPES) {
            if (BuildingStorageAccess.countItem(level, source, r.input()) < r.inputCount()) continue;
            int stock = BuildingStorageAccess.countItem(level, building, r.output());
            int quota = quotas.getOrDefault(r.output(), 0);
            if (stock >= quota) continue;
            double ratio = quota == 0 ? 0.0 : (double) stock / quota;
            if (ratio < lowestRatio) { lowestRatio = ratio; best = r; }
        }
        return Optional.ofNullable(best).map(WeaverRecipe::toProduction);
    }

    // ── Recipe list ───────────────────────────────────────────────────────────

    private record WeaverRecipe(Item input, int inputCount, Item output,
                                int outputCount, int ticks) {
        ProductionRecipe toProduction() {
            return ProductionRecipe.of(input, inputCount, output, outputCount, ticks);
        }
    }

    private static List<WeaverRecipe> buildRecipes() {
        List<WeaverRecipe> r = new ArrayList<>();

        // String → white wool (spinning)
        r.add(SPIN_STRING);

        // Each wool color → matching carpet (2 wool → 3 carpet, vanilla ratio)
        Map<Item, Item> woolToCarpet = new LinkedHashMap<>();
        woolToCarpet.put(Items.WHITE_WOOL,      Items.WHITE_CARPET);
        woolToCarpet.put(Items.ORANGE_WOOL,     Items.ORANGE_CARPET);
        woolToCarpet.put(Items.MAGENTA_WOOL,    Items.MAGENTA_CARPET);
        woolToCarpet.put(Items.LIGHT_BLUE_WOOL, Items.LIGHT_BLUE_CARPET);
        woolToCarpet.put(Items.YELLOW_WOOL,     Items.YELLOW_CARPET);
        woolToCarpet.put(Items.LIME_WOOL,       Items.LIME_CARPET);
        woolToCarpet.put(Items.PINK_WOOL,       Items.PINK_CARPET);
        woolToCarpet.put(Items.GRAY_WOOL,       Items.GRAY_CARPET);
        woolToCarpet.put(Items.LIGHT_GRAY_WOOL, Items.LIGHT_GRAY_CARPET);
        woolToCarpet.put(Items.CYAN_WOOL,       Items.CYAN_CARPET);
        woolToCarpet.put(Items.PURPLE_WOOL,     Items.PURPLE_CARPET);
        woolToCarpet.put(Items.BLUE_WOOL,       Items.BLUE_CARPET);
        woolToCarpet.put(Items.BROWN_WOOL,      Items.BROWN_CARPET);
        woolToCarpet.put(Items.GREEN_WOOL,      Items.GREEN_CARPET);
        woolToCarpet.put(Items.RED_WOOL,        Items.RED_CARPET);
        woolToCarpet.put(Items.BLACK_WOOL,      Items.BLACK_CARPET);
        woolToCarpet.forEach((wool, carpet) -> r.add(new WeaverRecipe(wool, 2, carpet, 3, 50)));

        // 4 string + 1 slimeball → 2 leads (vanilla recipe)
        // Handled as multi-ingredient in ProductionRecipe
        // Note: leads use ProductionRecipe.of(Map.of(...)) directly in chooseRecipe
        // since the record format doesn't support multi-input in WeaverRecipe

        return Collections.unmodifiableList(r);
    }
}