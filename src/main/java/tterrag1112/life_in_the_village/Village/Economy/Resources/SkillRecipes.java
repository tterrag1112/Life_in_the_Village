package tterrag1112.life_in_the_village.Village.Economy.Resources;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import tterrag1112.life_in_the_village.Items.ModItems;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Production Architecture M1 — the skill-keyed recipe registry.
 *
 * <p><b>Canonical principle:</b> a SKILL defines a production act (ingredients →
 * station → goods); profession / home / hobby / monk / office are <i>contexts</i>
 * that decide which skills an NPC exercises. This registry is the single source
 * of the static {@link ProductionRecipe} definitions, organized by their owning
 * skill — the skill that defines the act (usually the recipe's
 * {@code skillRequirement} skill, or the staple's profession skill when there is
 * no gate, e.g. {@link #WHEAT_TO_BREAD} → {@link Skill#BAKING}).</p>
 *
 * <p>M1 is a behavior-preserving reorganization: the recipe <i>definitions</i>
 * moved here verbatim from the profession behavior classes; those classes keep
 * their {@code public static} constants as thin aliases to these entries, and
 * source their candidate lists from {@link #forSkill(Skill)} (which returns each
 * skill's recipes in the SAME order the profession iterated before). No recipe
 * value, gate, order, or selection changed.</p>
 *
 * <p>E-S4a — the BLACKSMITH JSON fold: smithing is now genuinely skill-owned.
 * {@link #forSkill(Skill) forSkill(BLACKSMITHING)} returns the full blacksmith
 * production act — the JSON-loaded smelting + crafting recipes (from the
 * still-live {@link BlacksmithRecipeRegistry} reload listener) merged with the
 * inline masterpiece constants, in the same iteration order the former
 * {@code BlacksmithProductionBehavior} used (smelting ++ inline-smelting, then
 * crafting ++ inline-crafting). The merge is computed <i>at call time</i> by
 * delegating to {@link BlacksmithRecipeRegistry#INSTANCE}, so datapack reloads
 * are reflected automatically without any static-init coupling: this class's
 * {@code <clinit>} never touches the registry (the delegation lives in a method,
 * not the static {@code BY_SKILL} builder), and the registry's {@code <clinit>}
 * never touches this class — no init cycle. The registry remains the JSON data
 * source / reload listener; {@code getData()} consumers are untouched.</p>
 *
 * <p>Not (yet) here: {@code ProfessionSupplyChain} (item buy/sell routing) is a
 * separate concern left untouched, a flagged future fold.</p>
 */
public final class SkillRecipes {

    private SkillRecipes() {}

    // =========================================================================
    // Named recipes — referenced individually by behaviors / homesteads.
    // Values copied verbatim from the former profession-class constants.
    // =========================================================================

    // ── BAKING / PASTRY (BakerProductionBehavior) ────────────────────────────
    public static final ProductionRecipe FLOUR_TO_BREAD =
            ProductionRecipe.of(ModItems.WHEAT_FLOUR.get(), 1, Items.BREAD, 1, 60);
    public static final ProductionRecipe WHEAT_TO_BREAD =
            ProductionRecipe.of(Items.WHEAT, 3, Items.BREAD, 1, 200);
    public static final ProductionRecipe MAKE_COOKIE =
            ProductionRecipe.of(Map.of(Items.WHEAT, 2, Items.COCOA_BEANS, 1),
                    Items.COOKIE, 8, 400)
                .withSkillRequirement(Skill.BAKING, 30);
    public static final ProductionRecipe MAKE_PUMPKIN_PIE =
            ProductionRecipe.of(Map.of(Items.PUMPKIN, 1, Items.SUGAR, 1, Items.EGG, 1),
                    Items.PUMPKIN_PIE, 1, 600)
                .withSkillRequirement(Skill.PASTRY, 15);
    public static final ProductionRecipe MAKE_CAKE =
            ProductionRecipe.of(Map.of(Items.WHEAT, 3, Items.SUGAR, 2,
                            Items.EGG, 1, Items.MILK_BUCKET, 3),
                    Items.CAKE, 1, 900)
                .withSkillRequirement(Skill.PASTRY, 40);

    // ── MILLING (MillerProductionBehavior) ───────────────────────────────────
    public static final ProductionRecipe GRIND_WHEAT =
            ProductionRecipe.of(Items.WHEAT, 2, ModItems.WHEAT_FLOUR.get(), 3, 80);
    public static final ProductionRecipe GRIND_BONES =
            ProductionRecipe.of(Items.BONE, 1, Items.BONE_MEAL, 3, 60);
    public static final ProductionRecipe PROCESS_SUGAR_CANE =
            ProductionRecipe.of(Items.SUGAR_CANE, 1, Items.SUGAR, 1, 100);

    // ── CANDLEMAKING (CandlemakerProductionBehavior) ─────────────────────────
    public static final ProductionRecipe MAKE_CANDLE = ProductionRecipe.of(
            Map.of(Items.HONEYCOMB, 1, Items.STRING, 1),
            Items.CANDLE, 1, 60);
    public static final ProductionRecipe MAKE_TORCH = ProductionRecipe.of(
            Map.of(Items.STICK, 1, Items.COAL, 1),
            Items.TORCH, 4, 40);
    public static final ProductionRecipe MAKE_LANTERN = ProductionRecipe.of(
            Map.of(Items.IRON_NUGGET, 8, Items.TORCH, 1),
            Items.LANTERN, 1, 100)
            .withSkillRequirement(Skill.CANDLEMAKING, 50);

    // ── Monastic crafts (R6b) — produced by a monk in the MONASTERY context ──
    // (the MonkProductionBehavior); keyed by the owning skill like every other
    // recipe. All use existing items + skills; mead/BREWING is deferred (no
    // speculative Skill enum value this phase).
    /** BEEKEEPING — bottle honey at the apiary (mirrors the beekeeper's
     *  glass_bottle → honey_bottle harvest). */
    public static final ProductionRecipe HARVEST_HONEY =
            ProductionRecipe.of(Items.GLASS_BOTTLE, 1, Items.HONEY_BOTTLE, 1, 100);
    /** LITERACY — copy a manuscript (codex) at the lectern. Output is a plain
     *  BOOK (no NBT pitfalls; an illuminated/written variant is a later pass). */
    public static final ProductionRecipe COPY_MANUSCRIPT =
            ProductionRecipe.of(Items.PAPER, 3, Items.BOOK, 1, 200);
    /** VILLAGE_MEDICINE — brew a herbal tonic at the brewing stand. */
    public static final ProductionRecipe BREW_TONIC = ProductionRecipe.of(
            Map.of(Items.GLOW_BERRIES, 1, Items.BROWN_MUSHROOM, 1),
            Items.SUSPICIOUS_STEW, 1, 120);

    // ── CARPENTRY intermediates ───────────────────────────────────────────────
    /**
     * PB4 — planks-to-sticks intermediate, matching vanilla ratio (2 planks → 4
     * sticks). Keyed to OAK_PLANKS so the diamond-sword chain resolves cleanly
     * (logs→oak_planks→sticks→diamond_sword). No skill gate: stick-cutting is
     * entry-level carpentry work.
     *
     * <p>This recipe is the craftable link that terminates the stick dependency
     * of MAKE_DIAMOND_SWORD / MAKE_DIAMOND_PICKAXE in the PB4 DAG resolver.
     * OAK_LOG (raw leaf) → OAK_PLANKS (crafted by log recipe above) →
     * STICK (this) → diamond_sword output. The log recipe is already present;
     * diamonds are raw leaves consumed from business storage.</p>
     */
    public static final ProductionRecipe PLANKS_TO_STICK =
            ProductionRecipe.of(Items.OAK_PLANKS, 2, Items.STICK, 4, 40);

    // ── WEAVING (WeaverProductionBehavior) ───────────────────────────────────
    public static final ProductionRecipe SPIN_STRING =
            ProductionRecipe.of(Items.STRING, 4, Items.WHITE_WOOL, 1, 60);

    // ── BLACKSMITHING children — inline masterpieces (BlacksmithProductionBehavior) ──
    public static final ProductionRecipe MAKE_DIAMOND_PICKAXE = ProductionRecipe.of(
            Map.of(Items.DIAMOND, 3, Items.STICK, 2),
            Items.DIAMOND_PICKAXE, 1, 600)
            .withSkillRequirement(Skill.TOOLSMITHING, 50);
    public static final ProductionRecipe MAKE_DIAMOND_SWORD = ProductionRecipe.of(
            Map.of(Items.DIAMOND, 2, Items.STICK, 1),
            Items.DIAMOND_SWORD, 1, 500)
            .withSkillRequirement(Skill.WEAPONSMITHING, 50);
    public static final ProductionRecipe MAKE_NETHERITE_INGOT = ProductionRecipe.of(
            Map.of(Items.NETHERITE_SCRAP, 4, Items.GOLD_INGOT, 4),
            Items.NETHERITE_INGOT, 1, 1200)
            .withSkillRequirement(Skill.BLACKSMITHING, 65);

    // =========================================================================
    // Verbatim-moved list builders (order-sensitive — selection tie-breaks on
    // first-encountered, so the iteration order is part of the behavior).
    // =========================================================================

    // ── Weaving ──────────────────────────────────────────────────────────────
    private static ProductionRecipe weave(Item input, int inputCount,
                                          Item output, int outputCount,
                                          int ticks, int minSkill) {
        ProductionRecipe r = ProductionRecipe.of(input, inputCount, output, outputCount, ticks);
        return minSkill > 0 ? r.withSkillRequirement(Skill.WEAVING, minSkill) : r;
    }

    private static List<ProductionRecipe> buildWeavingRecipes() {
        List<ProductionRecipe> r = new ArrayList<>();

        // String → white wool (spinning). Entry-level fiber work.
        r.add(SPIN_STRING);

        // Tier 1: basic colors — primary palette + black/white.
        Map.of(Items.WHITE_WOOL,  Items.WHITE_CARPET,
                Items.RED_WOOL,    Items.RED_CARPET,
                Items.YELLOW_WOOL, Items.YELLOW_CARPET,
                Items.BLUE_WOOL,   Items.BLUE_CARPET)
                .forEach((w, c) -> r.add(weave(w, 2, c, 3, 50, 0)));

        // Tier 2: common — secondary colors.
        Map.of(Items.ORANGE_WOOL,     Items.ORANGE_CARPET,
                Items.GREEN_WOOL,      Items.GREEN_CARPET,
                Items.PINK_WOOL,       Items.PINK_CARPET,
                Items.LIGHT_BLUE_WOOL, Items.LIGHT_BLUE_CARPET)
                .forEach((w, c) -> r.add(weave(w, 2, c, 3, 50, 15)));

        // Tier 3: skilled — tertiary colors.
        Map.of(Items.CYAN_WOOL,    Items.CYAN_CARPET,
                Items.MAGENTA_WOOL, Items.MAGENTA_CARPET,
                Items.PURPLE_WOOL,  Items.PURPLE_CARPET,
                Items.LIME_WOOL,    Items.LIME_CARPET,
                Items.BROWN_WOOL,   Items.BROWN_CARPET)
                .forEach((w, c) -> r.add(weave(w, 2, c, 3, 50, 30)));

        // Tier 4: master — grayscale + black.
        Map.of(Items.LIGHT_GRAY_WOOL, Items.LIGHT_GRAY_CARPET,
                Items.GRAY_WOOL,       Items.GRAY_CARPET,
                Items.BLACK_WOOL,      Items.BLACK_CARPET)
                .forEach((w, c) -> r.add(weave(w, 2, c, 3, 50, 40)));

        // WHITE_BANNER masterpiece — 6 white_wool + 1 stick → 1 banner.
        r.add(ProductionRecipe.of(
                Map.of(Items.WHITE_WOOL, 6, Items.STICK, 1),
                Items.WHITE_BANNER, 1, 80)
                .withSkillRequirement(Skill.WEAVING, 50));

        return Collections.unmodifiableList(r);
    }

    // ── Carpentry ────────────────────────────────────────────────────────────
    private static final int CARPENTRY_CRAFT_TICKS = 60;

    private static ProductionRecipe craft(Item input, int inputCount,
                                          Item output, int outputCount,
                                          int minSkill) {
        ProductionRecipe r = ProductionRecipe.of(input, inputCount, output, outputCount,
                CARPENTRY_CRAFT_TICKS);
        return minSkill > 0 ? r.withSkillRequirement(Skill.CARPENTRY, minSkill) : r;
    }

    private static ProductionRecipe craft(Item input, int inputCount,
                                          Item output, int outputCount,
                                          int ticks, int minSkill) {
        ProductionRecipe r = ProductionRecipe.of(input, inputCount, output, outputCount, ticks);
        return minSkill > 0 ? r.withSkillRequirement(Skill.CARPENTRY, minSkill) : r;
    }

    private static List<ProductionRecipe> buildLogRecipes() {
        List<ProductionRecipe> r = new ArrayList<>();
        Map.of(Items.OAK_LOG,      Items.OAK_PLANKS,
                        Items.SPRUCE_LOG,   Items.SPRUCE_PLANKS,
                        Items.BIRCH_LOG,    Items.BIRCH_PLANKS,
                        Items.JUNGLE_LOG,   Items.JUNGLE_PLANKS,
                        Items.ACACIA_LOG,   Items.ACACIA_PLANKS,
                        Items.DARK_OAK_LOG, Items.DARK_OAK_PLANKS,
                        Items.MANGROVE_LOG, Items.MANGROVE_PLANKS,
                        Items.CHERRY_LOG,   Items.CHERRY_PLANKS)
                .forEach((log, planks) -> r.add(craft(log, 1, planks, 4, 0)));
        return r;
    }

    private static List<ProductionRecipe> buildPlankRecipes() {
        List<ProductionRecipe> r = new ArrayList<>();
        Map.of(Items.OAK_PLANKS, Items.OAK_SLAB, Items.SPRUCE_PLANKS, Items.SPRUCE_SLAB,
                        Items.BIRCH_PLANKS, Items.BIRCH_SLAB, Items.JUNGLE_PLANKS, Items.JUNGLE_SLAB,
                        Items.ACACIA_PLANKS, Items.ACACIA_SLAB, Items.DARK_OAK_PLANKS, Items.DARK_OAK_SLAB)
                .forEach((p, s) -> r.add(craft(p, 3, s, 6, 0)));
        Map.of(Items.OAK_PLANKS, Items.OAK_STAIRS, Items.SPRUCE_PLANKS, Items.SPRUCE_STAIRS,
                        Items.BIRCH_PLANKS, Items.BIRCH_STAIRS)
                .forEach((p, s) -> r.add(craft(p, 6, s, 4, 0)));
        Map.of(Items.OAK_PLANKS, Items.OAK_DOOR, Items.SPRUCE_PLANKS, Items.SPRUCE_DOOR,
                        Items.BIRCH_PLANKS, Items.BIRCH_DOOR)
                .forEach((p, d) -> r.add(craft(p, 6, d, 3, 15)));
        Map.of(Items.OAK_PLANKS, Items.OAK_FENCE, Items.SPRUCE_PLANKS, Items.SPRUCE_FENCE,
                        Items.BIRCH_PLANKS, Items.BIRCH_FENCE)
                .forEach((p, f) -> r.add(craft(p, 6, f, 3, 15)));
        r.add(craft(Items.OAK_PLANKS,    6, Items.OAK_FENCE_GATE,    1, 15));
        r.add(craft(Items.SPRUCE_PLANKS, 6, Items.SPRUCE_FENCE_GATE, 1, 15));
        r.add(craft(Items.OAK_PLANKS, 8, Items.CHEST,          1, 30));
        r.add(craft(Items.OAK_PLANKS, 6, Items.BARREL,         1, 30));
        r.add(craft(Items.OAK_PLANKS, 4, Items.CRAFTING_TABLE, 1, 30));
        r.add(craft(Items.OAK_PLANKS, 6, Items.BOOKSHELF,      1, 30));
        r.add(ProductionRecipe.of(
                Map.of(Items.OAK_SLAB, 6, Items.BOOK, 3),
                Items.CHISELED_BOOKSHELF, 1, 80)
                .withSkillRequirement(Skill.CARPENTRY, 50));
        return r;
    }

    private static List<ProductionRecipe> buildCarpentryRecipes() {
        // CARPENTRY bucket = log recipes ++ plank recipes (the order
        // CarpenterProductionBehavior.allRecipes() iterated).
        List<ProductionRecipe> all = new ArrayList<>();
        all.addAll(buildLogRecipes());
        all.addAll(buildPlankRecipes());
        return Collections.unmodifiableList(all);
    }

    // ── Masonry ──────────────────────────────────────────────────────────────
    private static ProductionRecipe mason(Item input, int inputCount,
                                          Item output, int outputCount,
                                          int ticks, int minSkill) {
        ProductionRecipe r = ProductionRecipe.of(input, inputCount, output, outputCount, ticks);
        return minSkill > 0 ? r.withSkillRequirement(Skill.MASONRY, minSkill) : r;
    }

    private static List<ProductionRecipe> buildMasonryRecipes() {
        List<ProductionRecipe> r = new ArrayList<>();
        r.add(mason(Items.STONE, 1,  Items.STONE_BRICKS,          1, 40, 0));
        r.add(mason(Items.STONE, 1,  Items.STONE_BRICK_SLAB,      2, 40, 0));
        r.add(mason(Items.STONE, 1,  Items.STONE_BRICK_STAIRS,    1, 40, 0));
        r.add(mason(Items.STONE, 1,  Items.STONE_BRICK_WALL,      1, 40, 0));
        r.add(mason(Items.STONE, 1,  Items.CHISELED_STONE_BRICKS, 1, 80, 50));
        r.add(mason(Items.COBBLESTONE, 1, Items.COBBLESTONE_SLAB,   2, 40, 0));
        r.add(mason(Items.COBBLESTONE, 1, Items.COBBLESTONE_STAIRS, 1, 40, 0));
        r.add(mason(Items.COBBLESTONE, 1, Items.COBBLESTONE_WALL,   1, 40, 0));
        r.add(mason(Items.SMOOTH_STONE, 1, Items.SMOOTH_STONE_SLAB, 2, 40, 15));
        r.add(mason(Items.ANDESITE, 2, Items.POLISHED_ANDESITE, 2, 40, 15));
        r.add(mason(Items.GRANITE,  2, Items.POLISHED_GRANITE,  2, 40, 15));
        r.add(mason(Items.DIORITE,  2, Items.POLISHED_DIORITE,  2, 40, 15));
        return Collections.unmodifiableList(r);
    }

    // =========================================================================
    // Per-skill registry — keyed by owning skill, in profession-iteration order.
    // Declared last so all named constants above are initialised first.
    // =========================================================================

    private static final Map<Skill, List<ProductionRecipe>> BY_SKILL = buildRegistry();

    private static Map<Skill, List<ProductionRecipe>> buildRegistry() {
        Map<Skill, List<ProductionRecipe>> m = new EnumMap<>(Skill.class);
        // Baker draws PASTRY then BAKING (RECIPE_PRIORITY = cake, pie, cookie,
        // flour→bread, wheat→bread).
        m.put(Skill.PASTRY,       List.of(MAKE_CAKE, MAKE_PUMPKIN_PIE));
        m.put(Skill.BAKING,       List.of(MAKE_COOKIE, FLOUR_TO_BREAD, WHEAT_TO_BREAD));
        m.put(Skill.MILLING,      List.of(GRIND_WHEAT, GRIND_BONES, PROCESS_SUGAR_CANE));
        m.put(Skill.CANDLEMAKING, List.of(MAKE_CANDLE, MAKE_TORCH, MAKE_LANTERN));
        // R6b monastic-craft buckets (existing skills).
        m.put(Skill.BEEKEEPING,      List.of(HARVEST_HONEY));
        m.put(Skill.LITERACY,        List.of(COPY_MANUSCRIPT));
        m.put(Skill.VILLAGE_MEDICINE, List.of(BREW_TONIC));
        m.put(Skill.WEAVING,      buildWeavingRecipes());
        m.put(Skill.CARPENTRY,    buildCarpentryRecipes());
        m.put(Skill.MASONRY,      buildMasonryRecipes());
        // Blacksmith inline masterpieces. E-S4a — the JSON-loaded smelting /
        // crafting recipes are NOT baked into BY_SKILL (they are dynamic /
        // reload-driven); forSkill(BLACKSMITHING) merges them in at call time
        // (see blacksmithRecipes()). The per-child-skill buckets keep their
        // inline-masterpiece-only lists for any consumer keyed on the sub-skill.
        m.put(Skill.TOOLSMITHING,   List.of(MAKE_DIAMOND_PICKAXE));
        m.put(Skill.WEAPONSMITHING, List.of(MAKE_DIAMOND_SWORD));
        // BLACKSMITHING intentionally absent from BY_SKILL — forSkill special-
        // cases it to fold the JSON registry + inline smelting masterpiece.
        return Collections.unmodifiableMap(m);
    }

    /** The recipes owned by {@code skill}, in profession-iteration order
     *  (empty for a skill with no registered recipes).
     *
     *  <p>E-S4a — {@link Skill#BLACKSMITHING} is the parent skill that owns the
     *  whole smithing act; it is served by {@link #blacksmithRecipes()} (JSON
     *  registry ++ inline masterpieces), merged at call time so datapack reloads
     *  are reflected. Every other skill is served from the static
     *  {@link #BY_SKILL} map verbatim (unchanged).</p> */
    public static List<ProductionRecipe> forSkill(Skill skill) {
        if (skill == Skill.BLACKSMITHING) return blacksmithRecipes();
        return BY_SKILL.getOrDefault(skill, List.of());
    }

    // =========================================================================
    // PB4 — production intermediates.
    // Recipes needed for DAG-based intermediate production (e.g. planks→sticks
    // for the diamond-sword chain) that are NOT in any skill's main production
    // bucket. The DAG resolver's reverse index includes these via
    // BusinessProductionTaskSource.buildRecipeIndex(), which calls this method
    // after the primary skill scan. NPC-profession behaviors (CarpenterCrafts,
    // etc.) do NOT call this, so these recipes don't alter NPC craft-selection.
    // =========================================================================

    /**
     * Supplemental intermediate recipes used by the PB4 DAG resolver.
     * These are craftable intermediate products not owned by any particular
     * profession skill bucket, exposed separately so NPC profession behaviors
     * (which read {@link #forSkill}) are unaffected.
     *
     * <p>Current intermediates:</p>
     * <ul>
     *   <li>{@link #PLANKS_TO_STICK} — 2 oak_planks → 4 sticks (vanilla ratio);
     *       terminates the stick dependency in WEAPONSMITHING / TOOLSMITHING
     *       production chains.</li>
     * </ul>
     */
    public static List<ProductionRecipe> productionIntermediates() {
        return List.of(PLANKS_TO_STICK);
    }

    // =========================================================================
    // E-S4a — BLACKSMITH JSON fold. The registry stays the JSON data source /
    // reload listener; these read its live data at call time and append the
    // inline masterpiece constants, preserving the former class's bins + order.
    // =========================================================================

    /** Inline crafting masterpieces appended after the JSON-loaded crafting
     *  recipes (former {@code BlacksmithProductionBehavior.INLINE_CRAFTING_RECIPES}). */
    private static final List<ProductionRecipe> INLINE_CRAFTING =
            List.of(MAKE_DIAMOND_PICKAXE, MAKE_DIAMOND_SWORD);
    /** Inline smelting masterpieces appended after the JSON-loaded smelting
     *  recipes (former {@code BlacksmithProductionBehavior.INLINE_SMELTING_RECIPES};
     *  netherite uses the furnace + coal via the smelt branch). */
    private static final List<ProductionRecipe> INLINE_SMELTING =
            List.of(MAKE_NETHERITE_INGOT);

    /** The smelting bin: JSON-loaded smelting recipes ++ inline smelting
     *  masterpieces. Reads the live registry data each call. */
    public static List<ProductionRecipe> blacksmithSmelting() {
        return concat(BlacksmithRecipeRegistry.INSTANCE.getData().getSmeltingRecipes(),
                INLINE_SMELTING);
    }

    /** The crafting bin: JSON-loaded crafting recipes ++ inline crafting
     *  masterpieces. Reads the live registry data each call. */
    public static List<ProductionRecipe> blacksmithCrafting() {
        return concat(BlacksmithRecipeRegistry.INSTANCE.getData().getCraftingRecipes(),
                INLINE_CRAFTING);
    }

    /** The full BLACKSMITHING production act, in the former class's iteration
     *  order: smelting bin then crafting bin. Returned by
     *  {@code forSkill(BLACKSMITHING)}. */
    public static List<ProductionRecipe> blacksmithRecipes() {
        List<ProductionRecipe> smelting = blacksmithSmelting();
        List<ProductionRecipe> crafting = blacksmithCrafting();
        List<ProductionRecipe> all = new ArrayList<>(smelting.size() + crafting.size());
        all.addAll(smelting);
        all.addAll(crafting);
        return Collections.unmodifiableList(all);
    }

    private static List<ProductionRecipe> concat(List<ProductionRecipe> a,
                                                 List<ProductionRecipe> b) {
        if (b.isEmpty()) return a;
        List<ProductionRecipe> out = new ArrayList<>(a.size() + b.size());
        out.addAll(a);
        out.addAll(b);
        return Collections.unmodifiableList(out);
    }
}
