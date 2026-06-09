// New file: src/main/java/tterrag1112/life_in_the_village/Village/Economy/EconomicBalance.java

package tterrag1112.life_in_the_village.Village.Economy;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.HashMap;
import java.util.Map;

/**
 * Centralized economic balance configuration.
 *
 * All prices, wages, and costs are defined here for easy balancing.
 */
public class EconomicBalance {

    // =========================================================================
    // Crop prices (per unit, in bronze coins)
    // =========================================================================

    public static final Map<Item, Long> CROP_PRICES = new HashMap<>();
    static {
        CROP_PRICES.put(Items.WHEAT, 2L);
        CROP_PRICES.put(Items.CARROT, 3L);
        CROP_PRICES.put(Items.POTATO, 3L);
        CROP_PRICES.put(Items.BEETROOT, 4L);
        CROP_PRICES.put(Items.MELON_SLICE, 5L);
        CROP_PRICES.put(Items.PUMPKIN, 6L);
        CROP_PRICES.put(Items.SWEET_BERRIES, 7L);
    }

    // =========================================================================
    // Animal product prices (per unit, in bronze coins)
    // =========================================================================

    public static final Map<Item, Long> ANIMAL_PRODUCT_PRICES = new HashMap<>();
    static {
        ANIMAL_PRODUCT_PRICES.put(Items.LEATHER, 8L);
        ANIMAL_PRODUCT_PRICES.put(Items.BEEF, 6L);
        ANIMAL_PRODUCT_PRICES.put(Items.PORKCHOP, 6L);
        ANIMAL_PRODUCT_PRICES.put(Items.MUTTON, 6L);
        ANIMAL_PRODUCT_PRICES.put(Items.CHICKEN, 4L);
        ANIMAL_PRODUCT_PRICES.put(Items.EGG, 1L);
        ANIMAL_PRODUCT_PRICES.put(Items.MILK_BUCKET, 5L);
        ANIMAL_PRODUCT_PRICES.put(Items.WHITE_WOOL, 4L);
        ANIMAL_PRODUCT_PRICES.put(Items.RED_WOOL, 6L);
        ANIMAL_PRODUCT_PRICES.put(Items.BLUE_WOOL, 6L);
        // Add other colored wool as needed
    }

    // =========================================================================
    // Seed prices (per unit, in bronze coins)
    // =========================================================================

    public static final Map<Item, Long> SEED_PRICES = new HashMap<>();
    static {
        SEED_PRICES.put(Items.WHEAT_SEEDS, 1L);
        SEED_PRICES.put(Items.CARROT, 2L);
        SEED_PRICES.put(Items.POTATO, 2L);
        SEED_PRICES.put(Items.BEETROOT_SEEDS, 2L);
        SEED_PRICES.put(Items.MELON_SEEDS, 4L);
        SEED_PRICES.put(Items.PUMPKIN_SEEDS, 3L);
    }

    // =========================================================================
    // Farmhand wages (per week, in bronze coins, by level)
    // =========================================================================

    public static final long[] FARMHAND_WEEKLY_WAGES = {
            8L,   // Level 1: Apprentice
            16L,  // Level 2: Journeyman
            32L,  // Level 3: Expert
            64L,  // Level 4: Master
            128L  // Level 5: Grandmaster
    };

    // =========================================================================
    // Task rewards (in bronze coins)
    // =========================================================================

    public static final long SIMPLE_TASK_REWARD = 4L;
    public static final long MEDIUM_TASK_REWARD = 8L;
    public static final long COMPLEX_TASK_REWARD = 16L;

    // =========================================================================
    // Religious economy (Religion Rework R4a) — daily flows through a religious
    // building's BuildingEconomy. (Civic wages live in the EconomyBalance
    // registry config; clergy are a new line and sit here per R4a.)
    // =========================================================================

    /** Daily clergy wage, drawn from the staffed building's BuildingEconomy
     *  (capped by what it can afford — a poor temple underpays). */
    public static final long PRIEST_DAILY_WAGE = 10L;

    /** Modest daily upkeep debited from a religious building's BuildingEconomy.
     *  Small enough that a healthily-attended temple stays solvent; a neglected
     *  one trends negative (R4c turns that into decay). */
    public static final long TEMPLE_DAILY_UPKEEP = 4L;

