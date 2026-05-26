package tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.CraftingTableBlock;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Profession.WorkplaceAssignmentManager;
import tterrag1112.life_in_the_village.Village.Economy.Resources.ProductionRecipe;

import java.util.*;

/**
 * NPC carpenter. Migrated to AbstractProductionBehavior (Phase 6.2.d.1).
 *
 * Single station (crafting table), single-ingredient recipes, no fuel.
 * The default {@code buildSteps} via {@code findWorkstation} handles everything —
 * no step override needed.
 */
public class CarpenterProductionBehavior extends AbstractProductionBehavior {

    private static final int CRAFT_TICKS = 60;
    private static final int MAX_BATCH   = 8;
    private static final int MIN_LOGS    = 4;

    private static final List<Item> LOG_TYPES = List.of(
            Items.OAK_LOG, Items.SPRUCE_LOG, Items.BIRCH_LOG,
            Items.JUNGLE_LOG, Items.ACACIA_LOG, Items.DARK_OAK_LOG,
            Items.MANGROVE_LOG, Items.CHERRY_LOG);

    private static final List<CarpenterRecipe> LOG_RECIPES   = buildLogRecipes();
    private static final List<CarpenterRecipe> PLANK_RECIPES = buildPlankRecipes();
    private static final Set<Item>             ALL_OUTPUTS;

    static {
        Set<Item> out = new LinkedHashSet<>();
        LOG_RECIPES  .forEach(r -> out.add(r.output()));
        PLANK_RECIPES.forEach(r -> out.add(r.output()));
        ALL_OUTPUTS = Collections.unmodifiableSet(out);
    }

    

    // =========================================================================
    // Abstract implementations
    // =========================================================================

    @Override protected BuildingType requiredBuildingType() { return BuildingType.CARPENTRY; }

    @Override
    protected Optional<BlockPos> findWorkstation(ServerLevel level, Building building) {
        BlockPos min = building.getShape().getMin();
        BlockPos max = building.getShape().getMax();
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            if (level.getBlockState(pos).getBlock() instanceof CraftingTableBlock)
                return Optional.of(pos.immutable());
        }
        return Optional.empty();
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

        // All quotas met / no target — nothing to produce
        if (target.isEmpty()) return Optional.empty();

