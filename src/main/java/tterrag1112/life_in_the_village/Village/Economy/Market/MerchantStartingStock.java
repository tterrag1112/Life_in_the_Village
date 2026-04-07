package tterrag1112.life_in_the_village.Village.Economy.Market;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.*;

/**
 * Determines what items a newly placed market stall should start with
 * based on the buildings present in the village.
 *
 * <h3>Logic</h3>
 * Every market gets a base set of goods that any village needs to
 * function — basic food, tools, and building materials. On top of that,
 * each building type in the village contributes the items that profession
 * would naturally produce as surplus. A farming village with two
 * farmhouses starts with more wheat and bread; a village with a
 * blacksmith has iron tools available from day one.
 *
 * <h3>Quantities</h3>
 * Amounts are intentionally modest — enough to seed the economy and
 * allow early trades without removing the need for ongoing production.
 */
public final class MerchantStartingStock {

    private MerchantStartingStock() {}

    public record StockEntry(Item item, int minCount, int maxCount) {
        int roll(Random rng) {
            if (minCount >= maxCount) return minCount;
            return minCount + rng.nextInt(maxCount - minCount + 1);
        }
    }

    // ── Base stock every market starts with ───────────────────────────────────
    private static final List<StockEntry> BASE_STOCK = List.of(
            // Food basics
            new StockEntry(Items.BREAD,           8,  20),
            new StockEntry(Items.CARROT,           8,  16),
            new StockEntry(Items.POTATO,           8,  16),
            // Tools
            new StockEntry(Items.WOODEN_PICKAXE,   2,   4),
            new StockEntry(Items.WOODEN_AXE,       2,   4),
            new StockEntry(Items.WOODEN_SHOVEL,    1,   3),
            // Materials
            new StockEntry(Items.OAK_LOG,          8,  16),
            new StockEntry(Items.COBBLESTONE,     16,  32),
            // Light
            new StockEntry(Items.TORCH,           16,  32)
    );

    // ── Per-building-type surplus stock ───────────────────────────────────────
    private static final Map<BuildingType, List<StockEntry>> BUILDING_CONTRIBUTIONS =
            new EnumMap<>(BuildingType.class);

    static {
        BUILDING_CONTRIBUTIONS.put(BuildingType.FARMHOUSE, List.of(
                new StockEntry(Items.WHEAT,          16,  48),
                new StockEntry(Items.BREAD,          16,  32),
                new StockEntry(Items.CARROT,         16,  32),
                new StockEntry(Items.POTATO,         16,  32),
                new StockEntry(Items.BEETROOT,        8,  16),
                new StockEntry(Items.WHEAT_SEEDS,     8,  16),
                new StockEntry(Items.HAY_BLOCK,       4,  10)
        ));

        BUILDING_CONTRIBUTIONS.put(BuildingType.BLACKSMITH, List.of(
                new StockEntry(Items.IRON_INGOT,      8,  16),
                new StockEntry(Items.IRON_SWORD,      1,   3),
                new StockEntry(Items.IRON_PICKAXE,    1,   3),
                new StockEntry(Items.IRON_AXE,        1,   2),
                new StockEntry(Items.IRON_SHOVEL,     1,   2),
                new StockEntry(Items.IRON_HOE,        1,   2),
                new StockEntry(Items.BUCKET,          2,   4)
        ));

        BUILDING_CONTRIBUTIONS.put(BuildingType.CARPENTRY, List.of(
                new StockEntry(Items.OAK_PLANKS,     16,  32),
                new StockEntry(Items.OAK_LOG,        16,  32),
                new StockEntry(Items.CHEST,           2,   4),
                new StockEntry(Items.OAK_DOOR,        2,   4),
                new StockEntry(Items.CRAFTING_TABLE,  1,   2),
                new StockEntry(Items.STICK,          16,  32)
        ));

        BUILDING_CONTRIBUTIONS.put(BuildingType.MINE, List.of(
                new StockEntry(Items.COAL,           16,  32),
                new StockEntry(Items.RAW_IRON,        8,  16),
                new StockEntry(Items.COBBLESTONE,    32,  64),
                new StockEntry(Items.GRAVEL,         16,  32)
        ));

        BUILDING_CONTRIBUTIONS.put(BuildingType.MILLER, List.of(
                new StockEntry(Items.BREAD,          16,  32),
                new StockEntry(Items.WHEAT,          16,  32)
        ));

        BUILDING_CONTRIBUTIONS.put(BuildingType.BAKERY, List.of(
                new StockEntry(Items.BREAD,          24,  48),
                new StockEntry(Items.COOKIE,          8,  16),
                new StockEntry(Items.PUMPKIN_PIE,     4,   8)
        ));

        BUILDING_CONTRIBUTIONS.put(BuildingType.INN, List.of(
                new StockEntry(Items.COOKED_BEEF,     4,   8),
                new StockEntry(Items.COOKED_PORKCHOP, 4,   8),
                new StockEntry(Items.APPLE,           4,   8),
                new StockEntry(Items.MUSHROOM_STEW,   2,   4)
        ));

        BUILDING_CONTRIBUTIONS.put(BuildingType.STONEMASON, List.of(
                new StockEntry(Items.STONE_BRICKS,   16,  32),
                new StockEntry(Items.STONE,          16,  32),
                new StockEntry(Items.COBBLESTONE,    16,  32)
        ));

        BUILDING_CONTRIBUTIONS.put(BuildingType.WEAVER, List.of(
                new StockEntry(Items.WHITE_WOOL,      4,  12),
                new StockEntry(Items.STRING,          8,  16),
                new StockEntry(Items.WHITE_BED,       1,   3)
        ));
    }

