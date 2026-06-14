package tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Economy.Resources.ProductionRecipe;
import tterrag1112.life_in_the_village.Village.Economy.Resources.SkillRecipes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * E-S3 — the single source of the CARPENTER craft definitions, shaped after
 * {@link CandleCrafts}. Drives the context-based
 * {@link CarpenterProductionBehavior}.
 *
 * <p>Parity note: recipe list and quotas are lifted verbatim from the former
 * {@code CarpenterProductionBehavior} (CARPENTRY skill bucket from
 * {@link SkillRecipes#forSkill}, {@code stockQuotas()} map). Craft-selection
 * reproduces the lowest-stock/quota-ratio rule. The buy basket mirrors the old
 * class's {@code resourcesToBuy}: any log type below {@code MIN_LOGS=4} is
 * bought up to {@code MIN_LOGS*2=8}, checked against the workBuilding (see
 * "Deviations" in the E-S3 PROGRESS entry — the former stockpile indirection
 * is replaced by the standard buy-into-workBuilding flow).</p>
 */
public final class CarpenterCrafts {

    private CarpenterCrafts() {}

    /** Per-cycle batch cap (former class's {@code MAX_BATCH}). */
    public static final int MAX_BATCH = 8;
    /** Day-tick after which surplus may be sold (APB's default). */
    public static final int SELL_WINDOW_DAY_TICK = 10000;

    private static final int MIN_LOGS = 4;

    private static final List<Item> LOG_TYPES = List.of(
            Items.OAK_LOG, Items.SPRUCE_LOG, Items.BIRCH_LOG,
            Items.JUNGLE_LOG, Items.ACACIA_LOG, Items.DARK_OAK_LOG,
            Items.MANGROVE_LOG, Items.CHERRY_LOG);

    /** One carpenter craft: recipe, stock quota, XP, activity label. */
    public record CarpenterCraft(ProductionRecipe recipe, int quota,
                                 int xpPerBatch, String activityLabel) {
        Item output() { return recipe.output(); }
    }

    /** Stock quotas from former {@code stockQuotas()} — keeps-floors for sell-surplus. */
    private static final Map<Item, Integer> QUOTA_MAP;

    static {
        Map<Item, Integer> q = new LinkedHashMap<>();
        q.put(Items.OAK_PLANKS,           64);
        q.put(Items.SPRUCE_PLANKS,         32);
        q.put(Items.BIRCH_PLANKS,          32);
        q.put(Items.OAK_SLAB,             64);
        q.put(Items.SPRUCE_SLAB,           32);
        q.put(Items.OAK_STAIRS,           32);
        q.put(Items.SPRUCE_STAIRS,         16);
        q.put(Items.OAK_DOOR,             16);
        q.put(Items.SPRUCE_DOOR,            8);
        q.put(Items.OAK_FENCE,            32);
        q.put(Items.SPRUCE_FENCE,          16);
        q.put(Items.OAK_FENCE_GATE,         8);
        q.put(Items.SPRUCE_FENCE_GATE,      4);
        q.put(Items.CHEST,                  8);
        q.put(Items.BARREL,                 4);
        q.put(Items.CRAFTING_TABLE,         4);
        q.put(Items.BOOKSHELF,              4);
        q.put(Items.CHISELED_BOOKSHELF,     2);
        QUOTA_MAP = Collections.unmodifiableMap(q);
    }

    /** All CARPENTRY recipes with their stock quotas. */
    public static final List<CarpenterCraft> CRAFTS;

    static {
        List<CarpenterCraft> crafts = new ArrayList<>();
        for (ProductionRecipe r : SkillRecipes.forSkill(
                tterrag1112.life_in_the_village.Npc.Skills.Skill.CARPENTRY)) {
            int quota = QUOTA_MAP.getOrDefault(r.output(), 8);
            crafts.add(new CarpenterCraft(r, quota, 3, "Carpentry"));
        }
        CRAFTS = Collections.unmodifiableList(crafts);
    }

    /** Per-item stock quotas for sell-surplus computation. */
    public static Map<Item, Integer> quotas() {
        Map<Item, Integer> q = new LinkedHashMap<>();
        for (CarpenterCraft c : CRAFTS) q.put(c.output(), c.quota());
        return q;
    }

    /** All recipe outputs, distinct, as sellable items. */
    public static List<Item> sellableOutputs() {
        return CRAFTS.stream().map(CarpenterCraft::output).distinct().toList();
    }

    /**
     * Pick the craft to run this cycle: the under-quota output with the lowest
     * {@code stock / quota} ratio (matching the former class's
     * {@code productionTarget} + {@code findBestAvailableRecipe} logic).
     * All checks against {@code building} (the workBuilding).
     */
    public static CarpenterCraft chooseCraft(ServerLevel level, Building building,
                                             TownspersonMob entity) {
        CarpenterCraft best = null;
        double lowestRatio = Double.MAX_VALUE;

        for (CarpenterCraft c : CRAFTS) {
            if (c.quota() <= 0) continue;
            int stock = BuildingStorageAccess.countItem(level, building, c.output());
            if (stock >= c.quota()) continue;
            double ratio = (double) stock / c.quota();
            if (ratio >= lowestRatio) continue;
            if (!meetsSkillGate(c.recipe(), entity)) continue;
            if (!hasAllInputs(level, building, c.recipe())) continue;
            lowestRatio = ratio;
            best = c;
        }
        return best;
    }

    /** Largest runnable batch from {@code building}'s stock, capped at {@link #MAX_BATCH}. */
    public static int batchSize(ServerLevel level, Building building, ProductionRecipe recipe) {
        int batches = recipe.inputs().entrySet().stream()
                .mapToInt(e -> BuildingStorageAccess.countItem(level, building, e.getKey())
                        / e.getValue())
                .min().orElse(0);
        return Math.min(batches, MAX_BATCH);
    }

    /**
     * Buy basket: each log type below {@code MIN_LOGS} (4) gets bought up to
     * {@code MIN_LOGS*2} (8), checked against the workBuilding.
     * Mirrors the former {@code resourcesToBuy} (combined carpentry+stockpile check
     * simplified to workBuilding — see "Deviations" in the E-S3 PROGRESS entry).
     */
    public static Map<Item, Integer> resourcesToBuy(ServerLevel level, Building building) {
        Map<Item, Integer> toBuy = new LinkedHashMap<>();
        if (building == null) return toBuy;
        for (Item log : LOG_TYPES) {
            int avail = BuildingStorageAccess.countItem(level, building, log);
            if (avail < MIN_LOGS) toBuy.put(log, MIN_LOGS * 2 - avail);
        }
        return toBuy;
    }

    /** Skill gate — mirrors {@link CandleCrafts#meetsSkillGate}. */
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
}