        // Target exists but inputs aren't available — fall back to any recipe below quota
        return findBestAvailableRecipe(level, building, source);
    }

    /**
     * Phase 6.6.3.3 — skill-gate check shared by both recipe-iteration
     * sites below. {@link CarpenterRecipe#minSkillLevel} is compared
     * against CARPENTRY (the profession's primary skill post-6.6.1.2).
     * Recipes failing the gate are skipped silently — the gate is
     * intentional, not an error.
     */
    private boolean meetsSkillGate(CarpenterRecipe r) {
        if (r.minSkillLevel() <= 0) return true;
        return entity.getSkills().getLevel(
                tterrag1112.life_in_the_village.Npc.Skills.Skill.CARPENTRY)
                >= r.minSkillLevel();
    }

    @Override
    protected int calculateBatchSize(ServerLevel level, ProductionRecipe recipe) {
        Building source = resolveInputSource(level, workBuilding);
        if (source == null) return 0;
        // recipe.inputs() has exactly one entry for carpenter recipes
        int available = recipe.inputs().entrySet().stream()
                .mapToInt(e -> BuildingStorageAccess.countItem(level, source, e.getKey())
                        / e.getValue())
                .min().orElse(0);
        return Math.min(available, MAX_BATCH);
    }

    // =========================================================================
    // Optional overrides
    // =========================================================================

    @Override
    protected Building resolveInputSource(ServerLevel level, Building workBuilding) {
        Building stockpile = findStockpile(level);
        if (stockpile != null) {
            for (Item log : LOG_TYPES) {
                if (BuildingStorageAccess.countItem(level, stockpile, log) > 0)
                    return stockpile;
            }
        }
        return workBuilding;
    }

    @Override
    protected Map<Item, Integer> resourcesToBuy(ServerLevel level, Building building) {
        Building stockpile = findStockpile(level);
        Map<Item, Integer> toBuy = new LinkedHashMap<>();
        for (Item log : LOG_TYPES) {
            int inCarpentry = BuildingStorageAccess.countItem(level, building, log);
            int inStockpile = stockpile != null
                    ? BuildingStorageAccess.countItem(level, stockpile, log) : 0;
            int total = inCarpentry + inStockpile;
            if (total < MIN_LOGS) toBuy.put(log, MIN_LOGS * 2 - total);
        }
        return toBuy;
    }

    @Override
    protected Map<Item, Integer> stockQuotas() {
        return Map.ofEntries(
                Map.entry(Items.OAK_PLANKS,          64),
                Map.entry(Items.SPRUCE_PLANKS,        32),
                Map.entry(Items.BIRCH_PLANKS,         32),
                Map.entry(Items.OAK_SLAB,             64),
                Map.entry(Items.SPRUCE_SLAB,          32),
                Map.entry(Items.OAK_STAIRS,           32),
                Map.entry(Items.SPRUCE_STAIRS,        16),
                Map.entry(Items.OAK_DOOR,             16),
                Map.entry(Items.SPRUCE_DOOR,           8),
                Map.entry(Items.OAK_FENCE,            32),
                Map.entry(Items.SPRUCE_FENCE,         16),
                Map.entry(Items.OAK_FENCE_GATE,        8),
                Map.entry(Items.SPRUCE_FENCE_GATE,     4),
                Map.entry(Items.CHEST,                 8),
                Map.entry(Items.BARREL,                4),
                Map.entry(Items.CRAFTING_TABLE,        4),
                Map.entry(Items.BOOKSHELF,             4),
                // Phase 6.6.3.3 — masterpiece quota kept low (dormant
                // until a master-tier carpenter spawns).
                Map.entry(Items.CHISELED_BOOKSHELF,    2));
    }

    @Override protected List<Item>  sellableOutputs()  { return List.copyOf(ALL_OUTPUTS); }
    @Override protected SoundEvent  workSound()         { return SoundEvents.AXE_STRIP; }
    @Override protected Item        workToolItem()      { return Items.IRON_AXE; }
    @Override protected boolean     canProduceItem(Item item) { return ALL_OUTPUTS.contains(item); }

    @Override
    protected void onProductionComplete(ServerLevel level, ProductionRecipe recipe, int batchSize) {
        if (workBuilding == null) return;
        WorkplaceAssignmentManager.onWorkplaceProduction(
                level, workBuilding.getId(),
                net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .getKey(recipe.output()).toString(),
                batchSize * recipe.outputCount());
    }

    // =========================================================================
    // Recipe helpers
    // =========================================================================

    private Optional<ProductionRecipe> findRecipeForOutput(ServerLevel level,
                                                           Building source,
                                                           Item output) {
        for (CarpenterRecipe r : LOG_RECIPES) {
            if (r.output() != output) continue;
            if (!meetsSkillGate(r)) continue;
            if (BuildingStorageAccess.countItem(level, source, r.input()) >= r.inputCount())
                return Optional.of(r.toProduction());
        }
        for (CarpenterRecipe r : PLANK_RECIPES) {
            if (r.output() != output) continue;
            if (!meetsSkillGate(r)) continue;
            if (BuildingStorageAccess.countItem(level, source, r.input()) >= r.inputCount())
                return Optional.of(r.toProduction());
        }
        return Optional.empty();
    }

    private Optional<ProductionRecipe> findBestAvailableRecipe(ServerLevel level,
                                                               Building building,
                                                               Building source) {
        Map<Item, Integer> quotas = stockQuotas();
        CarpenterRecipe best      = null;
        double          lowest    = Double.MAX_VALUE;

        for (CarpenterRecipe r : LOG_RECIPES) {
            if (!meetsSkillGate(r)) continue;
            if (BuildingStorageAccess.countItem(level, source, r.input()) < r.inputCount()) continue;
            double ratio = stockRatio(level, building, r.output(), quotas);
            if (ratio < 1.0 && ratio < lowest) { lowest = ratio; best = r; }
        }
        for (CarpenterRecipe r : PLANK_RECIPES) {
            if (!meetsSkillGate(r)) continue;
            if (BuildingStorageAccess.countItem(level, source, r.input()) < r.inputCount()) continue;
            double ratio = stockRatio(level, building, r.output(), quotas);
            if (ratio < 1.0 && ratio < lowest) { lowest = ratio; best = r; }
        }
        return Optional.ofNullable(best).map(CarpenterRecipe::toProduction);
    }

    private double stockRatio(ServerLevel level, Building building, Item item,
                              Map<Item, Integer> quotas) {
        int quota = quotas.getOrDefault(item, 0);
        if (quota == 0) return Double.MAX_VALUE;
        return (double) BuildingStorageAccess.countItem(level, building, item) / quota;
    }

    // =========================================================================
    // Recipe list
    // =========================================================================

    /**
     * Phase 6.6.3.3 — {@code minSkillLevel} gates the recipe against
     * the NPC's CARPENTRY level. Default 0 = no gate (preserves the
     * pre-fix behavior for log→planks and other entry-level
     * transformations). Higher tiers unlock as the carpenter levels.
     */
    private record CarpenterRecipe(Item input, int inputCount,
                                   Item output, int outputCount,
                                   int ticks, int minSkillLevel) {
        // Convenience constructors: no-gate, default ticks.
        CarpenterRecipe(Item input, int inputCount, Item output, int outputCount) {
            this(input, inputCount, output, outputCount, CRAFT_TICKS, 0);
        }
        CarpenterRecipe(Item input, int inputCount, Item output, int outputCount,
                        int minSkillLevel) {
            this(input, inputCount, output, outputCount, CRAFT_TICKS, minSkillLevel);
        }
        ProductionRecipe toProduction() {
            return ProductionRecipe.of(input, inputCount, output, outputCount, ticks);
        }
    }

    private static List<CarpenterRecipe> buildLogRecipes() {
        // Log → planks is entry-level; CARPENTRY 0.
        List<CarpenterRecipe> r = new ArrayList<>();
        Map.of(Items.OAK_LOG,      Items.OAK_PLANKS,
                        Items.SPRUCE_LOG,   Items.SPRUCE_PLANKS,
                        Items.BIRCH_LOG,    Items.BIRCH_PLANKS,
                        Items.JUNGLE_LOG,   Items.JUNGLE_PLANKS,
                        Items.ACACIA_LOG,   Items.ACACIA_PLANKS,
                        Items.DARK_OAK_LOG, Items.DARK_OAK_PLANKS,
                        Items.MANGROVE_LOG, Items.MANGROVE_PLANKS,
                        Items.CHERRY_LOG,   Items.CHERRY_PLANKS)
                .forEach((log, planks) -> r.add(new CarpenterRecipe(log, 1, planks, 4)));
        return r;
    }

    private static List<CarpenterRecipe> buildPlankRecipes() {
        // Phase 6.6.3.3 tier ladder:
        //   slabs / stairs     → CARPENTRY 0  (basic transformations)
        //   doors / fences     → CARPENTRY 15 (joinery: harder than slabs)
        //   chest / barrel /
        //     bookshelf /
        //     crafting_table   → CARPENTRY 30 (assemblies)
        //   chiseled_bookshelf → CARPENTRY 50 (masterpiece)
        List<CarpenterRecipe> r = new ArrayList<>();
        Map.of(Items.OAK_PLANKS, Items.OAK_SLAB, Items.SPRUCE_PLANKS, Items.SPRUCE_SLAB,
                        Items.BIRCH_PLANKS, Items.BIRCH_SLAB, Items.JUNGLE_PLANKS, Items.JUNGLE_SLAB,
                        Items.ACACIA_PLANKS, Items.ACACIA_SLAB, Items.DARK_OAK_PLANKS, Items.DARK_OAK_SLAB)
                .forEach((p, s) -> r.add(new CarpenterRecipe(p, 3, s, 6)));
        Map.of(Items.OAK_PLANKS, Items.OAK_STAIRS, Items.SPRUCE_PLANKS, Items.SPRUCE_STAIRS,
                        Items.BIRCH_PLANKS, Items.BIRCH_STAIRS)
                .forEach((p, s) -> r.add(new CarpenterRecipe(p, 6, s, 4)));
        // Joinery tier
        Map.of(Items.OAK_PLANKS, Items.OAK_DOOR, Items.SPRUCE_PLANKS, Items.SPRUCE_DOOR,
                        Items.BIRCH_PLANKS, Items.BIRCH_DOOR)
                .forEach((p, d) -> r.add(new CarpenterRecipe(p, 6, d, 3, 15)));
        Map.of(Items.OAK_PLANKS, Items.OAK_FENCE, Items.SPRUCE_PLANKS, Items.SPRUCE_FENCE,
                        Items.BIRCH_PLANKS, Items.BIRCH_FENCE)
                .forEach((p, f) -> r.add(new CarpenterRecipe(p, 6, f, 3, 15)));
        r.add(new CarpenterRecipe(Items.OAK_PLANKS,    6, Items.OAK_FENCE_GATE,    1, 15));
        r.add(new CarpenterRecipe(Items.SPRUCE_PLANKS, 6, Items.SPRUCE_FENCE_GATE, 1, 15));
        // Assembly tier — multi-component pieces; gated at CARPENTRY 30.
        r.add(new CarpenterRecipe(Items.OAK_PLANKS, 8, Items.CHEST,          1, 30));
        r.add(new CarpenterRecipe(Items.OAK_PLANKS, 6, Items.BARREL,         1, 30));
        r.add(new CarpenterRecipe(Items.OAK_PLANKS, 4, Items.CRAFTING_TABLE, 1, 30));
        r.add(new CarpenterRecipe(Items.OAK_PLANKS, 6, Items.BOOKSHELF,      1, 30));
        // Phase 6.6.3.3 masterpiece — CHISELED_BOOKSHELF. Vanilla
        // recipe is 6 wood_slabs + 3 books → 1; single-input schema
        // approximates as 6 OAK_PLANKS (books implicit, same
        // simplification as BLACKSMITH's diamond-stick approximation
        // in 6.6.2.3). 80t per batch unit — heavier than the 60t
        // baseline to signal mastery work.
        r.add(new CarpenterRecipe(Items.OAK_PLANKS, 6, Items.CHISELED_BOOKSHELF, 1, 80, 50));
        return r;
    }

    private Building findStockpile(ServerLevel level) {
        VillageSavedData data = VillageSavedData.get(level);
        return entity.getAssignedVillageName()
                .flatMap(data::getVillageByName)
                .flatMap(village -> village.getBuildingIds().stream()
                        .map(data::getBuildingById)
                        .filter(Optional::isPresent)
                        .map(Optional::get)
                        .filter(b -> b.getType() == BuildingType.STOCKPILE)
                        .findFirst())
                .orElse(null);
    }
}