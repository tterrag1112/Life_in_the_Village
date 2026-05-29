package tterrag1112.life_in_the_village.Village.Simulation;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.Set;

/**
 * Canonical {@code Item → }{@link ResourceCategory} classifier (economy
 * Phase 1c). The single source of truth for "what kind of good is this
 * item," consolidating the heuristics that used to live privately in
 * {@code DynamicPriceCalculator} (food/material) and
 * {@code VillageNeedsCalculator} (the building-material item set).
 *
 * <h3>Mechanism</h3>
 * Code-based for 1c — Minecraft item tags + the {@code FOOD} data
 * component + path-substring fallbacks, ordered most-specific first.
 * Externalising this to data (tags/JSON) is deferred to 1d; the static
 * surface here is what 1d will swap out.
 *
 * <h3>Contract</h3>
 * <ul>
 *   <li>{@link #classify} returns {@code null} for an unclassified item —
 *       the "neutral" / UNKNOWN case. Callers treat {@code null} as
 *       no-category (no per-village modifier, no need bump).</li>
 *   <li>{@code FOOD} is exactly "has the {@code FOOD} component", matching
 *       the legacy {@code isFoodItem} so food pricing/needs are
 *       unchanged.</li>
 *   <li>{@code BUILDING_MATERIALS} is a <em>superset</em> of the legacy
 *       material heuristics (path substrings) and the legacy explicit
 *       {@link #LEGACY_BUILDING_MATERIALS} set, so every item the old
 *       {@code VillageNeedsCalculator} counted still classifies as a
 *       material (its need output is unchanged).</li>
 * </ul>
 *
 * <p>Never returns {@link ResourceCategory#COIN_INFLUX} — that is a
 * sim-side metric, not a tradeable item.</p>
 */
public final class ItemResourceClassifier {

    private ItemResourceClassifier() {}

    /**
     * The legacy {@code VillageNeedsCalculator.BUILDING_MATERIAL_ITEMS}
     * set, moved here as the canonical material membership. Items not
     * caught by tags/path (notably GRAVEL / SAND) are pinned by this set
     * so the needs refactor is behaviour-preserving.
     */
    private static final Set<Item> LEGACY_BUILDING_MATERIALS = Set.of(
            Items.OAK_LOG,     Items.SPRUCE_LOG,    Items.BIRCH_LOG,
            Items.OAK_PLANKS,  Items.SPRUCE_PLANKS, Items.BIRCH_PLANKS,
            Items.COBBLESTONE, Items.STONE,          Items.STONE_BRICKS,
            Items.OAK_SLAB,    Items.SPRUCE_SLAB,
            Items.GLASS,       Items.GLASS_PANE,
            Items.IRON_INGOT,  Items.GRAVEL,         Items.SAND
    );

    private static final Set<Item> LUXURY_GOODS = Set.of(
            Items.DIAMOND, Items.EMERALD, Items.NETHERITE_INGOT,
            Items.AMETHYST_SHARD, Items.QUARTZ
    );

    /**
     * Resolves an item to its resource category, or {@code null} when it
     * matches no category (neutral). Order is most-specific first so e.g.
     * a stone pickaxe classifies as {@code TOOLS}, not a building material.
     */
    public static ResourceCategory classify(Item item) {
        if (item == null) return null;
        String path = BuiltInRegistries.ITEM.getKey(item).getPath();

        // SEEDS — planting stock (wheat/beetroot/pumpkin/melon/… _seeds).
        if (path.contains("seed")) return ResourceCategory.SEEDS;

        // FOOD — exact legacy semantics: has the FOOD component.
        if (item.components().has(DataComponents.FOOD)) return ResourceCategory.FOOD;

        // WEAPONS — swords, ranged, arrows, armour.
        if (is(item, ItemTags.SWORDS) || is(item, ItemTags.ARROWS)
                || item == Items.BOW || item == Items.CROSSBOW || item == Items.TRIDENT
                || isArmour(path)) {
            return ResourceCategory.WEAPONS;
        }

        // TOOLS — digging/harvesting implements.
        if (is(item, ItemTags.AXES) || is(item, ItemTags.PICKAXES)
                || is(item, ItemTags.SHOVELS) || is(item, ItemTags.HOES)
                || item == Items.SHEARS || item == Items.FISHING_ROD
                || item == Items.FLINT_AND_STEEL) {
            return ResourceCategory.TOOLS;
        }

        // CLOTH — fabric, leather, wool.
        if (is(item, ItemTags.WOOL) || is(item, ItemTags.WOOL_CARPETS)
                || item == Items.STRING || item == Items.LEATHER) {
            return ResourceCategory.CLOTH;
        }

        // PAPER — scribal goods.
        if (item == Items.PAPER || item == Items.BOOK || item == Items.WRITABLE_BOOK
                || item == Items.WRITTEN_BOOK || path.contains("map")) {
            return ResourceCategory.PAPER;
        }

        // LITURGICAL — candles, sacred items.
        if (path.contains("candle") || item == Items.TOTEM_OF_UNDYING) {
            return ResourceCategory.LITURGICAL;
        }

        // MEDICINE — potions / remedies.
        if (path.contains("potion")) return ResourceCategory.MEDICINE;

        // LUXURY — gems and fine goods.
        if (LUXURY_GOODS.contains(item)) return ResourceCategory.LUXURY;

        // BUILDING_MATERIALS — superset of the legacy heuristics.
        if (isBuildingMaterial(item, path)) return ResourceCategory.BUILDING_MATERIALS;

        // Unclassified — neutral.
        return null;
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static boolean isBuildingMaterial(Item item, String path) {
        if (is(item, ItemTags.LOGS) || is(item, ItemTags.PLANKS)) return true;
        if (LEGACY_BUILDING_MATERIALS.contains(item)) return true;
        return path.contains("log")    || path.contains("planks")
                || path.contains("stone") || path.contains("cobblestone")
                || path.contains("ingot") || path.contains("glass")
                || path.contains("brick");
    }

    private static boolean isArmour(String path) {
        return path.endsWith("helmet") || path.endsWith("chestplate")
                || path.endsWith("leggings") || path.endsWith("boots");
    }

    private static boolean is(Item item, TagKey<Item> tag) {
        return item.getDefaultInstance().is(tag);
    }
}
