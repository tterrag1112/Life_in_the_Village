package tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.StonecutterBlock;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Profession.WorkplaceAssignmentManager;
import tterrag1112.life_in_the_village.Village.Economy.Resources.ProductionRecipe;

import java.util.*;

/**
 * Stonemason cuts raw stone into dressed building materials at a stonecutter.
 * Draws stone and cobblestone from the village mine or stockpile.
 * Finished products (bricks, slabs, stairs, walls) go to the stonemason
 * building and are sold at market.
 */
public class StonemasonProductionBehavior extends AbstractProductionBehavior {

    private static final int MAX_BATCH = 8;

    // Phase 6.6.5 — recipes migrated from inline MasonRecipe records to
    // ProductionRecipe (6.4.10.1 multi-input + skill-gate type).
    private static final List<ProductionRecipe> RECIPES = buildRecipes();

    

    @Override protected BuildingType requiredBuildingType() { return BuildingType.STONEMASON; }

    @Override
    protected Optional<BlockPos> findWorkstation(ServerLevel level, Building building) {
        return findBlock(level, building, b -> b instanceof StonecutterBlock);
    }

    @Override
    protected Optional<ProductionRecipe> chooseRecipe(ServerLevel level, Building building) {
        Building source = resolveInputSource(level, building);
        if (source == null) return Optional.empty();

        Optional<Item> target = productionTarget(level, building);

        // Try to make the priority target
        if (target.isPresent()) {
            Optional<ProductionRecipe> specific = findRecipeForOutput(level, source, target.get());
            if (specific.isPresent()) return specific;
        }

        if (target.isEmpty()) return Optional.empty();

        // Opportunistic: best available below quota
        return findBestAvailable(level, building, source);
    }

