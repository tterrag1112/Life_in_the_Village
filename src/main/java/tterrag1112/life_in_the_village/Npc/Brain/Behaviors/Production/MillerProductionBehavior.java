package tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.GrindstoneBlock;
import tterrag1112.life_in_the_village.Items.ModItems;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Profession.WorkplaceAssignmentManager;
import tterrag1112.life_in_the_village.Village.Economy.Resources.ProductionRecipe;
import tterrag1112.life_in_the_village.Village.Economy.Resources.SkillRecipes;

import java.util.*;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskMigration;

/**
 * Miller grinds wheat into flour at a grindstone. Flour deposited to the
 * village stockpile provides the baker with a more efficient raw material,
 * multiplying the village's food output per unit of wheat.
 *
 * <h3>Efficiency gain</h3>
 * 2 wheat → 3 flour at the miller vs. 3 wheat → 1 bread directly.
 * With a mill, the same stockpile of wheat produces significantly more bread.
 */
public class MillerProductionBehavior extends AbstractProductionBehavior {

    // Phase 6.3.4.9 — MILLER is the village's general grinder.
    //   GRIND_WHEAT       : 2 wheat → 3 flour (primary chain to BAKER)
    //   GRIND_BONES       : 1 bone  → 3 bone_meal (closes FARMER
    //                       composting / FERTILIZER chain — bone_meal
    //                       matches vanilla crafting yield)
    //   PROCESS_SUGAR_CANE: 1 sugar_cane → 1 sugar (unblocks BAKER
    //                       cake / pumpkin_pie recipes; dormant until
    //                       sugar_cane has a producer in the village)
    // Phase 6.4.5 — GRIND_WHEAT is public so homestead-skill behaviors
    // (HomeMillingBehavior) can reuse it without redefining. The other
    // miller recipes stay private — bones / sugar_cane processing
    // are profession-tier, not homestead-tier.
    // M1 — definitions live in SkillRecipes (owning skill MILLING); aliased
    // here. GRIND_WHEAT stays public for HomeMillingBehavior.
    public static final ProductionRecipe GRIND_WHEAT = SkillRecipes.GRIND_WHEAT;
    private static final ProductionRecipe GRIND_BONES = SkillRecipes.GRIND_BONES;
    private static final ProductionRecipe PROCESS_SUGAR_CANE = SkillRecipes.PROCESS_SUGAR_CANE;

    private static final int MAX_BATCH = 8;

    // Task System yield -- when MILLER is in the migration set the Task
    // System owns the work loop; this legacy APB behavior stands down so
    // DoTaskBehavior (WORK@1) takes over.
    @Override
    protected boolean checkExtraStartConditions(net.minecraft.server.level.ServerLevel level,
                                                tterrag1112.life_in_the_village.Entities.custom.TownspersonMob entity) {
        if (TaskMigration.ownsWork(entity.getProfession())) return false;
        return super.checkExtraStartConditions(level, entity);
    }

    @Override protected BuildingType requiredBuildingType() { return BuildingType.MILLER; }

    @Override
    protected Optional<BlockPos> findWorkstation(ServerLevel level, Building building) {
        return findBlock(level, building, b -> b instanceof GrindstoneBlock);
    }

    @Override
    protected Optional<ProductionRecipe> chooseRecipe(ServerLevel level, Building building) {
        // Phase 6.3.4.9 — multi-recipe selection. Per the 6.3.4.6
        // decoupling principle, we don't filter on input availability;
        // we pick the recipe whose output is most needed and let the
        // analyze→executeBuy path source its inputs. Stock-aware
        // preference still applies as a tiebreaker so an in-stock
        // recipe wins over an empty one.
        Map<Item, Integer> quotas = stockQuotas();
        Building outBldg = outputBuilding(level);
        Building source = resolveInputSource(level, building);

        boolean flourNeeded = BuildingStorageAccess.countItem(level, outBldg,
                ModItems.WHEAT_FLOUR.get()) < quotas.get(ModItems.WHEAT_FLOUR.get());
        boolean boneMealNeeded = BuildingStorageAccess.countItem(level, outBldg,
                Items.BONE_MEAL) < quotas.get(Items.BONE_MEAL);
        boolean sugarNeeded = BuildingStorageAccess.countItem(level, outBldg,
                Items.SUGAR) < quotas.get(Items.SUGAR);

        int wheatStock = source != null
                ? BuildingStorageAccess.countItem(level, source, Items.WHEAT) : 0;
        int boneStock = source != null
                ? BuildingStorageAccess.countItem(level, source, Items.BONE) : 0;
        int caneStock = source != null
                ? BuildingStorageAccess.countItem(level, source, Items.SUGAR_CANE) : 0;

        // In-stock recipes win — no need for a buy round trip.
        if (flourNeeded && wheatStock >= 2)    return Optional.of(GRIND_WHEAT);
        if (boneMealNeeded && boneStock >= 1)  return Optional.of(GRIND_BONES);
        if (sugarNeeded && caneStock >= 1)     return Optional.of(PROCESS_SUGAR_CANE);

        // No in-stock matches. Default to the primary chain (wheat →
        // flour) so executeBuy attempts to source wheat from FARMER.
        // If flour quota is satisfied, drop through to the next needed
        // output.
        if (flourNeeded)    return Optional.of(GRIND_WHEAT);
        if (boneMealNeeded) return Optional.of(GRIND_BONES);
        if (sugarNeeded)    return Optional.of(PROCESS_SUGAR_CANE);
        return Optional.empty();
    }

