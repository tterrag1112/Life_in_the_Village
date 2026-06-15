package tterrag1112.life_in_the_village.Npc.Tasks.Producer;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Items.ModItems;
import tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Homestead.ContextProductionBehavior.Plan;
import tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production.BlacksmithCrafts;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.AmenityType;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Economy.Resources.ProductionRecipe;
import tterrag1112.life_in_the_village.Village.Economy.Resources.SkillRecipes;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The MILLER's {@link ProductionTaskSpec} &mdash; profession DATA consumed by the
 * generic producer infra ({@link ProductionTaskSource} +
 * {@link CraftOutputFulfillment} + {@link SellSurplusFulfillment} +
 * {@link BuyFulfillment}).
 *
 * <p>No intermediates: every recipe input (wheat, bone, sugar_cane) is
 * PURCHASED via the generic {@link BuyFulfillment}. {@link #intermediateInputsOf}
 * returns the full recipe input set so missing inputs lazily spawn
 * {@code Acquire} tasks.</p>
 *
 * <p>Workstation: {@link AmenityType#GRINDSTONE} &mdash; mirrors
 * {@code MillerProductionBehavior}'s {@code findBlock(GrindstoneBlock)}.</p>
 *
 * <p>No fuel: grinding requires no coal.</p>
 *
 * <p>Outputs land in the miller's own building. Flour does NOT go to the
 * stockpile here; the baker buys its own flour via the generic buy path. The
 * miller-to-baker stockpile supply chain is deferred to a separate rework.</p>
 *
 * <p>All milling recipes are ungated (no skill requirement), so
 * {@link #meetsSkillFor} uses the default {@code true} and no override is
 * needed. MILLING XP is awarded uniformly.</p>
 */
public final class MillerSpec implements ProductionTaskSpec {

    public static final MillerSpec INSTANCE = new MillerSpec();

    private MillerSpec() {}

    /** Day-tick after which surplus may be sold (mirrors APB default). */
    static final int SELL_WINDOW_DAY_TICK = 10000;

    /**
     * Stock quotas: verbatim from {@code MillerProductionBehavior.stockQuotas()}.
     * Flour highest (primary chain), bone_meal mid, sugar lowest.
     */
    public static final Map<Item, Integer> STOCK_QUOTAS = Map.of(
            ModItems.WHEAT_FLOUR.get(), 64,
            Items.BONE_MEAL,            32,
            Items.SUGAR,                16
    );

    /** The miller's three recipes in priority order (verbatim from legacy). */
    private static final List<ProductionRecipe> MILLER_RECIPES = List.of(
            SkillRecipes.GRIND_WHEAT,
            SkillRecipes.GRIND_BONES,
            SkillRecipes.PROCESS_SUGAR_CANE
    );

    @Override public Profession profession()     { return Profession.MILLER; }
    @Override public BuildingType buildingType() { return BuildingType.MILLER; }
    @Override public int sellWindowDayTick()     { return SELL_WINDOW_DAY_TICK; }

    @Override
    public List<Item> finalOutputs() {
        return List.of(ModItems.WHEAT_FLOUR.get(), Items.BONE_MEAL, Items.SUGAR);
    }

    /** No self-produced intermediates -- all inputs are purchased. */
    @Override
    public List<Item> intermediateOutputs() {
        return List.of();
    }

    @Override
    public int quota(Item output) {
        return STOCK_QUOTAS.getOrDefault(output, 0);
    }

    @Override
    public int buffer(Item output) {
        return 0;
    }

    @Override
    public List<Item> sellableOutputs() {
        return List.of(ModItems.WHEAT_FLOUR.get(), Items.BONE_MEAL, Items.SUGAR);
    }

    @Override
    public Map<Item, Integer> finalRecipeInputs(Item output, TownspersonMob npc) {
        ProductionRecipe r = recipeFor(output);
        return r != null ? r.inputs() : Map.of();
    }

    /**
     * All recipe inputs are purchased, so return the full input set to trigger
     * lazy {@code Acquire} -> {@link BuyFulfillment} when inputs are short.
     */
    @Override
    public List<Item> intermediateInputsOf(Item output, TownspersonMob npc) {
        return List.copyOf(finalRecipeInputs(output, npc).keySet());
    }

    // meetsSkillFor: milling recipes carry no skill gate, so the default
    // (always true) is correct -- no override needed.

    /**
     * Craft plan: grindstone workstation, MILLING XP, no fuel. Batch is
     * input-limited only.
     */
    @Override
    public Optional<Plan> craftPlan(ServerLevel level, TownspersonMob npc,
                                    Building building, Item output) {
        ProductionRecipe recipe = recipeFor(output);
        if (recipe == null) return Optional.empty();

        int batch = BlacksmithCrafts.batchSize(level, building, recipe);
        if (batch <= 0) return Optional.empty();

        BlockPos station = AmenityType.firstPresent(
                level, building, List.of(AmenityType.GRINDSTONE));
        if (station == null) return Optional.empty();

        return Optional.of(new Plan(building, station,
                recipe, Skill.MILLING, /*xpPerBatch*/ 3, "Grinding at the mill",
                batch, /*applyMultipliers*/ true, /*recordLedger*/ true));
    }

    // ---- Recipe lookup -------------------------------------------------------

    /** The recipe whose output is {@code output}, or null if not a miller output. */
    private static ProductionRecipe recipeFor(Item output) {
        for (ProductionRecipe r : MILLER_RECIPES) {
            if (r.output() == output) return r;
        }
        return null;
    }
}
