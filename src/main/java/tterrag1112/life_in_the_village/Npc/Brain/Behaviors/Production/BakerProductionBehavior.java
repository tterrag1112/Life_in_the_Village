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
import tterrag1112.life_in_the_village.Village.Economy.Resources.SkillRecipes;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskMigration;

/** Phase 6.2.d.1 — migrated from {@code BakerGoal}. */
public class BakerProductionBehavior extends AbstractProductionBehavior {

    // M1 — recipe definitions live in the skill-keyed SkillRecipes registry
    // (owning skills: bread/cookie → BAKING, pie/cake → PASTRY). These remain
    // here as thin aliases so RECIPE_PRIORITY and homestead behaviors resolve
    // to the same objects. FLOUR_TO_BREAD / WHEAT_TO_BREAD stay public for
    // HomeBakingBehavior.
    public static final ProductionRecipe FLOUR_TO_BREAD = SkillRecipes.FLOUR_TO_BREAD;
    public static final ProductionRecipe WHEAT_TO_BREAD = SkillRecipes.WHEAT_TO_BREAD;
    private static final ProductionRecipe MAKE_COOKIE = SkillRecipes.MAKE_COOKIE;
    private static final ProductionRecipe MAKE_PUMPKIN_PIE = SkillRecipes.MAKE_PUMPKIN_PIE;
    private static final ProductionRecipe MAKE_CAKE = SkillRecipes.MAKE_CAKE;

    private static final int MAX_BATCH = 8;

    // Task System yield — when BAKER is in the migration set the Task
    // System owns the work loop; this legacy APB behavior stands down so
    // DoTaskBehavior (WORK@1) takes over.
    @Override
    protected boolean checkExtraStartConditions(net.minecraft.server.level.ServerLevel level,
                                                tterrag1112.life_in_the_village.Entities.custom.TownspersonMob entity) {
        if (TaskMigration.ownsWork(entity.getProfession())) return false;
        return super.checkExtraStartConditions(level, entity);
    }

    // Priority order for chooseRecipe walks: high-value first, falls
    // back to BREAD which has no skill gate and is always choosable.
    private static final List<ProductionRecipe> RECIPE_PRIORITY = List.of(
            MAKE_CAKE, MAKE_PUMPKIN_PIE, MAKE_COOKIE,
            FLOUR_TO_BREAD, WHEAT_TO_BREAD);

    @Override protected BuildingType requiredBuildingType() { return BuildingType.BAKERY; }

    @Override
    protected Optional<BlockPos> findWorkstation(ServerLevel level, Building building) {
        return findBlock(level, building, b -> b instanceof AbstractFurnaceBlock);
    }

    @Override
    protected Optional<ProductionRecipe> chooseRecipe(ServerLevel level, Building building) {
        // Phase 6.3.4.10 — multi-recipe selection with skill-gate.
        // Walk RECIPE_PRIORITY (high-value first); for each candidate:
        //   1. Skill-gate check (meetsSkillRequirements). Log + skip
        //      gated recipes once per behavior instance.
        //   2. Output-quota check — skip if already at quota for this
        //      recipe's output item.
        //   3. Stock-aware preference — prefer recipes whose inputs
        //      are in-stock; otherwise fall through to the next
        //      candidate. The base behavior allows the chosen recipe
        //      even when inputs are missing (6.3.4.6 decoupling) — the
        //      executeBuy path will source them. But for multi-recipe
        //      selection, stock-aware preference avoids picking an
        //      ambitious cake recipe when bread inputs are right there.
        Map<Item, Integer> quotas = stockQuotas();
        Building source = resolveInputSource(level, building);
        ProductionRecipe firstViable = null;
        for (ProductionRecipe recipe : RECIPE_PRIORITY) {
            if (!meetsSkillRequirements(recipe)) {
                logSkillGate(recipe);
                continue;
            }
            int outStock = BuildingStorageAccess.countItem(
                    level, outputBuilding(level), recipe.output());
            int outQuota = quotas.getOrDefault(recipe.output(), 32);
            if (outStock >= outQuota) continue;

            boolean inStock = hasAllInputs(level, source, recipe);
            if (inStock) return Optional.of(recipe);
            if (firstViable == null) firstViable = recipe;
        }
        // No in-stock recipe found. Default to the first skill+quota
        // viable recipe so the analyze→executeBuy path fires for its
        // inputs. If none are viable, return empty (cycle stalls
        // gracefully — diagnostic from the base layer).
        return Optional.ofNullable(firstViable);
    }

