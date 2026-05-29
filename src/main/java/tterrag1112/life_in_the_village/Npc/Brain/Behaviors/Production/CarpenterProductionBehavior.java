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

    // Phase 6.6.5 — recipes migrated from inline CarpenterRecipe records
    // to ProductionRecipe (the unified 6.4.10.1 multi-input + skill-gate
    // type). Single-input recipes stay structurally identical; the
    // skill-gate moves from a per-profession field to the canonical
    // ProductionRecipe.skillRequirements map via .withSkillRequirement.
    private static final List<ProductionRecipe> LOG_RECIPES   = buildLogRecipes();
    private static final List<ProductionRecipe> PLANK_RECIPES = buildPlankRecipes();
    private static final Set<Item>              ALL_OUTPUTS;

    static {
        Set<Item> out = new LinkedHashSet<>();
        LOG_RECIPES  .forEach(r -> out.add(r.output()));
        PLANK_RECIPES.forEach(r -> out.add(r.output()));
        ALL_OUTPUTS = Collections.unmodifiableSet(out);
    }

    private static List<ProductionRecipe> allRecipes() {
        List<ProductionRecipe> all = new ArrayList<>(LOG_RECIPES.size() + PLANK_RECIPES.size());
        all.addAll(LOG_RECIPES);
        all.addAll(PLANK_RECIPES);
        return all;
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

    // Phase 6.6.5 — meetsSkillGate(CarpenterRecipe) removed. Skill
    // thresholds live in ProductionRecipe.skillRequirements via
    // .withSkillRequirement(); the base class meetsSkillRequirements
    // helper (6.4.10.1) is now the single canonical check.

    @Override
    protected int calculateBatchSize(ServerLevel level, ProductionRecipe recipe) {
        Building source = resolveInputSource(level, workBuilding);
        if (source == null) return 0;
        // Phase 6.6.5 — multi-input safe via min-batches-across-inputs.
        // Pre-6.6.5 comment said "exactly one entry"; CHISELED_BOOKSHELF
        // masterpiece (after 6.6.5.3) is the first multi-input carpenter
        // recipe.
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
        // Phase 6.8.4 — input-availability conjunct dropped (6.3.4.6
        // decoupling, matching MILLER/BAKER): return a skill-passing
        // recipe for the target regardless of input stock and let the
        // analyze→executeBuy path source logs. calculateBatchSize
        // (input-aware) gates production and routes to the buy. The
        // input-gated findBestAvailableRecipe stays as the opportunistic
        // no-explicit-target fallback only.
        for (ProductionRecipe r : allRecipes()) {
            if (r.output() != output) continue;
            if (!meetsSkillRequirements(r)) continue;
            return Optional.of(r);
        }
        return Optional.empty();
    }

    private Optional<ProductionRecipe> findBestAvailableRecipe(ServerLevel level,
                                                               Building building,
                                                               Building source) {
        Map<Item, Integer> quotas = stockQuotas();
        ProductionRecipe best   = null;
        double           lowest = Double.MAX_VALUE;

        for (ProductionRecipe r : allRecipes()) {
            if (!meetsSkillRequirements(r)) continue;
            if (!hasAllInputs(level, source, r)) continue;
            double ratio = stockRatio(level, building, r.output(), quotas);
            if (ratio < 1.0 && ratio < lowest) { lowest = ratio; best = r; }
        }
        return Optional.ofNullable(best);
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

    private double stockRatio(ServerLevel level, Building building, Item item,
                              Map<Item, Integer> quotas) {
        int quota = quotas.getOrDefault(item, 0);
        if (quota == 0) return Double.MAX_VALUE;
        return (double) BuildingStorageAccess.countItem(level, building, item) / quota;
    }

    // =========================================================================
    // Recipe list
    // =========================================================================

    // Phase 6.6.5 — recipes defined directly as ProductionRecipe.of(...)
    // with .withSkillRequirement(Skill.CARPENTRY, N) for tier gates.
    // The CarpenterRecipe inline record was removed; ProductionRecipe is
    // the canonical multi-input + skill-gate type (6.4.10.1).
    //
    // Masterpiece (CHISELED_BOOKSHELF) is promoted to true vanilla
    // multi-input in 6.6.5.3; this list still defines it single-input
    // as a placeholder and is updated by the masterpiece commit.

    private static ProductionRecipe craft(Item input, int inputCount,
                                          Item output, int outputCount,
                                          int minSkill) {
        ProductionRecipe r = ProductionRecipe.of(input, inputCount, output, outputCount, CRAFT_TICKS);
        return minSkill > 0
                ? r.withSkillRequirement(
                        tterrag1112.life_in_the_village.Npc.Skills.Skill.CARPENTRY, minSkill)
                : r;
    }

    private static ProductionRecipe craft(Item input, int inputCount,
                                          Item output, int outputCount,
                                          int ticks, int minSkill) {
        ProductionRecipe r = ProductionRecipe.of(input, inputCount, output, outputCount, ticks);
        return minSkill > 0
                ? r.withSkillRequirement(
                        tterrag1112.life_in_the_village.Npc.Skills.Skill.CARPENTRY, minSkill)
                : r;
    }

    private static List<ProductionRecipe> buildLogRecipes() {
        // Log → planks is entry-level; CARPENTRY 0.
        List<ProductionRecipe> r = new ArrayList<>();
        Map.of(Items.OAK_LOG,      Items.OAK_PLANKS,
                        Items.SPRUCE_LOG,   Items.SPRUCE_PLANKS,
                        Items.BIRCH_LOG,    Items.BIRCH_PLANKS,
                        Items.JUNGLE_LOG,   Items.JUNGLE_PLANKS,
                        Items.ACACIA_LOG,   Items.ACACIA_PLANKS,
                        Items.DARK_OAK_LOG, Items.DARK_OAK_PLANKS,
                        Items.MANGROVE_LOG, Items.MANGROVE_PLANKS,
                        Items.CHERRY_LOG,   Items.CHERRY_PLANKS)
                .forEach((log, planks) -> r.add(craft(log, 1, planks, 4, 0)));
        return r;
    }

    private static List<ProductionRecipe> buildPlankRecipes() {
        // Phase 6.6.3.3 tier ladder (unchanged by 6.6.5 migration):
        //   slabs / stairs     → CARPENTRY 0
        //   doors / fences     → CARPENTRY 15
        //   chest / barrel /
        //     bookshelf /
        //     crafting_table   → CARPENTRY 30
        //   chiseled_bookshelf → CARPENTRY 50 (masterpiece)
        List<ProductionRecipe> r = new ArrayList<>();
        Map.of(Items.OAK_PLANKS, Items.OAK_SLAB, Items.SPRUCE_PLANKS, Items.SPRUCE_SLAB,
                        Items.BIRCH_PLANKS, Items.BIRCH_SLAB, Items.JUNGLE_PLANKS, Items.JUNGLE_SLAB,
                        Items.ACACIA_PLANKS, Items.ACACIA_SLAB, Items.DARK_OAK_PLANKS, Items.DARK_OAK_SLAB)
                .forEach((p, s) -> r.add(craft(p, 3, s, 6, 0)));
        Map.of(Items.OAK_PLANKS, Items.OAK_STAIRS, Items.SPRUCE_PLANKS, Items.SPRUCE_STAIRS,
                        Items.BIRCH_PLANKS, Items.BIRCH_STAIRS)
                .forEach((p, s) -> r.add(craft(p, 6, s, 4, 0)));
        Map.of(Items.OAK_PLANKS, Items.OAK_DOOR, Items.SPRUCE_PLANKS, Items.SPRUCE_DOOR,
                        Items.BIRCH_PLANKS, Items.BIRCH_DOOR)
                .forEach((p, d) -> r.add(craft(p, 6, d, 3, 15)));
        Map.of(Items.OAK_PLANKS, Items.OAK_FENCE, Items.SPRUCE_PLANKS, Items.SPRUCE_FENCE,
                        Items.BIRCH_PLANKS, Items.BIRCH_FENCE)
                .forEach((p, f) -> r.add(craft(p, 6, f, 3, 15)));
        r.add(craft(Items.OAK_PLANKS,    6, Items.OAK_FENCE_GATE,    1, 15));
        r.add(craft(Items.SPRUCE_PLANKS, 6, Items.SPRUCE_FENCE_GATE, 1, 15));
        r.add(craft(Items.OAK_PLANKS, 8, Items.CHEST,          1, 30));
        r.add(craft(Items.OAK_PLANKS, 6, Items.BARREL,         1, 30));
        r.add(craft(Items.OAK_PLANKS, 4, Items.CRAFTING_TABLE, 1, 30));
        r.add(craft(Items.OAK_PLANKS, 6, Items.BOOKSHELF,      1, 30));
        // Phase 6.6.5.3 — CHISELED_BOOKSHELF promoted to true vanilla
        // multi-input (6 wood_slabs + 3 books → 1). OAK_SLAB stands
        // in for "wood_slabs"; books come from a future LIBRARIAN
        // profession or merchant import — same dormancy pattern as
        // CANDLEMAKER LANTERN (waits on cross-profession supply),
        // BAKER CAKE (waits on milk_bucket from animal husbandry),
        // BLACKSMITH NETHERITE_INGOT (waits on netherite_scrap).
        // Self-activating: when BOOK becomes channel-queryable, the
        // recipe fires automatically.
        r.add(ProductionRecipe.of(
                Map.of(Items.OAK_SLAB, 6, Items.BOOK, 3),
                Items.CHISELED_BOOKSHELF, 1, 80)
                .withSkillRequirement(
                        tterrag1112.life_in_the_village.Npc.Skills.Skill.CARPENTRY, 50));
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