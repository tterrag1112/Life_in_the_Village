package tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.AnvilBlock;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.Blacksmith.BlacksmithSpecialization;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Economy.Resources.BlacksmithRecipeData;
import tterrag1112.life_in_the_village.Village.Economy.Resources.BlacksmithRecipeRegistry;
import tterrag1112.life_in_the_village.Profession.WorkplaceAssignmentManager;
import tterrag1112.life_in_the_village.Village.Economy.Resources.ProductionRecipe;
import tterrag1112.life_in_the_village.Village.Economy.Resources.ProductionStep;

import java.util.*;

/**
 * Migrated blacksmith goal using AbstractWorkstationProductionGoal.
 *
 * <h3>Two-recipe types</h3>
 * Smelting (furnace): ore + coal → ingot.
 * Crafting (anvil): ingots → tools / weapons / armor.
 * The {@link #isSmeltRecipe} flag set in {@link #chooseRecipe} drives which
 * station, fuel, and input source the rest of the run uses.
 *
 * <h3>Supply chain</h3>
 * Ore is drawn from the village mine or stockpile. Ingots are stored in the
 * blacksmith building and consumed there for crafting. Finished goods are
 * deposited to the blacksmith building and sold at market.
 */
public class BlacksmithProductionBehavior extends AbstractProductionBehavior {

    private static final int MAX_BATCH = 8;

    /** Set in chooseRecipe; read by buildSteps, resolveInputSource, fuelPerBatch. */
    private boolean isSmeltRecipe = false;

    // Phase 6.6.5.3 — multi-input masterpieces. Inline ProductionRecipe.of(Map.of(...))
    // instances; live alongside the JSON-loaded single-input recipes per the
    // Option A1 hybrid. Diamond tools now require genuine stick handles
    // (sourced from CARPENTER via DirectBusinessChannel); NETHERITE_INGOT
    // is the GENERALIST masterpiece (deferred from 6.6.2.3 pending schema
    // support — now unblocked).
    //
    // Dormancy chains (recipe ships ready, activates when input source
    // exists — same self-activating pattern as MILLER SUGAR, BAKER PASTRY):
    //   DIAMOND_PICKAXE / DIAMOND_SWORD → diamond from MINER (deferred)
    //   NETHERITE_INGOT → netherite_scrap from Nether mining / merchant
    //                     (deferred); gold_ingot from this profession's
    //                     own smelting chain (works today).
    private static final ProductionRecipe MAKE_DIAMOND_PICKAXE = ProductionRecipe.of(
            Map.of(Items.DIAMOND, 3, Items.STICK, 2),
            Items.DIAMOND_PICKAXE, 1, 600)
            .withSkillRequirement(
                    tterrag1112.life_in_the_village.Npc.Skills.Skill.TOOLSMITHING, 50);
    private static final ProductionRecipe MAKE_DIAMOND_SWORD = ProductionRecipe.of(
            Map.of(Items.DIAMOND, 2, Items.STICK, 1),
            Items.DIAMOND_SWORD, 1, 500)
            .withSkillRequirement(
                    tterrag1112.life_in_the_village.Npc.Skills.Skill.WEAPONSMITHING, 50);
    private static final ProductionRecipe MAKE_NETHERITE_INGOT = ProductionRecipe.of(
            Map.of(Items.NETHERITE_SCRAP, 4, Items.GOLD_INGOT, 4),
            Items.NETHERITE_INGOT, 1, 1200)
            .withSkillRequirement(
                    tterrag1112.life_in_the_village.Npc.Skills.Skill.BLACKSMITHING, 65);

    /** Inline crafting masterpieces appended to the JSON-loaded list. */
    private static final List<ProductionRecipe> INLINE_CRAFTING_RECIPES = List.of(
            MAKE_DIAMOND_PICKAXE, MAKE_DIAMOND_SWORD);

    /** Inline smelting masterpieces appended to the JSON-loaded smelting list.
     *  NETHERITE_INGOT smelt-flavor recipe uses the furnace workstation +
     *  coal fuel via the existing isSmeltRecipe branch. */
    private static final List<ProductionRecipe> INLINE_SMELTING_RECIPES = List.of(
            MAKE_NETHERITE_INGOT);

    

