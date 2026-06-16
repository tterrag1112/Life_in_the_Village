package tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Economy.Resources.ProductionRecipe;
import tterrag1112.life_in_the_village.Village.Economy.Resources.SkillRecipes;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * E-S3 — the single source of the WEAVER craft definitions, shaped after
 * {@link CandleCrafts}. Drives the context-based
 * {@link WeaverProductionBehavior}.
 *
 * <p>Parity note: recipe list and quotas are lifted verbatim from the former
 * {@code WeaverProductionBehavior} (WEAVING skill bucket from
 * {@link SkillRecipes#forSkill}, {@code stockQuotas()} map). Craft-selection
 * reproduces the lowest-stock/quota-ratio rule ({@code productionTarget} +
 * {@code findBestAvailable} from the old class), applied entirely against the
 * workBuilding — no separate {@code resolveInputSource} indirection (see
 * "Deviations" in the PROGRESS entry). The buy basket mirrors the old class's
 * {@code resourcesToBuy}, also checked against the workBuilding.</p>
 */
public final class WeaverCrafts {

    private WeaverCrafts() {}

    /** Per-cycle batch cap (former class's {@code MAX_BATCH}). */
    public static final int MAX_BATCH = 8;
    /** Day-tick after which surplus may be sold (APB's default {@code sellWindowDayTick}). */
    public static final int SELL_WINDOW_DAY_TICK = 10000;

    /** One weaver craft: recipe, stock quota, XP, activity label. */
    public record WeaverCraft(ProductionRecipe recipe, int quota,
                              int xpPerBatch, String activityLabel) {
        public Item output() { return recipe.output(); }
    }

    /** All WEAVING recipes with their stock quotas (from former {@code stockQuotas()}). */
    public static final List<WeaverCraft> CRAFTS;

    static {
        // Map of output → quota from former WeaverProductionBehavior.stockQuotas().
        Map<Item, Integer> quotaMap = new LinkedHashMap<>();
        quotaMap.put(Items.WHITE_CARPET,      32);
        quotaMap.put(Items.GRAY_CARPET,       16);
        quotaMap.put(Items.LIGHT_GRAY_CARPET, 16);
        quotaMap.put(Items.BLACK_CARPET,       8);
        quotaMap.put(Items.BROWN_CARPET,       8);
        quotaMap.put(Items.RED_CARPET,         8);
        quotaMap.put(Items.BLUE_CARPET,        8);
        quotaMap.put(Items.GREEN_CARPET,       8);
        quotaMap.put(Items.YELLOW_CARPET,      8);
        quotaMap.put(Items.ORANGE_CARPET,      8);
        quotaMap.put(Items.LEAD,               8);
        quotaMap.put(Items.WHITE_BANNER,       2);
        // Remaining carpet colors not in the explicit quota map get 8 (default floor).
        quotaMap.putIfAbsent(Items.LIGHT_BLUE_CARPET, 8);
        quotaMap.putIfAbsent(Items.CYAN_CARPET,        8);
        quotaMap.putIfAbsent(Items.MAGENTA_CARPET,     8);
        quotaMap.putIfAbsent(Items.PINK_CARPET,        8);
        quotaMap.putIfAbsent(Items.PURPLE_CARPET,      8);
        quotaMap.putIfAbsent(Items.LIME_CARPET,        8);
        // WHITE_WOOL from SPIN_STRING has no sell quota (input-direction product).
        quotaMap.putIfAbsent(Items.WHITE_WOOL,          0);

        List<WeaverCraft> crafts = new java.util.ArrayList<>();
        for (ProductionRecipe r : SkillRecipes.forSkill(
                tterrag1112.life_in_the_village.Npc.Skills.Skill.WEAVING)) {
            int quota = quotaMap.getOrDefault(r.output(), 8);
            crafts.add(new WeaverCraft(r, quota, 3, "Weaving"));
        }
        CRAFTS = java.util.Collections.unmodifiableList(crafts);
    }

    /** Per-item stock quotas — keep floors for sell-surplus computation. */
    public static Map<Item, Integer> quotas() {
        Map<Item, Integer> q = new LinkedHashMap<>();
        for (WeaverCraft c : CRAFTS) q.put(c.output(), c.quota());
        return q;
    }

    /** All recipe outputs, distinct, as sellable items. */
    public static List<Item> sellableOutputs() {
        return CRAFTS.stream().map(WeaverCraft::output).distinct().toList();
    }

    /**
     * Pick the craft to run this cycle: the under-quota output with the lowest
     * {@code stock / quota} ratio (matching the former class's
     * {@code productionTarget} + {@code findBestAvailable} logic). Returns null
     * when nothing is runnable (all at quota, or inputs missing, or skill gate).
     * All input/stock checks are against {@code building} (the workBuilding).
     */
    public static WeaverCraft chooseCraft(ServerLevel level, Building building,
                                          TownspersonMob entity) {
        WeaverCraft best = null;
        double lowestRatio = Double.MAX_VALUE;

        for (WeaverCraft c : CRAFTS) {
            if (c.quota() <= 0) continue;  // WHITE_WOOL: no target quota, skip
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
     * Buy basket: string when low, white_wool when low — from the workBuilding.
     * Mirrors the former {@code resourcesToBuy} (string &lt;8 → buy to 16;
     * white_wool &lt;4 → buy to 16), but reads against the workBuilding directly
     * since items bought via {@link WorkshopProcurement} land in the workBuilding.
     */
    public static Map<Item, Integer> resourcesToBuy(ServerLevel level, Building building) {
        Map<Item, Integer> toBuy = new LinkedHashMap<>();
        if (building == null) return toBuy;
        int string = BuildingStorageAccess.countItem(level, building, Items.STRING);
        if (string < 8) toBuy.put(Items.STRING, 16 - string);
        int white = BuildingStorageAccess.countItem(level, building, Items.WHITE_WOOL);
        if (white < 4) toBuy.put(Items.WHITE_WOOL, 16 - white);
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
