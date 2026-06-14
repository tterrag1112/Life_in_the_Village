package tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.Blacksmith.BlacksmithSpecialization;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Economy.Resources.ProductionRecipe;
import tterrag1112.life_in_the_village.Village.Economy.Resources.SkillRecipes;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * E-S4a — the single source of the BLACKSMITH craft definitions, shaped after
 * {@link CandleCrafts} but preserving the blacksmith's two-flavor structure
 * (smelt vs craft) and its specialization-aware selection + XP.
 *
 * <p><b>Skill-owned recipes.</b> The smelt / craft recipe bins are read from
 * {@link SkillRecipes#blacksmithSmelting()} / {@link SkillRecipes#blacksmithCrafting()}
 * — the JSON-loaded recipes (still served by the {@code BlacksmithRecipeRegistry}
 * reload listener) merged with the inline masterpiece constants, at call time so
 * datapack reloads are reflected. The smelt / craft distinction (which drives
 * furnace vs anvil and coal fuel) is preserved by which bin a recipe lives in,
 * exactly as the former {@code BlacksmithRecipeData} carried it.</p>
 *
 * <h3>Parity with the former {@code BlacksmithProductionBehavior}</h3>
 * <ul>
 *   <li><b>Selection</b> — {@link #chooseCraft} reproduces the former
 *       {@code chooseRecipe}: commission/quota target first (commission overrides
 *       the spec bias), then opportunistic under-quota smelt (no spec bias —
 *       ingots are intermediate goods), then the spec-weighted under-quota
 *       craft.</li>
 *   <li><b>Specialty weight</b> — {@link #specialtyWeight} (match 4.0, neutral
 *       2.0, opposite 1.0), verbatim.</li>
 *   <li><b>Quotas</b> — {@link #STOCK_QUOTAS}, verbatim from {@code stockQuotas()}.</li>
 *   <li><b>Fuel</b> — smelt recipes consume {@code COAL × 2} per batch (the
 *       former {@code fuelPerBatch}); expressed via the context's fuel hook.</li>
 *   <li><b>XP</b> — {@link #xpFor} routes base 3 to the {@link
 *       BlacksmithSpecialization#categorize output category} sub-skill, ×1.5 when
 *       it matches the NPC's specialization (former {@code awardProductionXp}).</li>
 *   <li><b>Buy</b> — {@link #resourcesToBuy} buys ore up to each ingot's quota
 *       (former {@code resourcesToBuy}); E-S3 buy-only convention — checked
 *       against the workBuilding (no mine/stockpile sourcing; that's deferred to
 *       the supply-chain pass).</li>
 * </ul>
 */
public final class BlacksmithCrafts {

    private BlacksmithCrafts() {}

    /** Per-cycle batch cap (former class's {@code MAX_BATCH}). */
    public static final int MAX_BATCH = 8;
    /** Day-tick after which surplus may be sold (APB's default sellWindowDayTick). */
    public static final int SELL_WINDOW_DAY_TICK = 10000;
    /** Base XP per completed cycle (former {@code XP_PER_PRODUCTION_CYCLE}). */
    public static final int BASE_XP = 3;
    /** Coal consumed per batch unit when smelting (former {@code fuelPerBatch}). */
    public static final Map<Item, Integer> SMELT_FUEL = Map.of(Items.COAL, 2);

    /** Verbatim from former {@code stockQuotas()} — production targets + sell
     *  keep-floors. */
    public static final Map<Item, Integer> STOCK_QUOTAS = Map.ofEntries(
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
            Map.entry(Items.DIAMOND_PICKAXE,     1),
            Map.entry(Items.DIAMOND_SWORD,       1),
            Map.entry(Items.DIAMOND_CHESTPLATE,  1),
            Map.entry(Items.NETHERITE_INGOT,     1));

    /** A selected blacksmith craft: the recipe, whether it's a smelt (furnace +
     *  coal fuel) vs a craft (anvil), and the per-cycle XP (specialty bonus
     *  already folded in). */
    public record BlacksmithCraft(ProductionRecipe recipe, boolean smelt, int xpPerBatch) {
        public Item output() { return recipe.output(); }
        public Skill skill() { return BlacksmithSpecialization.categorize(recipe.output()); }
        public String activityLabel() { return smelt ? "Smelting metal" : "Forging at the anvil"; }
        public Map<Item, Integer> fuelPerBatch() { return smelt ? SMELT_FUEL : Map.of(); }
    }

    /** The smelting bin (JSON ++ inline), live from the registry. */
    public static List<ProductionRecipe> smelting() { return SkillRecipes.blacksmithSmelting(); }
    /** The crafting bin (JSON ++ inline), live from the registry. */
    public static List<ProductionRecipe> crafting() { return SkillRecipes.blacksmithCrafting(); }

    /**
     * Pick the craft to run this cycle, reproducing the former
     * {@code chooseRecipe}:
     * <ol>
     *   <li>If there is a quota/commission target, fill it (smelt first, then
     *       craft) — a commission overrides the spec bias.</li>
     *   <li>Opportunistic: smelt any under-quota ingot whose input is on hand
     *       (no spec bias).</li>
     *   <li>Opportunistic: craft the under-quota item with the highest
     *       spec-weight × urgency score whose inputs are on hand.</li>
     * </ol>
     * All input / stock checks are against {@code building} (the workBuilding):
     * E-S3 buy-only convention. Returns null when nothing is runnable.
     */
    public static BlacksmithCraft chooseCraft(ServerLevel level, Building building,
                                              TownspersonMob entity) {
        List<ProductionRecipe> allSmelting = smelting();
        List<ProductionRecipe> allCrafting = crafting();

        // ── Quota/commission target (commission overrides bias) ─────────────
        Item target = productionTarget(level, building);
        if (target != null) {
            for (ProductionRecipe r : allSmelting) {
                if (r.output() != target) continue;
                if (!meetsSkillGate(r, entity)) continue;
                // Fix B: smelt requires both ore inputs AND coal fuel on hand.
                if (hasAllInputs(level, building, r) && hasFuel(level, building, SMELT_FUEL)) return smelt(r, entity);
            }
            for (ProductionRecipe r : allCrafting) {
                if (r.output() != target) continue;
                if (!meetsSkillGate(r, entity)) continue;
                if (hasAllInputs(level, building, r)) return craft(r, entity);
            }
        }

        // ── Opportunistic smelt (no spec bias — ingots are intermediate) ────
        for (ProductionRecipe r : allSmelting) {
            if (!meetsSkillGate(r, entity)) continue;
            if (!hasAllInputs(level, building, r)) continue;
            // Fix B: smelt requires coal fuel on hand (≥ 1 batch worth).
            if (!hasFuel(level, building, SMELT_FUEL)) continue;
            int stock = BuildingStorageAccess.countItem(level, building, r.output());
            int quota = STOCK_QUOTAS.getOrDefault(r.output(), 0);
            if (stock < quota) return smelt(r, entity);
        }

        // ── Opportunistic craft, biased by specialization ───────────────────
        BlacksmithSpecialization spec =
                tterrag1112.life_in_the_village.Entities.Goals.Profession.Workshop
                        .SpecializationManager.getSpecialization(entity, BlacksmithSpecialization.class);
        ProductionRecipe best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (ProductionRecipe r : allCrafting) {
            if (!meetsSkillGate(r, entity)) continue;
            if (!hasAllInputs(level, building, r)) continue;
            int stock = BuildingStorageAccess.countItem(level, building, r.output());
            int quota = STOCK_QUOTAS.getOrDefault(r.output(), 0);
            if (stock >= quota) continue;
            double urgency = quota == 0 ? 0.0 : 1.0 - ((double) stock / quota);
            double score = specialtyWeight(spec, r.output()) * (0.1 + urgency);
            if (score > bestScore) { bestScore = score; best = r; }
        }
        if (best != null) return craft(best, entity);

        return null;
    }

    private static BlacksmithCraft smelt(ProductionRecipe r, TownspersonMob entity) {
        return new BlacksmithCraft(r, true, xpFor(r.output(), entity));
    }

    private static BlacksmithCraft craft(ProductionRecipe r, TownspersonMob entity) {
        return new BlacksmithCraft(r, false, xpFor(r.output(), entity));
    }

    /**
     * Per-cycle XP for {@code output}: base 3 routed to the output's category
     * sub-skill, ×1.5 when that category matches the NPC's specialization
     * (former {@code awardProductionXp}). The float bonus (4.5) is rounded to
     * the nearest int because the context primitive awards an int {@code
     * Plan.xpPerBatch} (see PROGRESS deviation note).
     */
    public static int xpFor(Item output, TownspersonMob entity) {
        Skill category = BlacksmithSpecialization.categorize(output);
        BlacksmithSpecialization spec =
                tterrag1112.life_in_the_village.Entities.Goals.Profession.Workshop
                        .SpecializationManager.getSpecialization(entity, BlacksmithSpecialization.class);
        float scaled = BASE_XP;
        if (matchesSpecialty(spec, category)) scaled *= 1.5f;
        return Math.round(scaled);
    }

    /** Largest runnable batch from {@code building}'s stock, capped at
     *  {@link #MAX_BATCH}. Counts inputs only — used for craft (anvil) recipes
     *  that have no fuel. */
    public static int batchSize(ServerLevel level, Building building, ProductionRecipe recipe) {
        int batches = recipe.inputs().entrySet().stream()
                .mapToInt(e -> BuildingStorageAccess.countItem(level, building, e.getKey())
                        / e.getValue())
                .min().orElse(0);
        return Math.min(batches, MAX_BATCH);
    }

    /**
     * Fix C — fuel-aware batch size for smelt recipes. Returns the largest
     * batch that is supported by BOTH the available input ore AND the
     * available coal, capped at {@link #MAX_BATCH}.
     *
     * <p>effective batch = min(input-limited, coal-limited, MAX_BATCH)</p>
     *
     * @param fuelPerBatch fuel consumed per batch unit (e.g. {@link #SMELT_FUEL}).
     *                     If empty, delegates to the no-fuel overload.
     */
    public static int batchSize(ServerLevel level, Building building, ProductionRecipe recipe,
                                Map<Item, Integer> fuelPerBatch) {
        if (fuelPerBatch.isEmpty()) return batchSize(level, building, recipe);
        int inputLimited = batchSize(level, building, recipe);
        int fuelLimited = fuelPerBatch.entrySet().stream()
                .mapToInt(e -> BuildingStorageAccess.countItem(level, building, e.getKey())
                        / Math.max(1, e.getValue()))
                .min().orElse(Integer.MAX_VALUE);
        return Math.min(Math.min(inputLimited, fuelLimited), MAX_BATCH);
    }

    /**
     * Buy basket: for each smelting recipe whose ingot output is under quota,
     * buy enough of its input ore to reach the quota; also buy enough COAL
     * to fuel all those smelt batches (Fix D — buy-only model requires coal
     * to be procured here since there is no mine/stockpile sourcing).
     *
     * <p>Coal need = SMELT_FUEL COAL count × total ore batches being bought,
     * minus on-hand coal, floored at 0. Both ore and coal are checked against
     * the workBuilding (E-S3 buy-only convention).</p>
     */
    public static Map<Item, Integer> resourcesToBuy(ServerLevel level, Building building) {
        Map<Item, Integer> toBuy = new LinkedHashMap<>();
        if (building == null) return toBuy;
        int totalOreBatches = 0;
        for (ProductionRecipe r : smelting()) {
            int ingotStock = BuildingStorageAccess.countItem(level, building, r.output());
            int quota = STOCK_QUOTAS.getOrDefault(r.output(), 0);
            if (ingotStock >= quota) continue;
            int oreNeeded = (quota - ingotStock) / Math.max(1, r.outputCount());
            // Single-input smelting recipes: buy the input ore.
            if (r.inputs().size() != 1) continue;
            Item input = r.inputs().keySet().iterator().next();
            int oreAvail = BuildingStorageAccess.countItem(level, building, input);
            int oreToBuy = Math.max(0, oreNeeded - oreAvail);
            if (oreToBuy > 0) {
                toBuy.put(input, oreToBuy);
                // Coal need is proportional to ore being bought (not total oreNeeded),
                // consistent with the prompt spec: "ore batches you're buying for".
                totalOreBatches += oreToBuy;
            }
        }
        // Buy coal to fuel all the ore batches we just ordered.
        // coalPerBatch from SMELT_FUEL; default 2 if somehow not present.
        int coalPerBatch = SMELT_FUEL.getOrDefault(Items.COAL, 2);
        int coalNeeded = totalOreBatches * coalPerBatch;
        if (coalNeeded > 0) {
            int coalOnHand = BuildingStorageAccess.countItem(level, building, Items.COAL);
            int coalToBuy = Math.max(0, coalNeeded - coalOnHand);
            if (coalToBuy > 0) toBuy.put(Items.COAL, coalToBuy);
        }
        return toBuy;
    }

    /** Per-item stock quotas — keep floors for sell-surplus computation. */
    public static Map<Item, Integer> quotas() {
        return STOCK_QUOTAS;
    }

    /** All recipe outputs (smelt ++ craft, distinct) as sellable items
     *  (former {@code sellableOutputs}). */
    public static List<Item> sellableOutputs() {
        Set<Item> items = new LinkedHashSet<>();
        for (ProductionRecipe r : smelting()) items.add(r.output());
        for (ProductionRecipe r : crafting()) items.add(r.output());
        return List.copyOf(items);
    }

    // ── Selection helpers (verbatim from the former class) ───────────────────

    /** Quota-target: the under-quota output with the lowest stock/quota ratio
     *  (former {@code productionTarget} sans the commission path — commissions
     *  are a separate system not reproduced in the E-S* buy-only conversions).
     *  Returns null when every quota item is at or over its floor. */
    private static Item productionTarget(ServerLevel level, Building building) {
        Item lowest = null;
        double lowestRatio = Double.MAX_VALUE;
        for (Map.Entry<Item, Integer> e : STOCK_QUOTAS.entrySet()) {
            int stock = BuildingStorageAccess.countItem(level, building, e.getKey());
            if (stock >= e.getValue()) continue;
            double ratio = (double) stock / e.getValue();
            if (ratio < lowestRatio) { lowestRatio = ratio; lowest = e.getKey(); }
        }
        return lowest;
    }

    /** Preference weight for {@code output} given the NPC's specialization
     *  (former {@code specialtyWeight}: match 4.0, neutral 2.0, opposite 1.0). */
    private static double specialtyWeight(BlacksmithSpecialization spec, Item output) {
        if (spec == null || spec == BlacksmithSpecialization.GENERALIST) return 2.0;
        Skill category = BlacksmithSpecialization.categorize(output);
        return switch (spec) {
            case TOOLSMITH   -> category == Skill.TOOLSMITHING   ? 4.0 : 1.0;
            case ARMORER     -> category == Skill.ARMORSMITHING  ? 4.0 : 1.0;
            case WEAPONSMITH -> category == Skill.WEAPONSMITHING ? 4.0 : 1.0;
            default          -> 2.0;
        };
    }

    private static boolean matchesSpecialty(BlacksmithSpecialization spec, Skill category) {
        if (spec == null) return false;
        return switch (spec) {
            case TOOLSMITH   -> category == Skill.TOOLSMITHING;
            case ARMORER     -> category == Skill.ARMORSMITHING;
            case WEAPONSMITH -> category == Skill.WEAPONSMITHING;
            default          -> false;
        };
    }

    /** The NPC meets every skill requirement on the recipe (AND logic; empty
     *  map = always true) — mirrors {@link CandleCrafts#meetsSkillGate}. */
    public static boolean meetsSkillGate(ProductionRecipe recipe, TownspersonMob entity) {
        if (recipe.skillRequirements().isEmpty()) return true;
        var skills = entity.getSkills();
        for (var e : recipe.skillRequirements().entrySet()) {
            if (skills.getLevel(e.getKey()) < e.getValue()) return false;
        }
        return true;
    }

    private static boolean hasAllInputs(ServerLevel level, Building building,
                                        ProductionRecipe recipe) {
        return recipe.inputs().entrySet().stream().allMatch(e ->
                BuildingStorageAccess.countItem(level, building, e.getKey()) >= e.getValue());
    }

    /**
     * Fix B helper — true when the building holds at least one batch's worth
     * of every fuel item in {@code fuelMap}.
     */
    private static boolean hasFuel(ServerLevel level, Building building,
                                   Map<Item, Integer> fuelMap) {
        for (Map.Entry<Item, Integer> e : fuelMap.entrySet()) {
            if (BuildingStorageAccess.countItem(level, building, e.getKey()) < e.getValue()) {
                return false;
            }
        }
        return true;
    }
}