    // =========================================================================
    // Abstract implementations
    // =========================================================================

    @Override
    protected BuildingType requiredBuildingType() { return BuildingType.BLACKSMITH; }

    /**
     * Prioritises smelt (build ingot stock) over craft.
     * Uses productionTarget() to pick the specific item.
     *
     * <p>Phase 6.3.2.c — <em>bias not gate</em>: all blacksmiths can
     * craft all categories. Specialization adjusts selection priority
     * in the opportunistic branch via {@link #specialtyWeight}; a
     * lone TOOLSMITH in a village will still pick up an armor order
     * if no tool order is pending (weight 1.0 &gt; 0). Target-driven
     * commissions skip the bias check entirely — a commission is a
     * commission.
     */
    @Override
    protected Optional<ProductionRecipe> chooseRecipe(ServerLevel level,
                                                      Building building) {
        // Phase 6.6.5.2b — recipes are now ProductionRecipe instances
        // produced by BlacksmithRecipeRegistry. Skill gating via base
        // class meetsSkillRequirements (6.4.10.1); per-profession
        // meetsSkillGate helper removed.
        BlacksmithRecipeData data = BlacksmithRecipeRegistry.INSTANCE.getData();
        // Phase 6.6.5.3 — concatenate JSON-loaded + inline masterpiece
        // recipes for each flavor. Inline masterpieces participate in
        // iteration on equal footing with JSON-loaded recipes.
        List<ProductionRecipe> allSmelting = concat(data.getSmeltingRecipes(),
                INLINE_SMELTING_RECIPES);
        List<ProductionRecipe> allCrafting = concat(data.getCraftingRecipes(),
                INLINE_CRAFTING_RECIPES);
        Optional<Item> target = productionTarget(level, building);

        // ── Try to fill the priority target (commission overrides bias) ─────
        // Phase 6.8.4 — input-availability conjunct dropped (6.3.4.6
        // decoupling, matching MILLER/BAKER): a skill-passing recipe for
        // the target is returned regardless of input stock, and the
        // analyze→executeBuy path procures the inputs. calculateBatchSize
        // (input-aware) keeps a no-input recipe from producing and routes
        // to the buy. isSmeltRecipe is still set from the source list so
        // buildSteps / resolveInputSource / fuelPerBatch read it correctly.
        if (target.isPresent()) {
            Item t = target.get();

            for (ProductionRecipe r : allSmelting) {
                if (r.output() != t) continue;
                if (!meetsSkillRequirements(r)) continue;
                isSmeltRecipe = true;
                return Optional.of(r);
            }

            for (ProductionRecipe r : allCrafting) {
                if (r.output() != t) continue;
                if (!meetsSkillRequirements(r)) continue;
                isSmeltRecipe = false;
                return Optional.of(r);
            }
        }

        // ── Opportunistic: smelt any available ore (no spec bias — ingots
        //     are intermediate goods, no specialty has preference) ──────────
        for (ProductionRecipe r : allSmelting) {
            if (!meetsSkillRequirements(r)) continue;
            if (!smeltInputsAvailable(level, r)) continue;
            int ingotStock = BuildingStorageAccess.countItem(level, building, r.output());
            int quota     = stockQuotas().getOrDefault(r.output(), 0);
            if (ingotStock < quota) {
                isSmeltRecipe = true;
                return Optional.of(r);
            }
        }

        // ── Opportunistic: craft with available ingots, biased by spec ─────
        ProductionRecipe best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        BlacksmithSpecialization spec = getSpecialization(BlacksmithSpecialization.class);
        for (ProductionRecipe r : allCrafting) {
            if (!meetsSkillRequirements(r)) continue;
            if (!hasAllInputs(level, building, r)) continue;
            int stock = BuildingStorageAccess.countItem(level, building, r.output());
            int quota = stockQuotas().getOrDefault(r.output(), 0);
            if (stock >= quota) continue;
            double urgency = quota == 0 ? 0.0 : 1.0 - ((double) stock / quota);
            double score = specialtyWeight(spec, r.output()) * (0.1 + urgency);
            if (score > bestScore) { bestScore = score; best = r; }
        }
        if (best != null) {
            isSmeltRecipe = false;
            return Optional.of(best);
        }

        return Optional.empty();
    }