    /**
     * Returns the stock map (item → quantity) for a market stall based on
     * which building types exist in the village.
     */
    public static Map<Item, Integer> computeStock(
            Collection<BuildingType> presentBuildings, Random rng) {

        Map<Item, Integer> result = new LinkedHashMap<>();

        // Always add base stock
        for (StockEntry entry : BASE_STOCK) {
            result.merge(entry.item(), entry.roll(rng), Integer::sum);
        }

        // Add contributions from each building type present
        for (BuildingType type : presentBuildings) {
            List<StockEntry> contributions =
                    BUILDING_CONTRIBUTIONS.get(type);
            if (contributions == null) continue;
            for (StockEntry entry : contributions) {
                result.merge(entry.item(), entry.roll(rng), Integer::sum);
            }
        }

        return result;
    }

    /**
     * Called once per stall on the first daily tick after the village loads.
     * Stocks the stall chest if it is empty, using village composition.
     */
    public static void initialStockIfNeeded(ServerLevel level,
                                            MarketStall stall,
                                            Village village,
                                            VillageSavedData data) {
        if (stall.getChestPos().equals(net.minecraft.core.BlockPos.ZERO)) return;

        net.minecraft.world.level.block.entity.BlockEntity be =
                level.getBlockEntity(stall.getChestPos());
        if (!(be instanceof net.minecraft.world.Container chest)) return;

        // Only stock if completely empty
        boolean hasItems = false;
        for (int i = 0; i < chest.getContainerSize(); i++) {
            if (!chest.getItem(i).isEmpty()) { hasItems = true; break; }
        }
        if (hasItems) return;

        Set<BuildingType> presentTypes = village.getBuildingIds().stream()
                .map(data::getBuildingById)
                .filter(java.util.Optional::isPresent)
                .map(java.util.Optional::get)
                .map(tterrag1112.life_in_the_village.Village.Building::getType)
                .collect(java.util.stream.Collectors.toSet());

        Map<net.minecraft.world.item.Item, Integer> stock =
                computeStock(presentTypes, new java.util.Random());

        stock.forEach((item, qty) -> {
            net.minecraft.world.item.ItemStack stack =
                    new net.minecraft.world.item.ItemStack(item, qty);
            for (int i = 0; i < chest.getContainerSize() && !stack.isEmpty(); i++) {
                if (chest.getItem(i).isEmpty()) {
                    chest.setItem(i, stack.copy());
                    stack.setCount(0);
                }
            }
        });
    }
}