    /** Bronze a tithe routes from the payer's wallet into the temple economy
     *  (capped by the payer's wallet). */
    public static final long TITHE_AMOUNT = 8L;

    /** R6d — modest daily upkeep debited from a MONASTERY/ABBEY BuildingEconomy
     *  (the monastery's shared pool). A productive monastery (surplus monastic
     *  goods sold to market) stays solvent; an idle one trends negative. NOT
     *  coupled to decay — monasteries are not rite venues (R6a isRiteVenue split),
     *  so they are outside TempleProsperity. */
    public static final long MONASTERY_DAILY_UPKEEP = 4L;

    // =========================================================================
    // Business costs
    // =========================================================================

    public static final long FARM_BASE_PURCHASE_PRICE = 500L;
    public static final long PRICE_PER_PLOT = 100L;
    public static final long PRICE_PER_BUSINESS_LEVEL = 200L;
    public static final long FARMHOUSE_CONSTRUCTION_COST = 1000L;

    public static final long MARKET_STALL_WEEKLY_RENT = 50L;

    // =========================================================================
    // Equipment costs
    // =========================================================================

    public static final long IRON_HOE_COST = 50L;
    public static final long DIAMOND_HOE_COST = 200L;
    public static final long ENCHANTED_HOE_COST = 500L;
    public static final long SPRINKLER_SYSTEM_COST = 300L;
    public static final long SCARECROW_COST = 100L;

    // =========================================================================
    // Revenue targets for business level progression
    // =========================================================================

    public static final long[] BUSINESS_LEVEL_THRESHOLDS = {
            0L,      // Level 1: Startup
            500L,    // Level 2: Established
            2000L,   // Level 3: Prosperous
            5000L,   // Level 4: Enterprise
            10000L   // Level 5: Agricultural Empire
    };

    // =========================================================================
    // Helper methods
    // =========================================================================

    /**
     * Get market price for any item
     */
    public static long getMarketPrice(Item item) {
        if (CROP_PRICES.containsKey(item)) {
            return CROP_PRICES.get(item);
        }
        if (ANIMAL_PRODUCT_PRICES.containsKey(item)) {
            return ANIMAL_PRODUCT_PRICES.get(item);
        }
        return 1L; // Default fallback
    }

    /**
     * Get seed price for any item
     */
    public static long getSeedPrice(Item item) {
        return SEED_PRICES.getOrDefault(item, 1L);
    }

    /**
     * Calculate expected weekly revenue for a farm
     */
    public static long calculateExpectedWeeklyRevenue(int plotCount, int avgYieldPerPlot) {
        // Assume 2 harvests per week, average wheat price
        return plotCount * avgYieldPerPlot * 2 * CROP_PRICES.get(Items.WHEAT);
    }

    /**
     * Calculate expected weekly costs for a farm
     */
    public static long calculateExpectedWeeklyCosts(int farmhandCount, int plotCount) {
        // Wages + seed costs
        long wages = 0;
        for (int i = 0; i < farmhandCount; i++) {
            wages += FARMHAND_WEEKLY_WAGES[Math.min(i, FARMHAND_WEEKLY_WAGES.length - 1)];
        }

        long seedCosts = plotCount * 20 * SEED_PRICES.get(Items.WHEAT_SEEDS); // ~20 seeds per plot

        return wages + seedCosts;
    }

    /**
     * Calculate expected profit margin
     */
    public static double calculateProfitMargin(int plotCount, int farmhandCount, int avgYieldPerPlot) {
        long revenue = calculateExpectedWeeklyRevenue(plotCount, avgYieldPerPlot);
        long costs = calculateExpectedWeeklyCosts(farmhandCount, plotCount);

        if (revenue == 0) return 0.0;
        return ((double)(revenue - costs) / revenue) * 100.0;
    }

    /**
     * Check if farm is economically viable
     */
    public static boolean isFarmViable(int plotCount, int farmhandCount, int avgYieldPerPlot) {
        long revenue = calculateExpectedWeeklyRevenue(plotCount, avgYieldPerPlot);
        long costs = calculateExpectedWeeklyCosts(farmhandCount, plotCount);
        return revenue > costs;
    }

    /**
     * Suggest optimal farmhand count for plot count
     */
    public static int suggestFarmhandCount(int plotCount) {
        // Rule of thumb: 1 farmhand per 2 plots
        return Math.max(1, plotCount / 2);
    }
}