    private static List<ProductionRecipe> concat(List<ProductionRecipe> a,
                                                 List<ProductionRecipe> b) {
        if (b.isEmpty()) return a;
        List<ProductionRecipe> out = new ArrayList<>(a.size() + b.size());
        out.addAll(a);
        out.addAll(b);
        return out;
    }

    /** Smelt-flavor input check. JSON-loaded smelting recipes are
     *  single-input (ore from mine/stockpile); inline multi-input
     *  smelting (NETHERITE_INGOT: scrap + gold_ingot) checks every
     *  input — scrap via ore source, gold_ingot from this building's
     *  own smelt stock. */
    private boolean smeltInputsAvailable(ServerLevel level, ProductionRecipe r) {
        // Single-input case: classic ore-from-mine lookup.
        if (r.inputs().size() == 1) {
            return countFromOreSource(level, smeltInput(r)) > 0;
        }
        // Multi-input case: every input must be present in workBuilding
        // (BlacksmithProductionBehavior accumulates intermediate ingots
        // like gold_ingot here) or in the ore source (raw inputs like
        // netherite_scrap).
        Building oreSource = findOreSource(level);
        for (var e : r.inputs().entrySet()) {
            int avail = BuildingStorageAccess.countItem(level, workBuilding, e.getKey());
            if (avail < e.getValue() && oreSource != null) {
                avail += BuildingStorageAccess.countItem(level, oreSource, e.getKey());
            }
            if (avail < e.getValue()) return false;
        }
        return true;
    }

    /** Smelting recipes have exactly one input (single-input JSON schema).
     *  Helper extracts the input item for ore-source lookups. */
    private static Item smeltInput(ProductionRecipe recipe) {
        return recipe.inputs().keySet().iterator().next();
    }

