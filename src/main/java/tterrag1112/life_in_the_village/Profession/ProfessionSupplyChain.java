package tterrag1112.life_in_the_village.Profession;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import tterrag1112.life_in_the_village.Items.ModItems;

import java.util.*;

/**
 * Central registry of what each profession produces and consumes.
 *
 * <h3>Purpose</h3>
 * Each NPC goal class previously defined its inputs and outputs inline.
 * This caused the production chain to be implicit and scattered — adding a
 * new profession or changing a recipe required finding every goal file that
 * referenced the affected item.
 *
 * <p>{@code ProfessionSupplyChain} is the single source of truth. Goal classes
 * query it in their {@code resourcesToBuy()} and {@code sellableOutputs()}
 * overrides. The village economy and needs calculator use it to understand
 * what the village can produce and what it is missing.</p>
 *
 * <h3>Design</h3>
 * <ul>
 *   <li>{@link #getOutputs} — items this profession typically sells to the economy</li>
 *   <li>{@link #getInputs} — items this profession typically buys from the economy</li>
 *   <li>{@link #findProducersOf} — which professions can supply a given item</li>
 *   <li>{@link #findConsumersOf} — which professions need a given item</li>
 * </ul>
 *
 * <h3>Extending</h3>
 * When adding a new profession, add two entries to the maps at the bottom of
 * this file — one for outputs, one for inputs. No other file needs to change
 * for the economy and needs calculator to pick up the new profession.
 */
public final class ProfessionSupplyChain {

    private ProfessionSupplyChain() {}

    // =========================================================================
    // Registry maps — keyed by Profession enum constant
    // =========================================================================

    private static final Map<Profession, List<Item>> OUTPUTS;
    private static final Map<Profession, List<Item>> INPUTS;