    // Phase 6.6.5 — meetsSkillGate(MasonRecipe) removed. The base class
    // meetsSkillRequirements (6.4.10.1) is now the canonical check; gates
    // live on ProductionRecipe.skillRequirements.

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
        VillageSavedData data = VillageSavedData.get(level);
        return entity.getAssignedVillageName()
                .flatMap(data::getVillageByName)
                .flatMap(village -> {
                    // Prefer mine
                    Optional<Building> mine = village.getBuildingIds().stream()
                            .map(data::getBuildingById).filter(Optional::isPresent)
                            .map(Optional::get).filter(b -> b.getType() == BuildingType.MINE)
                            .findFirst();
                    if (mine.isPresent() && hasStoneInputs(level, mine.get())) return mine;
                    // Fall back to stockpile
                    return village.getBuildingIds().stream()
                            .map(data::getBuildingById).filter(Optional::isPresent)
                            .map(Optional::get)
                            .filter(b -> b.getType() == BuildingType.STOCKPILE)
                            .findFirst();
                })
                .orElse(workBuilding);
    }

    @Override
    protected Map<Item, Integer> stockQuotas() {
        return Map.ofEntries(
                Map.entry(Items.STONE_BRICKS,         32),
                Map.entry(Items.STONE_BRICK_SLAB,     32),
                Map.entry(Items.STONE_BRICK_STAIRS,   16),
                Map.entry(Items.STONE_BRICK_WALL,     16),
                Map.entry(Items.CHISELED_STONE_BRICKS, 8),
                Map.entry(Items.COBBLESTONE_SLAB,     16),
                Map.entry(Items.COBBLESTONE_STAIRS,   16),
                Map.entry(Items.COBBLESTONE_WALL,     16),
                Map.entry(Items.SMOOTH_STONE_SLAB,    16),
                Map.entry(Items.POLISHED_ANDESITE,    16),
                Map.entry(Items.POLISHED_GRANITE,      8),
                Map.entry(Items.POLISHED_DIORITE,      8));
    }

    @Override
    protected List<Item> sellableOutputs() {
        return RECIPES.stream().map(ProductionRecipe::output).distinct().toList();
    }

    @Override
    protected boolean canProduceItem(Item item) {
        return RECIPES.stream().anyMatch(r -> r.output() == item);
    }

    @Override protected SoundEvent workSound() { return SoundEvents.UI_STONECUTTER_TAKE_RESULT; }

    @Override
    protected Map<Item, Integer> resourcesToBuy(ServerLevel level, Building building) {
        Map<Item, Integer> toBuy = new LinkedHashMap<>();
        Building source = resolveInputSource(level, building);

        List<Item> stoneInputs = List.of(Items.STONE, Items.COBBLESTONE,
                Items.SMOOTH_STONE, Items.STONE_BRICKS,
                Items.ANDESITE, Items.GRANITE, Items.DIORITE);

        for (Item stone : stoneInputs) {
            int avail = source != null
                    ? BuildingStorageAccess.countItem(level, source, stone) : 0;
            if (avail < 8) toBuy.put(stone, 16 - avail);
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

    // =========================================================================
    // Helpers
    // =========================================================================

    private Optional<ProductionRecipe> findRecipeForOutput(ServerLevel level,
                                                           Building source, Item output) {
        return RECIPES.stream()
                .filter(r -> r.output() == output)
                .filter(this::meetsSkillRequirements)
                .filter(r -> hasAllInputs(level, source, r))
                .findFirst();
    }

    private Optional<ProductionRecipe> findBestAvailable(ServerLevel level,
                                                        Building building,
                                                        Building source) {
        Map<Item, Integer> quotas = stockQuotas();
        ProductionRecipe best = null;
        double lowestRatio = Double.MAX_VALUE;

        for (ProductionRecipe r : RECIPES) {
            if (!meetsSkillRequirements(r)) continue;
            if (!hasAllInputs(level, source, r)) continue;
            int stock = BuildingStorageAccess.countItem(level, building, r.output());
            int quota = quotas.getOrDefault(r.output(), 0);
            if (stock >= quota) continue;
            double ratio = quota == 0 ? 0.0 : (double) stock / quota;
            if (ratio < lowestRatio) { lowestRatio = ratio; best = r; }
        }
        return Optional.ofNullable(best);
    }

    private boolean hasStoneInputs(ServerLevel level, Building building) {
        return RECIPES.stream().anyMatch(r -> hasAllInputs(level, building, r));
    }

    private static boolean hasAllInputs(ServerLevel level, Building source,
                                        ProductionRecipe recipe) {
        for (var e : recipe.inputs().entrySet()) {
            if (BuildingStorageAccess.countItem(level, source, e.getKey()) < e.getValue()) {
                return false;
            }
        }
        return true;
    }

    // ── Recipe definitions ────────────────────────────────────────────────────

    // Phase 6.6.5 — recipes defined directly as ProductionRecipe.of(...)
    // with .withSkillRequirement(MASONRY, N) for tier gates. The
    // MasonRecipe inline record was removed.

    private static ProductionRecipe mason(Item input, int inputCount,
                                          Item output, int outputCount,
                                          int ticks, int minSkill) {
        ProductionRecipe r = ProductionRecipe.of(input, inputCount, output, outputCount, ticks);
        return minSkill > 0
                ? r.withSkillRequirement(
                        tterrag1112.life_in_the_village.Npc.Skills.Skill.MASONRY, minSkill)
                : r;
    }

    private static List<ProductionRecipe> buildRecipes() {
        // Phase 6.6.3.4 tier ladder (unchanged by 6.6.5 migration):
        //   bricks / slabs / stairs / walls (basic stone/cobble) → MASONRY 0
        //   polished variants                                    → MASONRY 15
        //   smooth-stone slab                                    → MASONRY 15
        //   chiseled_stone_bricks                                → MASONRY 50 (masterpiece)
        List<ProductionRecipe> r = new ArrayList<>();
        r.add(mason(Items.STONE, 1,  Items.STONE_BRICKS,          1, 40, 0));
        r.add(mason(Items.STONE, 1,  Items.STONE_BRICK_SLAB,      2, 40, 0));
        r.add(mason(Items.STONE, 1,  Items.STONE_BRICK_STAIRS,    1, 40, 0));
        r.add(mason(Items.STONE, 1,  Items.STONE_BRICK_WALL,      1, 40, 0));
        r.add(mason(Items.STONE, 1,  Items.CHISELED_STONE_BRICKS, 1, 80, 50));
        r.add(mason(Items.COBBLESTONE, 1, Items.COBBLESTONE_SLAB,   2, 40, 0));
        r.add(mason(Items.COBBLESTONE, 1, Items.COBBLESTONE_STAIRS, 1, 40, 0));
        r.add(mason(Items.COBBLESTONE, 1, Items.COBBLESTONE_WALL,   1, 40, 0));
        r.add(mason(Items.SMOOTH_STONE, 1, Items.SMOOTH_STONE_SLAB, 2, 40, 15));
        r.add(mason(Items.ANDESITE, 2, Items.POLISHED_ANDESITE, 2, 40, 15));
        r.add(mason(Items.GRANITE,  2, Items.POLISHED_GRANITE,  2, 40, 15));
        r.add(mason(Items.DIORITE,  2, Items.POLISHED_DIORITE,  2, 40, 15));
        return Collections.unmodifiableList(r);
    }
}