    /** Multi-input safe input-availability check. */
    private static boolean hasAllInputs(ServerLevel level, Building source,
                                        ProductionRecipe recipe) {
        for (var e : recipe.inputs().entrySet()) {
            if (BuildingStorageAccess.countItem(level, source, e.getKey()) < e.getValue()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Phase 6.3.2.c — preference weight for {@code output} given the
     * NPC's blacksmith specialization. Matches the spec table:
     * specialty-match = 4.0, neutral (GENERALIST or category-mismatch
     * intermediate) = 2.0, opposite specialty = 1.0.
     */
    private static double specialtyWeight(BlacksmithSpecialization spec, Item output) {
        if (spec == null || spec == BlacksmithSpecialization.GENERALIST) return 2.0;
        tterrag1112.life_in_the_village.Npc.Skills.Skill category =
                BlacksmithSpecialization.categorize(output);
        return switch (spec) {
            case TOOLSMITH   -> category == tterrag1112.life_in_the_village.Npc.Skills.Skill.TOOLSMITHING ? 4.0 : 1.0;
            case ARMORER     -> category == tterrag1112.life_in_the_village.Npc.Skills.Skill.ARMORSMITHING ? 4.0 : 1.0;
            case WEAPONSMITH -> category == tterrag1112.life_in_the_village.Npc.Skills.Skill.WEAPONSMITHING ? 4.0 : 1.0;
            default          -> 2.0;
        };
    }

    @Override
    protected int calculateBatchSize(ServerLevel level, ProductionRecipe recipe) {
        Building source = isSmeltRecipe ? findOreSource(level) : workBuilding;
        if (source == null) return 0;
        int available = recipe.inputs().entrySet().stream()
                .mapToInt(e -> BuildingStorageAccess.countItem(level, source, e.getKey())
                        / e.getValue())
                .min().orElse(0);
        return Math.min(available, MAX_BATCH);
    }

    // =========================================================================
    // Optional overrides
    // =========================================================================

    /**
     * Smelting: ore + fuel come from mine / stockpile.
     * Crafting: ingots come from the blacksmith building itself.
     */
    @Override
    protected Building resolveInputSource(ServerLevel level, Building workBuilding) {
        return isSmeltRecipe ? findOreSource(level) : workBuilding;
    }

    /** Coal is needed only for smelting steps. */
    @Override
    protected Map<Item, Integer> fuelPerBatch(ProductionRecipe recipe) {
        return isSmeltRecipe ? Map.of(Items.COAL, 2) : Map.of();
    }

    /**
     * Builds a furnace step (smelt) or anvil step (craft) based on
     * the {@link #isSmeltRecipe} flag. Returns empty if the required
     * block is not present in the building.
     */
    @Override
    protected List<ProductionStep> buildSteps(ServerLevel level, Building building,
                                              ProductionRecipe recipe, int batchSize) {
        int scaledTicks = Math.max(20,
                (int)(recipe.ticks() * batchSize * productionSpeedMultiplier()));

        Map<Item, Integer> consumes = new LinkedHashMap<>();
        recipe.inputs().forEach((item, count) -> consumes.put(item, count * batchSize));

        if (isSmeltRecipe) {
            Optional<BlockPos> furnacePos = findBlock(level, building,
                    b -> b instanceof AbstractFurnaceBlock);
            if (furnacePos.isEmpty()) return List.of();

            // Fuel consumed at the furnace
            consumes.merge(Items.COAL, 2 * batchSize, Integer::sum);

            Map<Item, Integer> produces = Map.of(
                    recipe.output(), recipe.outputCount() * batchSize);

            return List.of(new ProductionStep(
                    furnacePos.get(), scaledTicks,
                    SoundEvents.FURNACE_FIRE_CRACKLE, Items.AIR,
                    consumes, produces));
        } else {
            Optional<BlockPos> anvilPos = findBlock(level, building,
                    b -> b instanceof AnvilBlock);
            if (anvilPos.isEmpty()) return List.of();

            Map<Item, Integer> produces = Map.of(
                    recipe.output(), recipe.outputCount() * batchSize);

            return List.of(new ProductionStep(
                    anvilPos.get(), scaledTicks,
                    SoundEvents.ANVIL_USE, Items.IRON_AXE,
                    consumes, produces));
        }
    }

    @Override
    protected Map<Item, Integer> stockQuotas() {
        return Map.ofEntries(
                Map.entry(Items.IRON_INGOT,         32),
                Map.entry(Items.GOLD_INGOT,          8),
                Map.entry(Items.COPPER_INGOT,       16),
                Map.entry(Items.IRON_SWORD,          4),
                Map.entry(Items.IRON_PICKAXE,        4),
                Map.entry(Items.IRON_AXE,            4),
                Map.entry(Items.IRON_SHOVEL,         2),
                Map.entry(Items.IRON_HOE,            2),
                Map.entry(Items.IRON_HELMET,         2),
                Map.entry(Items.IRON_CHESTPLATE,     2),
                Map.entry(Items.IRON_LEGGINGS,       2),
                Map.entry(Items.IRON_BOOTS,          2),
                Map.entry(Items.GOLDEN_SWORD,        1),
                Map.entry(Items.GOLDEN_HELMET,       1),
                // Phase 6.6.5.3 — masterpiece quotas kept low (dormant
                // until master-tier blacksmith + input source exist).
                Map.entry(Items.DIAMOND_PICKAXE,     1),
                Map.entry(Items.DIAMOND_SWORD,       1),
                Map.entry(Items.DIAMOND_CHESTPLATE,  1),
                Map.entry(Items.NETHERITE_INGOT,     1));
    }

    @Override
    protected List<Item> sellableOutputs() {
        BlacksmithRecipeData data = BlacksmithRecipeRegistry.INSTANCE.getData();
        Set<Item> items = new LinkedHashSet<>();
        data.getCraftingRecipes().forEach(r -> items.add(r.output()));
        data.getSmeltingRecipes().forEach(r -> items.add(r.output()));
        INLINE_CRAFTING_RECIPES.forEach(r -> items.add(r.output()));
        INLINE_SMELTING_RECIPES.forEach(r -> items.add(r.output()));
        return List.copyOf(items);
    }

    @Override
    protected boolean canProduceItem(Item item) {
        BlacksmithRecipeData data = BlacksmithRecipeRegistry.INSTANCE.getData();
        return data.getSmeltingRecipes().stream().anyMatch(r -> r.output() == item)
                || data.getCraftingRecipes().stream().anyMatch(r -> r.output() == item)
                || INLINE_CRAFTING_RECIPES.stream().anyMatch(r -> r.output() == item)
                || INLINE_SMELTING_RECIPES.stream().anyMatch(r -> r.output() == item);
    }

    @Override
    protected Map<Item, Integer> resourcesToBuy(ServerLevel level, Building building) {
        BlacksmithRecipeData data = BlacksmithRecipeRegistry.INSTANCE.getData();
        Map<Item, Integer> toBuy = new LinkedHashMap<>();

        for (ProductionRecipe r : data.getSmeltingRecipes()) {
            int ingotStock = BuildingStorageAccess.countItem(level, building, r.output());
            int quota = stockQuotas().getOrDefault(r.output(), 0);
            if (ingotStock >= quota) continue;

            int oreNeeded = (quota - ingotStock) / Math.max(1, r.outputCount());
            Building oreSource = findOreSource(level);
            Item input = smeltInput(r);
            int oreAvail = oreSource != null
                    ? BuildingStorageAccess.countItem(level, oreSource, input) : 0;
            if (oreAvail < oreNeeded) toBuy.put(input, oreNeeded - oreAvail);
        }
        return toBuy;
    }

    /**
     * Phase 6.3.2.b/c — dispatch CRAFTING-cycle XP onto the appropriate
     * blacksmithing sub-skill via {@link BlacksmithSpecialization#categorize}.
     * The cascade in {@code SkillComponent.addXp} carries propagation up
     * to BLACKSMITHING and CRAFTING at 25% each tier.
     *
     * <p>Phase 6.3.2.c — specialty bonus: a +50% XP scalar applies when
     * the output category matches the NPC's specialization. Off-specialty
     * crafting still earns base XP into the appropriate sub-skill.
     */
    @Override
    protected void awardProductionXp(ServerLevel level, Item output, int amount) {
        tterrag1112.life_in_the_village.Npc.Skills.Skill target =
                BlacksmithSpecialization.categorize(output);
        BlacksmithSpecialization spec = getSpecialization(BlacksmithSpecialization.class);
        float scaled = amount;
        if (matchesSpecialty(spec, target)) scaled *= 1.5f;
        tterrag1112.life_in_the_village.Npc.Skills.SkillXp.award(
                entity, target, scaled, level.getGameTime());
    }

    private static boolean matchesSpecialty(
            BlacksmithSpecialization spec,
            tterrag1112.life_in_the_village.Npc.Skills.Skill category) {
        if (spec == null) return false;
        return switch (spec) {
            case TOOLSMITH   -> category == tterrag1112.life_in_the_village.Npc.Skills.Skill.TOOLSMITHING;
            case ARMORER     -> category == tterrag1112.life_in_the_village.Npc.Skills.Skill.ARMORSMITHING;
            case WEAPONSMITH -> category == tterrag1112.life_in_the_village.Npc.Skills.Skill.WEAPONSMITHING;
            default -> false;
        };
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

    /** Count of the given item in the mine or stockpile, whichever has more. */
    private int countFromOreSource(ServerLevel level, Item item) {
        Building source = findOreSource(level);
        return source != null ? BuildingStorageAccess.countItem(level, source, item) : 0;
    }

    /** Find the village mine; fall back to the stockpile. */
    private Building findOreSource(ServerLevel level) {
        VillageSavedData data = VillageSavedData.get(level);
        return entity.getAssignedVillageName()
                .flatMap(data::getVillageByName)
                .flatMap(village -> {
                    // Prefer mine
                    Optional<Building> mine = village.getBuildingIds().stream()
                            .map(data::getBuildingById)
                            .filter(Optional::isPresent).map(Optional::get)
                            .filter(b -> b.getType() == BuildingType.MINE)
                            .findFirst();
                    if (mine.isPresent()) return mine;
                    // Fall back to stockpile
                    return village.getBuildingIds().stream()
                            .map(data::getBuildingById)
                            .filter(Optional::isPresent).map(Optional::get)
                            .filter(b -> b.getType() == BuildingType.STOCKPILE)
                            .findFirst();
                })
                .orElse(null);
    }
}