    static {
        Map<Profession, List<Item>> out = new EnumMap<>(Profession.class);
        Map<Profession, List<Item>> in  = new EnumMap<>(Profession.class);

        // ── FARMER ────────────────────────────────────────────────────────────
        // Produces crops and basic processed food.
        out.put(Profession.FARMER, List.of(
                Items.WHEAT, Items.POTATO, Items.CARROT, Items.BEETROOT,
                Items.PUMPKIN, Items.MELON, Items.BREAD, Items.APPLE,
                Items.EGG, Items.FEATHER, Items.LEATHER));
        // Farmers buy seeds from the market if they run out of their own stock.
        in.put(Profession.FARMER, List.of(
                Items.WHEAT_SEEDS, Items.POTATO, Items.CARROT,
                Items.BEETROOT_SEEDS, Items.PUMPKIN_SEEDS, Items.MELON_SEEDS));

        // ── MILLER ────────────────────────────────────────────────────────────
        // Phase 6.3.4.9 — MILLER is the village's general grinder.
        // wheat → flour (primary chain to BAKER),
        // bones → bone_meal (FARMER's composting / FERTILIZER chain),
        // sugar_cane → sugar (dormant until a sugar_cane producer
        // exists in the village; needed by BAKER for cake/pumpkin_pie).
        out.put(Profession.MILLER, List.of(
                ModItems.WHEAT_FLOUR.get(), Items.BONE_MEAL, Items.SUGAR));
        in.put(Profession.MILLER, List.of(
                Items.WHEAT, Items.BONE, Items.SUGAR_CANE));

        // ── BAKER ─────────────────────────────────────────────────────────────
        // Phase 6.3.4.4.4 — WHEAT_FLOUR is the primary recipe input
        // (FLOUR_TO_BREAD; 1 flour → 1 bread, 60t). WHEAT is the
        // fallback (WHEAT_TO_BREAD; 3 wheat → 1 bread, 200t) used when
        // no MILLER exists in the village. Order matters: the supply
        // chain's consumer lookup walks inputs in declaration order, so
        // WHEAT_FLOUR first lets baker prefer the efficient pathway.
        out.put(Profession.BAKER, List.of(
                Items.BREAD, Items.COOKIE, Items.PUMPKIN_PIE, Items.CAKE));
        in.put(Profession.BAKER, List.of(
                ModItems.WHEAT_FLOUR.get(), Items.WHEAT,
                Items.EGG, Items.SUGAR, Items.PUMPKIN));

        // ── BLACKSMITH ────────────────────────────────────────────────────────
        out.put(Profession.BLACKSMITH, List.of(
                Items.IRON_INGOT, Items.GOLD_INGOT,
                Items.IRON_SWORD, Items.IRON_AXE, Items.IRON_PICKAXE,
                Items.IRON_SHOVEL, Items.IRON_HOE,
                Items.IRON_HELMET, Items.IRON_CHESTPLATE,
                Items.IRON_LEGGINGS, Items.IRON_BOOTS,
                Items.BUCKET, Items.SHEARS, Items.FLINT_AND_STEEL));
        in.put(Profession.BLACKSMITH, List.of(
                Items.RAW_IRON, Items.RAW_GOLD, Items.COAL, Items.CHARCOAL,
                Items.FLINT, Items.STICK));

        // ── CARPENTER ─────────────────────────────────────────────────────────
        out.put(Profession.CARPENTER, List.of(
                Items.OAK_PLANKS, Items.SPRUCE_PLANKS, Items.BIRCH_PLANKS,
                Items.OAK_SLAB, Items.OAK_STAIRS,
                Items.CHEST, Items.BARREL, Items.CRAFTING_TABLE,
                Items.OAK_DOOR, Items.SPRUCE_DOOR,
                Items.OAK_FENCE, Items.OAK_FENCE_GATE,
                Items.BOOKSHELF, Items.LADDER, Items.STICK));
        in.put(Profession.CARPENTER, List.of(
                Items.OAK_LOG, Items.SPRUCE_LOG, Items.BIRCH_LOG,
                Items.DARK_OAK_LOG, Items.ACACIA_LOG));

        // ── STONEMASON ────────────────────────────────────────────────────────
        out.put(Profession.STONEMASON, List.of(
                Items.STONE_BRICKS, Items.STONE_BRICK_SLAB,
                Items.STONE_BRICK_STAIRS, Items.STONE_BRICK_WALL,
                Items.COBBLESTONE_SLAB, Items.CHISELED_STONE_BRICKS,
                Items.SMOOTH_STONE, Items.SMOOTH_STONE_SLAB));
        in.put(Profession.STONEMASON, List.of(
                Items.COBBLESTONE, Items.STONE, Items.DEEPSLATE));

        // ── MINER ─────────────────────────────────────────────────────────────
        out.put(Profession.MINER, List.of(
                Items.RAW_IRON, Items.RAW_GOLD, Items.RAW_COPPER,
                Items.COAL, Items.REDSTONE, Items.LAPIS_LAZULI,
                Items.COBBLESTONE, Items.GRAVEL, Items.FLINT));
        in.put(Profession.MINER, List.of(
                Items.OAK_LOG,      // pit props / torches
                Items.TORCH,
                Items.IRON_PICKAXE, Items.IRON_SHOVEL));

        // ── WEAVER ────────────────────────────────────────────────────────────
        out.put(Profession.WEAVER, List.of(
                Items.WHITE_CARPET, Items.WHITE_WOOL,
                Items.WHITE_BANNER, Items.STRING));
        in.put(Profession.WEAVER, List.of(Items.WHITE_WOOL, Items.STRING));

        // ── CANDLEMAKER ───────────────────────────────────────────────────────
        out.put(Profession.CANDLEMAKER, List.of(
                Items.CANDLE, Items.WHITE_CANDLE,
                Items.ORANGE_CANDLE, Items.TORCH));
        in.put(Profession.CANDLEMAKER, List.of(
                Items.HONEYCOMB, Items.STRING, Items.STICK, Items.COAL));

        // ── MERCHANT ─────────────────────────────────────────────────────────
        // Merchants buy diverse goods and sell them on; exact lists are dynamic
        // but their core trade goods are books, paper, lanterns, and maps.
        out.put(Profession.MERCHANT, List.of(
                Items.PAPER, Items.BOOK, Items.MAP,
                Items.LANTERN, Items.GLASS_BOTTLE, Items.CLOCK,
                Items.COMPASS, Items.GOLD_INGOT));
        in.put(Profession.MERCHANT, List.of(
                Items.PAPER, Items.BOOK, Items.GLASS,
                Items.GOLD_INGOT, Items.EMERALD, Items.WHEAT));

        // ── GUARD ─────────────────────────────────────────────────────────────
        out.put(Profession.GUARD, List.of(
                Items.IRON_SWORD, Items.SHIELD, Items.ARROW,
                Items.CROSSBOW, Items.BOW));
        in.put(Profession.GUARD, List.of(
                Items.IRON_INGOT, Items.STICK, Items.FLINT,
                Items.FEATHER, Items.STRING, Items.OAK_PLANKS));

        // ── INNKEEPER ────────────────────────────────────────────────────────
        out.put(Profession.INNKEEPER, List.of(
                Items.BREAD, Items.COOKED_PORKCHOP, Items.COOKED_BEEF,
                Items.APPLE, Items.COOKIE, Items.MUSHROOM_STEW));
        in.put(Profession.INNKEEPER, List.of(
                Items.WHEAT, Items.PORKCHOP, Items.BEEF, Items.POTATO,
                Items.CARROT, Items.APPLE, Items.BROWN_MUSHROOM, Items.BOWL));

        // ── PRIEST ───────────────────────────────────────────────────────────
        out.put(Profession.PRIEST, List.of(
                Items.WHITE_CANDLE, Items.BOOK,
                Items.GOLDEN_APPLE, Items.EXPERIENCE_BOTTLE));
        in.put(Profession.PRIEST, List.of(
                Items.WHITE_CANDLE, Items.PAPER, Items.GOLD_INGOT));

        // ── SCHOLAR ───────────────────────────────────────────────────────────
        out.put(Profession.SCHOLAR, List.of(
                Items.BOOK, Items.WRITABLE_BOOK,
                Items.ENCHANTED_BOOK, Items.MAP));
        in.put(Profession.SCHOLAR, List.of(
                Items.PAPER, Items.BOOK, Items.INK_SAC, Items.FEATHER));

        OUTPUTS = Collections.unmodifiableMap(out);
        INPUTS  = Collections.unmodifiableMap(in);
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Items this profession produces and sells to the economy.
     * Returns an empty list for professions without production (NONE, CITIZEN, etc.).
     */
    public static List<Item> getOutputs(Profession profession) {
        return OUTPUTS.getOrDefault(profession, List.of());
    }

    /**
     * Items this profession needs to buy from the economy to operate.
     * Returns an empty list for self-sufficient professions.
     */
    public static List<Item> getInputs(Profession profession) {
        return INPUTS.getOrDefault(profession, List.of());
    }

    /**
     * Returns the set of professions that produce the given item.
     * Useful for the economy to route crafting orders to capable producers.
     */
    public static Set<Profession> findProducersOf(Item item) {
        Set<Profession> result = EnumSet.noneOf(Profession.class);
        OUTPUTS.forEach((prof, items) -> {
            if (items.contains(item)) result.add(prof);
        });
        return Collections.unmodifiableSet(result);
    }

    /**
     * Returns the set of professions that consume the given item as an input.
     * Useful for prioritising crafting orders when the village has a shortage.
     */
    public static Set<Profession> findConsumersOf(Item item) {
        Set<Profession> result = EnumSet.noneOf(Profession.class);
        INPUTS.forEach((prof, items) -> {
            if (items.contains(item)) result.add(prof);
        });
        return Collections.unmodifiableSet(result);
    }

    /**
     * True if the given profession is a pure consumer — it buys inputs from
     * the economy but its outputs are not economically traded goods (e.g.
     * services like guards, priests).
     */
    public static boolean isPureConsumer(Profession profession) {
        return getOutputs(profession).isEmpty() && !getInputs(profession).isEmpty();
    }

    /**
     * True if the given profession participates in at least one side of the
     * economy (has registered inputs or outputs).
     */
    public static boolean isEconomicParticipant(Profession profession) {
        return !getOutputs(profession).isEmpty() || !getInputs(profession).isEmpty();
    }
}
