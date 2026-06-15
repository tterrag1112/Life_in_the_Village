package tterrag1112.life_in_the_village.Npc.Tasks.Producer;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
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
 * The BAKER's {@link ProductionTaskSpec} &mdash; profession DATA consumed by the
 * generic producer infra ({@link ProductionTaskSource} +
 * {@link CraftOutputFulfillment} + {@link SellSurplusFulfillment} +
 * {@link BuyFulfillment}).
 *
 * <p>No intermediates: every recipe input (flour, wheat, cocoa_beans, sugar,
 * egg, pumpkin, milk_bucket) is PURCHASED via the generic
 * {@link BuyFulfillment}. {@link #intermediateInputsOf} returns the full
 * recipe input set so missing inputs lazily spawn {@code Acquire} tasks.</p>
 *
 * <p>Workstation: {@link AmenityType#SMOKER} (preferred) or
 * {@link AmenityType#FURNACE} (fallback) &mdash; mirrors
 * {@code BakerProductionBehavior}'s {@code findBlock(AbstractFurnaceBlock)}.</p>
 *
 * <p>Fuel: {@code COAL x 1} per batch drawn from the own building
 * ({@code plan.building()}), wiring through the existing
 * {@link Plan#fuelPerBatch} + {@link Plan#fuelSource} fields.</p>
 *
 * <p>Skill routing: bread/cookie award BAKING XP; pumpkin_pie/cake award
 * PASTRY XP &mdash; matching {@code BakerProductionBehavior.awardProductionXp}.
 * Skill-aware task generation suppresses recipes the baker cannot yet make
 * (cookie requires BAKING&ge;30, pumpkin_pie PASTRY&ge;15, cake PASTRY&ge;40).</p>
 *
 * <p>Stockpile: inputs arrive via buying; outputs land in the bakery's own
 * building. The stockpile supply chain is deferred to a separate rework.</p>
 */
public final class BakerSpec implements ProductionTaskSpec {

    public static final BakerSpec INSTANCE = new BakerSpec();

    private BakerSpec() {}

    /** Day-tick after which surplus may be sold (mirrors APB default). */
    static final int SELL_WINDOW_DAY_TICK = 10000;

    /** Fuel consumed per batch unit when baking (mirrors legacy fuelPerBatch). */
    static final Map<Item, Integer> BAKE_FUEL = Map.of(Items.COAL, 1);

    /**
     * Stock quotas: production target and sell keep-floor per output.
     * Deliberately tighter than the legacy APB (bread 32 -> 16, etc.) because
     * the task system generates one task per output item and the baker should not
     * over-stock before PASTRY skills unlock the higher-value items.
     */
    public static final Map<Item, Integer> STOCK_QUOTAS = Map.of(
            Items.BREAD,       16,
            Items.COOKIE,      16,
            Items.PUMPKIN_PIE,  4,
            Items.CAKE,         2
    );

    @Override public Profession profession()     { return Profession.BAKER; }
    @Override public BuildingType buildingType() { return BuildingType.BAKERY; }
    @Override public int sellWindowDayTick()     { return SELL_WINDOW_DAY_TICK; }

    @Override
    public List<Item> finalOutputs() {
        // Listed in recipe-priority order (high-value last for task-board
        // ordering; bread is always achievable, pastries unlock with skill).
        return List.of(Items.BREAD, Items.COOKIE, Items.PUMPKIN_PIE, Items.CAKE);
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
        return List.of(Items.BREAD, Items.COOKIE, Items.PUMPKIN_PIE, Items.CAKE);
    }

    @Override
    public Map<Item, Integer> finalRecipeInputs(Item output, TownspersonMob npc) {
        ProductionRecipe r = recipeFor(output, npc);
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

    /**
     * Skill-aware generation: true iff {@code npc} meets the skill gate of the
     * recipe that produces {@code output}. Bread/wheat_bread have no gate;
     * cookie (BAKING>=30), pumpkin_pie (PASTRY>=15), cake (PASTRY>=40) are gated.
     * The task board only emits a {@code ProvideItem(output)} when this returns
     * true, and naturally expands as the baker levels up.
     */
    @Override
    public boolean meetsSkillFor(ServerLevel level, TownspersonMob npc, Item output) {
        return recipeFor(output, npc) != null;
    }

    /**
     * Craft plan: smoker (or furnace fallback), skill-routed XP, COAL x 1 fuel
     * drawn from the bakery's own building. Batch is fuel-aware (bounded by
     * available coal as well as recipe inputs).
     */
    @Override
    public Optional<Plan> craftPlan(ServerLevel level, TownspersonMob npc,
                                    Building building, Item output) {
        ProductionRecipe recipe = recipeFor(output, npc);
        if (recipe == null) return Optional.empty();

        int batch = BlacksmithCrafts.batchSize(level, building, recipe, BAKE_FUEL);
        if (batch <= 0) return Optional.empty();

        BlockPos station = AmenityType.firstPresent(
                level, building, List.of(AmenityType.SMOKER, AmenityType.FURNACE));
        if (station == null) return Optional.empty();

        Skill skill = skillFor(output);
        int xp = 3; // BASE_XP, consistent with other professions

        return Optional.of(new Plan(building, station,
                recipe, skill, xp, "Baking",
                batch, /*applyMultipliers*/ true, /*recordLedger*/ true,
                BAKE_FUEL, /*fuelSource*/ building));
    }

    // ---- Recipe lookup -------------------------------------------------------

    /**
     * First recipe that produces {@code output}, skill-gated. Returns null when
     * the baker cannot yet make the item (skill too low) or when the item is not
     * a baker output.
     */
    private static ProductionRecipe recipeFor(Item output, TownspersonMob npc) {
        for (ProductionRecipe r : BAKER_RECIPES) {
            if (r.output() != output) continue;
            if (!meetsSkillGate(r, npc)) continue;
            return r;
        }
        return null;
    }

    /**
     * The baker's recipe list -- high-value first (cake, pie, cookie), bread
     * last (verbatim from {@code BakerProductionBehavior.RECIPE_PRIORITY}).
     * WHEAT_TO_BREAD listed after FLOUR_TO_BREAD so flour is preferred when
     * available.
     */
    private static final List<ProductionRecipe> BAKER_RECIPES = List.of(
            SkillRecipes.MAKE_CAKE,
            SkillRecipes.MAKE_PUMPKIN_PIE,
            SkillRecipes.MAKE_COOKIE,
            SkillRecipes.FLOUR_TO_BREAD,
            SkillRecipes.WHEAT_TO_BREAD
    );

    /**
     * Skill that should receive XP for baking {@code output}: PASTRY for
     * pies/cakes; BAKING for everything else (bread, cookies). Mirrors
     * {@code BakerProductionBehavior.awardProductionXp}.
     */
    private static Skill skillFor(Item output) {
        return (output == Items.PUMPKIN_PIE || output == Items.CAKE)
                ? Skill.PASTRY : Skill.BAKING;
    }

    /**
     * The NPC meets every skill requirement on {@code recipe} (AND-logic;
     * empty requirements = always true). Mirrors {@code CandleCrafts
     * .meetsSkillGate}.
     */
    static boolean meetsSkillGate(ProductionRecipe recipe, TownspersonMob npc) {
        if (recipe.skillRequirements().isEmpty()) return true;
        var skills = npc.getSkills();
        for (var e : recipe.skillRequirements().entrySet()) {
            if (skills.getLevel(e.getKey()) < e.getValue()) return false;
        }
        return true;
    }
}
