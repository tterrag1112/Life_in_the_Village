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
 * E-S2 — the single source of the CANDLEMAKER craft definitions, shaped like
 * {@code MonasticCrafts}: skill × recipe × quota × xp × label. Drives the
 * context-based {@code CandlemakerProductionBehavior}, which replaced the old
 * {@code AbstractProductionBehavior}-based class.
 *
 * <p>Parity note: the values are lifted verbatim from the former class —
 * the three candle recipes ({@link SkillRecipes#MAKE_CANDLE},
 * {@link SkillRecipes#MAKE_TORCH}, {@link SkillRecipes#MAKE_LANTERN}), their
 * stock quotas (16 / 32 / 4), the {@code MAX_BATCH} of 8, the buy basket
 * (honeycomb, string, stick, coal, iron nugget), and the sellable outputs.
 * The skill gate is the recipe's own {@code skillRequirements()} (CANDLE / TORCH
 * have none; LANTERN requires CANDLEMAKING 50), exactly as the former class
 * enforced via {@code meetsSkillRequirements}.</p>
 */
public final class CandleCrafts {

    private CandleCrafts() {}

    /** Per-cycle batch cap (former class's {@code MAX_BATCH}). */
    public static final int MAX_BATCH = 8;
    /** Day-tick after which surplus may be sold (former class default). */
    public static final int SELL_WINDOW_DAY_TICK = 10000;

    /** One candle craft: the recipe, the target stock {@code quota} (need = quota
     *  − stock), the XP per cycle, and the nameplate label. */
    public record CandleCraft(ProductionRecipe recipe, int quota,
                              int xpPerBatch, String activityLabel) {
        Item output() { return recipe.output(); }
    }

    /** The three candle crafts in priority order (candle, torch, lantern). */
    public static final List<CandleCraft> CRAFTS = List.of(
            new CandleCraft(SkillRecipes.MAKE_CANDLE,  16, 3, "Making candles"),
            new CandleCraft(SkillRecipes.MAKE_TORCH,   32, 3, "Making torches"),
            new CandleCraft(SkillRecipes.MAKE_LANTERN,  4, 3, "Crafting a lantern"));

    /** The per-item stock quotas (used both as production targets and as sell
     *  keep-floors), mirroring the former {@code stockQuotas()}. */
    public static Map<Item, Integer> quotas() {
        Map<Item, Integer> q = new LinkedHashMap<>();
        for (CandleCraft c : CRAFTS) q.put(c.output(), c.quota());
        return q;
    }

    /** The sellable outputs, mirroring the former {@code sellableOutputs()}. */
    public static List<Item> sellableOutputs() {
        return List.of(Items.CANDLE, Items.TORCH, Items.LANTERN);
    }

    /**
     * Pick the craft to run this cycle: the under-quota output with the lowest
     * {@code stock / quota} ratio (matching the former class's
     * {@code productionTarget}), provided the NPC meets that recipe's skill gate
     * and the building holds its inputs. Returns null when nothing is runnable.
     */
    public static CandleCraft chooseCraft(ServerLevel level, Building building,
                                          TownspersonMob entity) {
        CandleCraft target = null;
        double lowestRatio = Double.MAX_VALUE;
        for (CandleCraft c : CRAFTS) {
            int stock = BuildingStorageAccess.countItem(level, building, c.output());
            if (stock >= c.quota()) continue;
            double ratio = (double) stock / c.quota();
            if (ratio < lowestRatio) { lowestRatio = ratio; target = c; }
        }
        if (target == null) return null;
        if (!meetsSkillGate(target.recipe(), entity)) return null;
        if (!hasAllInputs(level, building, target.recipe())) return null;
        return target;
    }

    /** Largest runnable batch for {@code recipe} from {@code building}'s stock,
     *  capped at {@link #MAX_BATCH} (former {@code calculateBatchSize}). */
    public static int batchSize(ServerLevel level, Building building,
                                ProductionRecipe recipe) {
        int batches = recipe.inputs().entrySet().stream()
                .mapToInt(e -> BuildingStorageAccess.countItem(level, building, e.getKey())
                        / e.getValue())
                .min().orElse(0);
        return Math.min(batches, MAX_BATCH);
    }

    /** The inputs to procure when short — buy to 16 whenever stock is under 8
     *  (former {@code resourcesToBuy}). ChannelRouter routes iron_nugget to the
     *  blacksmith per the supply chain. */
    public static Map<Item, Integer> resourcesToBuy(ServerLevel level, Building building) {
        Map<Item, Integer> toBuy = new LinkedHashMap<>();
        for (Item item : List.of(Items.HONEYCOMB, Items.STRING,
                Items.STICK, Items.COAL, Items.IRON_NUGGET)) {
            int avail = building != null
                    ? BuildingStorageAccess.countItem(level, building, item) : 0;
            if (avail < 8) toBuy.put(item, 16 - avail);
        }
        return toBuy;
    }

    /** The NPC meets every skill requirement on the recipe (AND logic; empty
     *  map = always true) — mirrors {@code AbstractProductionBehavior
     *  .meetsSkillRequirements}. */
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
