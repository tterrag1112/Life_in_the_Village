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
 * E-S3 — the single source of the STONEMASON craft definitions, shaped after
 * {@link CandleCrafts}. Drives the context-based
 * {@link StonemasonProductionBehavior}.
 *
 * <p>Parity note: recipe list and quotas are lifted verbatim from the former
 * {@code StonemasonProductionBehavior} (MASONRY skill bucket from
 * {@link SkillRecipes#forSkill}, {@code stockQuotas()} map). Craft-selection
 * reproduces the lowest-stock/quota-ratio rule. The buy basket mirrors the old
 * class's {@code resourcesToBuy} (stone inputs below 8 → buy to 16), checked
 * against the workBuilding (see "Deviations" in the E-S3 PROGRESS entry — the
 * former mine/stockpile indirection is replaced by the standard
 * buy-into-workBuilding flow).</p>
 */
public final class StonemasonCrafts {

    private StonemasonCrafts() {}

    /** Per-cycle batch cap (former class's {@code MAX_BATCH}). */
    public static final int MAX_BATCH = 8;
    /** Day-tick after which surplus may be sold (APB's default). */
    public static final int SELL_WINDOW_DAY_TICK = 10000;

    private static final List<Item> STONE_INPUTS = List.of(
            Items.STONE, Items.COBBLESTONE, Items.SMOOTH_STONE, Items.STONE_BRICKS,
            Items.ANDESITE, Items.GRANITE, Items.DIORITE);

    /** One stonemason craft: recipe, stock quota, XP, activity label. */
    public record StonemasonCraft(ProductionRecipe recipe, int quota,
                                  int xpPerBatch, String activityLabel) {
        public Item output() { return recipe.output(); }
    }

    /** Stock quotas from former {@code stockQuotas()} — keep-floors for sell-surplus. */
    private static final Map<Item, Integer> QUOTA_MAP;

    static {
        Map<Item, Integer> q = new LinkedHashMap<>();
        q.put(Items.STONE_BRICKS,          32);
        q.put(Items.STONE_BRICK_SLAB,      32);
        q.put(Items.STONE_BRICK_STAIRS,    16);
        q.put(Items.STONE_BRICK_WALL,      16);
        q.put(Items.CHISELED_STONE_BRICKS,  8);
        q.put(Items.COBBLESTONE_SLAB,      16);
        q.put(Items.COBBLESTONE_STAIRS,    16);
        q.put(Items.COBBLESTONE_WALL,      16);
        q.put(Items.SMOOTH_STONE_SLAB,     16);
        q.put(Items.POLISHED_ANDESITE,     16);
        q.put(Items.POLISHED_GRANITE,       8);
        q.put(Items.POLISHED_DIORITE,       8);
        QUOTA_MAP = Collections.unmodifiableMap(q);
    }

    /** All MASONRY recipes with their stock quotas. */
    public static final List<StonemasonCraft> CRAFTS;

    static {
        List<StonemasonCraft> crafts = new ArrayList<>();
        for (ProductionRecipe r : SkillRecipes.forSkill(
                tterrag1112.life_in_the_village.Npc.Skills.Skill.MASONRY)) {
            int quota = QUOTA_MAP.getOrDefault(r.output(), 8);
            crafts.add(new StonemasonCraft(r, quota, 3, "Stone cutting"));
        }
        CRAFTS = Collections.unmodifiableList(crafts);
    }

    /** Per-item stock quotas for sell-surplus computation. */
    public static Map<Item, Integer> quotas() {
        Map<Item, Integer> q = new LinkedHashMap<>();
        for (StonemasonCraft c : CRAFTS) q.put(c.output(), c.quota());
        return q;
    }

    /** All recipe outputs, distinct, as sellable items. */
    public static List<Item> sellableOutputs() {
        return CRAFTS.stream().map(StonemasonCraft::output).distinct().toList();
    }

    /**
     * Pick the craft to run this cycle: the under-quota output with the lowest
     * {@code stock / quota} ratio (matching the former class's
     * {@code productionTarget} + {@code findBestAvailable} logic).
     * All checks against {@code building} (the workBuilding).
     */
    public static StonemasonCraft chooseCraft(ServerLevel level, Building building,
                                              TownspersonMob entity) {
        StonemasonCraft best = null;
        double lowestRatio = Double.MAX_VALUE;

        for (StonemasonCraft c : CRAFTS) {
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
     * Buy basket: each stone-type input below 8 gets bought up to 16.
     * Mirrors the former {@code resourcesToBuy} (checked against the resolved
     * input source, simplified to the workBuilding — see "Deviations" in the
     * E-S3 PROGRESS entry).
     */
    public static Map<Item, Integer> resourcesToBuy(ServerLevel level, Building building) {
        Map<Item, Integer> toBuy = new LinkedHashMap<>();
        if (building == null) return toBuy;
        for (Item stone : STONE_INPUTS) {
            int avail = BuildingStorageAccess.countItem(level, building, stone);
            if (avail < 8) toBuy.put(stone, 16 - avail);
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
