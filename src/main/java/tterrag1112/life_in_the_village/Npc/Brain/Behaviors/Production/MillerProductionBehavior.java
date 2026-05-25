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

import java.util.*;

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

    // 2 wheat → 3 flour; 80 ticks per batch unit
    private static final ProductionRecipe GRIND_WHEAT =
            ProductionRecipe.of(Items.WHEAT, 2, ModItems.WHEAT_FLOUR.get(), 3, 80);

    private static final int MAX_BATCH = 8;

    

    @Override protected BuildingType requiredBuildingType() { return BuildingType.MILLER; }

    @Override
    protected Optional<BlockPos> findWorkstation(ServerLevel level, Building building) {
        return findBlock(level, building, b -> b instanceof GrindstoneBlock);
    }

    @Override
    protected Optional<ProductionRecipe> chooseRecipe(ServerLevel level, Building building) {
        Building source = resolveInputSource(level, building);
        if (source == null) return Optional.empty();

        int wheatAvail = BuildingStorageAccess.countItem(level, source, Items.WHEAT);
        // Need at least 2 wheat per batch unit
        if (wheatAvail >= GRIND_WHEAT.inputs().values().iterator().next()) {
            // Only grind if flour supply is below quota
            Optional<Item> target = productionTarget(level, building);
            if (target.map(t -> t == ModItems.WHEAT_FLOUR.get()).orElse(true)) {
                return Optional.of(GRIND_WHEAT);
            }
        }
        return Optional.empty();
    }

    @Override
    protected int calculateBatchSize(ServerLevel level, ProductionRecipe recipe) {
        Building source = resolveInputSource(level, workBuilding);
        if (source == null) return 0;
        int wheat = BuildingStorageAccess.countItem(level, source, Items.WHEAT);
        return Math.min(wheat / 2, MAX_BATCH);
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
        if (stockpile != null
                && BuildingStorageAccess.countItem(level, stockpile, Items.WHEAT) >= 2) {
            return stockpile;
        }
        return workBuilding;
    }

    @Override
    protected Map<Item, Integer> stockQuotas() {
        // Managed in the stockpile; keep a working stock in the building
        return Map.of(ModItems.WHEAT_FLOUR.get(), 64);
    }

    @Override
    protected List<Item> sellableOutputs() {
        return List.of(ModItems.WHEAT_FLOUR.get());
    }

    @Override
    protected int sellSurplusThreshold() { return 32; }

    @Override
    protected boolean canProduceItem(Item item) {
        return item == ModItems.WHEAT_FLOUR.get();
    }

    @Override
    protected SoundEvent workSound() { return SoundEvents.GRINDSTONE_USE; }

    @Override
    protected Map<Item, Integer> resourcesToBuy(ServerLevel level, Building building) {
        int flourStock = BuildingStorageAccess.countItem(
                level, outputBuilding(level), ModItems.WHEAT_FLOUR.get());
        int quota = stockQuotas().getOrDefault(ModItems.WHEAT_FLOUR.get(), 64);
        if (flourStock >= quota) return Map.of();

        int wheatNeeded = ((quota - flourStock) / 3) * 2; // reverse the 2→3 ratio
        Building source = resolveInputSource(level, building);
        int wheatAvail = source != null
                ? BuildingStorageAccess.countItem(level, source, Items.WHEAT) : 0;
        if (wheatAvail < wheatNeeded) {
            return Map.of(Items.WHEAT, wheatNeeded - wheatAvail);
        }
        return Map.of();
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
     * Phase 6.3.4.1.4 — MILLER routes production XP to CROP_FARMING
     * specifically, not the flat FARMING parent. Wheat-grinding IS
     * agricultural work; the 6.3.3.i sub-skill subdivision under
     * FARMING (CROP_FARMING / ORCHARDING / ANIMAL_HUSBANDRY) makes
     * CROP_FARMING the right axis. The skill hierarchy cascade in
     * SkillComponent propagates 25% from CROP_FARMING → FARMING
     * automatically, so the parent skill still accrues.
     *
     * <p>Without this override, the base class's
     * ProfessionSkills.primary lookup would route MILLER's XP to
     * FARMING (flat parent) — correct but coarse. Routing to
     * CROP_FARMING enables MILLER ↔ FARMER mentorship on the same
     * skill axis and lays groundwork for any future MILLER
     * specialization to compose via the FarmerSpecialtyMultiplier
     * pattern.</p>
     */
    @Override
    protected void awardProductionXp(ServerLevel level,
            net.minecraft.world.item.Item output, int amount) {
        tterrag1112.life_in_the_village.Npc.Skills.SkillXp.award(
                entity,
                tterrag1112.life_in_the_village.Npc.Skills.Skill.CROP_FARMING,
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