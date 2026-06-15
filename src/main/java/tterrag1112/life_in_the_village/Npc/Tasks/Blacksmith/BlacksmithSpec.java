package tterrag1112.life_in_the_village.Npc.Tasks.Blacksmith;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.Blacksmith.BlacksmithSpecialization;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Homestead.ContextProductionBehavior.Plan;
import tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production.BlacksmithCrafts;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;
import tterrag1112.life_in_the_village.Npc.Tasks.Producer.ProductionTaskSpec;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.AmenityType;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Economy.Resources.ProductionRecipe;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The BLACKSMITH's {@link ProductionTaskSpec} &mdash; the profession's DATA,
 * consumed by the generalized producer infra ({@code ProductionTaskSource} +
 * {@code CraftOutputFulfillment} + {@code SellSurplusFulfillment}). Folds in
 * the plan-building logic that was the pilot's {@code BlacksmithPlans}
 * (convert-then-delete): both the craft plan (anvil, category-skill XP with the
 * specialty bonus) and the intermediate smelt plan (furnace, coal fuel,
 * SMELTING XP) the smith uses, reusing {@link BlacksmithCrafts} for batch
 * sizing / XP / fuel exactly as the validated pilot did.
 *
 * <h3>Output taxonomy</h3>
 * <ul>
 *   <li><b>Finals</b> = the craft-bin outputs (tools / armor / weapons).</li>
 *   <li><b>Intermediates</b> = the smelt-bin outputs (iron / gold / copper
 *       ingots), kept as a LOW reserve and lazily acquired (smelt or buy) as a
 *       dependency of a final short on them.</li>
 * </ul>
 *
 * <p>The smelt-vs-buy intermediate acquisition itself is genuinely
 * blacksmith-specific and stays in {@code SmeltFulfillment} /
 * {@code BuyIngotFulfillment} (registered by {@code BlacksmithFulfillments});
 * this spec only supplies the smelt {@link Plan} they drive.</p>
 */
public final class BlacksmithSpec implements ProductionTaskSpec {

    public static final BlacksmithSpec INSTANCE = new BlacksmithSpec();

    private BlacksmithSpec() {}

    @Override public Profession profession()       { return Profession.BLACKSMITH; }
    @Override public BuildingType buildingType()   { return BuildingType.BLACKSMITH; }
    @Override public int sellWindowDayTick()       { return BlacksmithCrafts.SELL_WINDOW_DAY_TICK; }

    /** Finals = distinct craft-bin outputs that carry a quota. */
    @Override
    public List<Item> finalOutputs() {
        Set<Item> out = new LinkedHashSet<>();
        for (ProductionRecipe r : BlacksmithCrafts.crafting()) {
            if (BlacksmithCrafts.STOCK_QUOTAS.getOrDefault(r.output(), 0) > 0) out.add(r.output());
        }
        return List.copyOf(out);
    }

    /** Intermediates = distinct smelt-bin outputs (the ingots). */
    @Override
    public List<Item> intermediateOutputs() {
        Set<Item> out = new LinkedHashSet<>();
        for (ProductionRecipe r : BlacksmithCrafts.smelting()) out.add(r.output());
        return List.copyOf(out);
    }

    @Override
    public int quota(Item output) {
        return BlacksmithCrafts.STOCK_QUOTAS.getOrDefault(output, 0);
    }

    /** Iron-style reserve buffer for intermediates; 0 for finals (the surplus
     *  headroom for finals is just their quota). Verbatim from the pilot's
     *  {@code IRON_RESERVE_BUFFER} (8). */
    @Override
    public int buffer(Item output) {
        return intermediateOutputs().contains(output) ? RESERVE_BUFFER : 0;
    }

    /** The pilot's iron reserve buffer (target was the quota, 32; buffer 8). */
    public static final int RESERVE_BUFFER = 8;

    @Override
    public List<Item> sellableOutputs() {
        return BlacksmithCrafts.sellableOutputs();
    }

    @Override
    public Map<Item, Integer> finalRecipeInputs(Item output, TownspersonMob npc) {
        return craftRecipeFor(output, npc).map(ProductionRecipe::inputs).orElse(Map.of());
    }

    // ── Plan builders (folded in from the pilot's BlacksmithPlans) ───────────

    /** Build a craft plan for {@code output} (anvil, category-skill XP with the
     *  specialty bonus folded in), or empty if it can't run right now. */
    @Override
    public Optional<Plan> craftPlan(ServerLevel level, TownspersonMob npc, Building building, Item output) {
        ProductionRecipe recipe = craftRecipeFor(output, npc).orElse(null);
        if (recipe == null) return Optional.empty();
        int batch = BlacksmithCrafts.batchSize(level, building, recipe);
        if (batch <= 0) return Optional.empty();
        BlockPos station = AmenityType.firstPresent(level, building, List.of(AmenityType.ANVIL));
        if (station == null) return Optional.empty();
        Skill skill = BlacksmithSpecialization.categorize(recipe.output());
        int xp = BlacksmithCrafts.xpFor(recipe.output(), npc);
        return Optional.of(new Plan(building, station, recipe, skill, xp,
                "Forging at the anvil", batch, /*applyMultipliers*/ true, /*recordLedger*/ true));
    }

    /** Build a smelt plan for {@code intermediate} (furnace + coal fuel,
     *  SMELTING XP), or empty if it can't run right now. Used by the
     *  blacksmith-specific SmeltFulfillment. */
    @Override
    public Optional<Plan> intermediatePlan(ServerLevel level, TownspersonMob npc, Building building, Item intermediate) {
        ProductionRecipe recipe = smeltRecipeFor(intermediate, npc).orElse(null);
        if (recipe == null) return Optional.empty();
        int batch = BlacksmithCrafts.batchSize(level, building, recipe, BlacksmithCrafts.SMELT_FUEL);
        if (batch <= 0) return Optional.empty();
        BlockPos station = AmenityType.firstPresent(level, building, List.of(AmenityType.FURNACE));
        if (station == null) return Optional.empty();
        int xp = BlacksmithCrafts.BASE_XP; // smelting is unbiased (intermediate good)
        return Optional.of(new Plan(building, station, recipe, Skill.SMELTING, xp,
                "Smelting metal", batch, /*applyMultipliers*/ true, /*recordLedger*/ true,
                BlacksmithCrafts.SMELT_FUEL, /*fuelSource*/ building));
    }

    // ── Recipe lookups (folded in from BlacksmithPlans) ──────────────────────

    /** The first crafting-bin recipe whose output is {@code item}, skill-gated. */
    public Optional<ProductionRecipe> craftRecipeFor(Item item, TownspersonMob npc) {
        for (ProductionRecipe r : BlacksmithCrafts.crafting()) {
            if (r.output() != item) continue;
            if (!BlacksmithCrafts.meetsSkillGate(r, npc)) continue;
            return Optional.of(r);
        }
        return Optional.empty();
    }

    /** The first smelting-bin recipe whose output is {@code item}, skill-gated. */
    public Optional<ProductionRecipe> smeltRecipeFor(Item item, TownspersonMob npc) {
        for (ProductionRecipe r : BlacksmithCrafts.smelting()) {
            if (r.output() != item) continue;
            if (!BlacksmithCrafts.meetsSkillGate(r, npc)) continue;
            return Optional.of(r);
        }
        return Optional.empty();
    }
}