    @Override
    protected int calculateBatchSize(ServerLevel level, ProductionRecipe recipe) {
        Building source = resolveInputSource(level, workBuilding);
        if (source == null) return 0;
        // Phase 6.3.4.9 — generalised across all miller recipes by
        // walking the recipe's input map.
        int minBatches = MAX_BATCH;
        for (Map.Entry<Item, Integer> e : recipe.inputs().entrySet()) {
            int avail = BuildingStorageAccess.countItem(level, source, e.getKey());
            minBatches = Math.min(minBatches, avail / e.getValue());
        }
        return minBatches;
    }

    /**
     * Flour is deposited directly to the village stockpile so the baker can
     * access it without a separate market transaction.
     */
    @Override
    protected Building outputBuilding(ServerLevel level) {
        Building stockpile = findStockpile(level);
        return stockpile != null ? stockpile : workBuilding;
    }

    @Override
    protected Building resolveInputSource(ServerLevel level, Building workBuilding) {
        Building stockpile = findStockpile(level);
        if (stockpile != null) {
            boolean hasAny =
                    BuildingStorageAccess.countItem(level, stockpile, Items.WHEAT) >= 2
                 || BuildingStorageAccess.countItem(level, stockpile, Items.BONE) >= 1
                 || BuildingStorageAccess.countItem(level, stockpile, Items.SUGAR_CANE) >= 1;
            if (hasAny) return stockpile;
        }
        return workBuilding;
    }

    @Override
    protected Map<Item, Integer> stockQuotas() {
        // Managed in the stockpile; keep a working stock in the building.
        // Flour quota highest (primary chain to BAKER), bone_meal mid
        // (FARMER's FERTILIZER use is steady but lower volume), sugar
        // lowest (specialised use for cake / pumpkin_pie).
        return Map.of(
                ModItems.WHEAT_FLOUR.get(), 64,
                Items.BONE_MEAL, 32,
                Items.SUGAR, 16);
    }

    @Override
    protected List<Item> sellableOutputs() {
        return List.of(ModItems.WHEAT_FLOUR.get(), Items.BONE_MEAL, Items.SUGAR);
    }

    @Override
    protected int sellSurplusThreshold() { return 32; }

    @Override
    protected boolean canProduceItem(Item item) {
        return item == ModItems.WHEAT_FLOUR.get()
                || item == Items.BONE_MEAL
                || item == Items.SUGAR;
    }

    @Override
    protected SoundEvent workSound() { return SoundEvents.GRINDSTONE_USE; }

    @Override
    protected Map<Item, Integer> resourcesToBuy(ServerLevel level, Building building) {
        Map<Item, Integer> quotas = stockQuotas();
        Building outBldg = outputBuilding(level);
        Building source = resolveInputSource(level, building);
        Map<Item, Integer> toBuy = new LinkedHashMap<>();

        // Wheat for flour (primary chain). 2:3 ratio.
        int flourStock = BuildingStorageAccess.countItem(
                level, outBldg, ModItems.WHEAT_FLOUR.get());
        int flourQuota = quotas.getOrDefault(ModItems.WHEAT_FLOUR.get(), 64);
        if (flourStock < flourQuota) {
            int wheatNeeded = ((flourQuota - flourStock) / 3) * 2;
            int wheatAvail = source != null
                    ? BuildingStorageAccess.countItem(level, source, Items.WHEAT) : 0;
            if (wheatAvail < wheatNeeded) toBuy.put(Items.WHEAT, wheatNeeded - wheatAvail);
        }
        // Bones for bone_meal (composting chain). 1:3 ratio.
        int bmStock = BuildingStorageAccess.countItem(level, outBldg, Items.BONE_MEAL);
        int bmQuota = quotas.getOrDefault(Items.BONE_MEAL, 32);
        if (bmStock < bmQuota) {
            int bonesNeeded = Math.max(1, (bmQuota - bmStock) / 3);
            int boneAvail = source != null
                    ? BuildingStorageAccess.countItem(level, source, Items.BONE) : 0;
            if (boneAvail < bonesNeeded) toBuy.put(Items.BONE, bonesNeeded - boneAvail);
        }
        // Sugar cane for sugar (specialised — dormant until village has
        // a sugar_cane producer). 1:1 ratio.
        int sugarStock = BuildingStorageAccess.countItem(level, outBldg, Items.SUGAR);
        int sugarQuota = quotas.getOrDefault(Items.SUGAR, 16);
        if (sugarStock < sugarQuota) {
            int caneNeeded = sugarQuota - sugarStock;
            int caneAvail = source != null
                    ? BuildingStorageAccess.countItem(level, source, Items.SUGAR_CANE) : 0;
            if (caneAvail < caneNeeded) toBuy.put(Items.SUGAR_CANE, caneNeeded - caneAvail);
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

    /**
     * Phase 6.3.4.9 — MILLER's production XP routes to MILLING, a new
     * CRAFTING sub-skill (replaces the 6.3.4.1.4 CROP_FARMING routing).
     * Reasoning: milling is a processing trade, not crop husbandry —
     * grinding wheat, bones, or sugar cane is the same physical
     * activity regardless of what's being ground. The skill hierarchy
     * cascade propagates 25% MILLING → CRAFTING automatically, so the
     * CRAFTING parent still accrues.
     *
     * <p>This override remains in place because the base class falls
     * back to {@code ProfessionSkills.primary} which now returns
     * MILLING — so technically the override is redundant. Keeping it
     * explicit for clarity and to lock the routing against future
     * ProfessionSkills changes.</p>
     */
    @Override
    protected void awardProductionXp(ServerLevel level,
            net.minecraft.world.item.Item output, int amount) {
        tterrag1112.life_in_the_village.Npc.Skills.SkillXp.award(
                entity,
                tterrag1112.life_in_the_village.Npc.Skills.Skill.MILLING,
                amount, level.getGameTime());
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