    private static boolean hasAllInputs(ServerLevel level, Building source,
                                        ProductionRecipe recipe) {
        if (source == null) return false;
        for (var e : recipe.inputs().entrySet()) {
            if (BuildingStorageAccess.countItem(level, source, e.getKey()) < e.getValue()) {
                return false;
            }
        }
        return true;
    }

    @Override
    protected int calculateBatchSize(ServerLevel level, ProductionRecipe recipe) {
        Building source = resolveInputSource(level, workBuilding);
        if (source == null) return 0;
        // Generalised across all baker recipes by walking inputs.
        int minBatches = MAX_BATCH;
        for (var e : recipe.inputs().entrySet()) {
            int avail = BuildingStorageAccess.countItem(level, source, e.getKey());
            minBatches = Math.min(minBatches, avail / e.getValue());
        }
        return minBatches;
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
        // Phase 6.3.4.10 — produces map keyed on recipe.output() (was
        // hardcoded BREAD) so cookies / pies / cakes flow through the
        // same step machinery as bread.
        Map<Item, Integer> produces = Map.of(recipe.output(),
                recipe.outputCount() * batchSize);
        return List.of(new ProductionStep(ovenPos.get(), scaledTicks,
                SoundEvents.FURNACE_FIRE_CRACKLE, Items.AIR, consumes, produces));
    }

    @Override
    protected Building resolveInputSource(ServerLevel level, Building workBuilding) {
        // Stockpile when ANY recipe's primary input lives there; falls
        // back to the bakery's own storage.
        Building stockpile = findStockpile(level);
        if (stockpile != null) {
            boolean hasAny =
                    BuildingStorageAccess.countItem(level, stockpile, ModItems.WHEAT_FLOUR.get()) > 0
                 || BuildingStorageAccess.countItem(level, stockpile, Items.WHEAT) >= 3
                 || BuildingStorageAccess.countItem(level, stockpile, Items.COCOA_BEANS) > 0
                 || BuildingStorageAccess.countItem(level, stockpile, Items.PUMPKIN) > 0
                 || BuildingStorageAccess.countItem(level, stockpile, Items.SUGAR) > 0
                 || BuildingStorageAccess.countItem(level, stockpile, Items.EGG) > 0
                 || BuildingStorageAccess.countItem(level, stockpile, Items.MILK_BUCKET) > 0;
            if (hasAny) return stockpile;
        }
        return workBuilding;
    }

    @Override
    protected Map<Item, Integer> stockQuotas() {
        return Map.of(
                Items.BREAD, 32,
                Items.COOKIE, 24,
                Items.PUMPKIN_PIE, 8,
                Items.CAKE, 4);
    }

    @Override
    protected List<Item> sellableOutputs() {
        return List.of(Items.BREAD, Items.COOKIE, Items.PUMPKIN_PIE, Items.CAKE);
    }

    @Override
    protected boolean canProduceItem(Item item) {
        return item == Items.BREAD || item == Items.COOKIE
                || item == Items.PUMPKIN_PIE || item == Items.CAKE;
    }

    @Override protected SoundEvent workSound() { return SoundEvents.FURNACE_FIRE_CRACKLE; }

    @Override
    protected Map<Item, Integer> resourcesToBuy(ServerLevel level, Building building) {
        // Restricted to bread's inputs only. PASTRY recipes are
        // skill-gated and dormant on a fresh BAKER, so seeding their
        // ingredient demand into the buy path is premature — would
        // try to source cocoa/sugar/milk before BAKER can actually
        // use them. When PASTRY recipes become reachable, this hook
        // can grow per-recipe demand projection.
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

    /**
     * Phase 6.3.4.10 — recipe-output-driven XP routing. Staple recipes
     * (BREAD, COOKIE) award BAKING; pastry recipes (PUMPKIN_PIE, CAKE)
     * award PASTRY. Cascade then propagates upward (PASTRY → BAKING →
     * CRAFTING) automatically via SkillComponent.addXp.
     *
     * <p>Without this override, the base would route every recipe's
     * XP to BAKER's profession-wide primary (BAKING). That's coarse —
     * it would mean a BAKER making CAKE all day still levels BAKING
     * rather than the rarer PASTRY child, defeating the purpose of
     * the sub-skill distinction.</p>
     */
    @Override
    protected void awardProductionXp(ServerLevel level,
            net.minecraft.world.item.Item output, int amount) {
        var skill = (output == Items.PUMPKIN_PIE || output == Items.CAKE)
                ? tterrag1112.life_in_the_village.Npc.Skills.Skill.PASTRY
                : tterrag1112.life_in_the_village.Npc.Skills.Skill.BAKING;
        tterrag1112.life_in_the_village.Npc.Skills.SkillXp.award(
                entity, skill, amount, level.getGameTime());
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
