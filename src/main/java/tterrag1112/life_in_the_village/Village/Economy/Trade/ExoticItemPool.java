package tterrag1112.life_in_the_village.Village.Economy.Trade;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Static pool of exotic trade offers for the wandering trader.
 *
 * Items here should not be normally producible by village buildings,
 * so they feel genuinely rare and worth seeking the trader out for.
 * Prices are in bronze coins.
 */
public final class ExoticItemPool {

    private ExoticItemPool() {}

    public record WanderTrade(Item item, int quantity, long price) {}

    // ── Tier 1: cheap curiosities ────────────────────────────────────────────
    private static final List<WanderTrade> TIER_1 = List.of(
            new WanderTrade(Items.CACTUS,           8,   4L),
            new WanderTrade(Items.SUGAR_CANE,       8,   4L),
            new WanderTrade(Items.BAMBOO,           16,  4L),
            new WanderTrade(Items.COCOA_BEANS,      8,   6L),
            new WanderTrade(Items.SEA_PICKLE,       4,   8L),
            new WanderTrade(Items.DRIED_KELP_BLOCK, 4,   5L),
            new WanderTrade(Items.SAND,             16,  3L),
            new WanderTrade(Items.RED_SAND,         16,  3L),
            new WanderTrade(Items.GRAVEL,           16,  2L),
            new WanderTrade(Items.PODZOL,           8,   4L)
    );

    // ── Tier 2: useful goods ─────────────────────────────────────────────────
    private static final List<WanderTrade> TIER_2 = List.of(
            new WanderTrade(Items.JUNGLE_SAPLING,   2,   12L),
            new WanderTrade(Items.ACACIA_SAPLING,   2,   10L),
            new WanderTrade(Items.DARK_OAK_SAPLING, 2,   10L),
            new WanderTrade(Items.CHORUS_FRUIT,     4,   15L),
            new WanderTrade(Items.SWEET_BERRIES,    8,   8L),
            new WanderTrade(Items.INK_SAC,          4,   10L),
            new WanderTrade(Items.GLOW_INK_SAC,     2,   18L),
            new WanderTrade(Items.HONEYCOMB,        3,   14L),
            new WanderTrade(Items.SLIME_BALL,       4,   12L),
            new WanderTrade(Items.MAGMA_CREAM,      2,   16L)
    );

    // ── Tier 3: rare materials ────────────────────────────────────────────────
    private static final List<WanderTrade> TIER_3 = List.of(
            new WanderTrade(Items.SPONGE,           1,   50L),
            new WanderTrade(Items.PRISMARINE_SHARD, 4,   20L),
            new WanderTrade(Items.SEA_LANTERN,      2,   30L),
            new WanderTrade(Items.BRAIN_CORAL_BLOCK,1,   35L),
            new WanderTrade(Items.TUBE_CORAL_BLOCK, 1,   35L),
            new WanderTrade(Items.FIRE_CORAL_BLOCK, 1,   35L),
            new WanderTrade(Items.OBSIDIAN,         4,   40L),
            new WanderTrade(Items.GHAST_TEAR,       1,   60L),
            new WanderTrade(Items.BLAZE_ROD,        2,   45L),
            new WanderTrade(Items.ENDER_PEARL,      2,   40L)
    );

    // ── Tier 4: very rare / unique ────────────────────────────────────────────
    private static final List<WanderTrade> TIER_4 = List.of(
            new WanderTrade(Items.NAME_TAG,         1,  100L),
            new WanderTrade(Items.SADDLE,           1,   80L),
            new WanderTrade(Items.LEAD,             2,   30L),
            new WanderTrade(Items.NAUTILUS_SHELL,   1,   90L),
            new WanderTrade(Items.HEART_OF_THE_SEA, 1,  200L),
            new WanderTrade(Items.TOTEM_OF_UNDYING, 1,  300L),
            new WanderTrade(Items.EXPERIENCE_BOTTLE,4,   50L),
            new WanderTrade(Items.MUSIC_DISC_CAT,   1,  120L)
    );

    /**
     * Generates a random selection of trades for a new wandering trader.
     * Always includes at least one from each tier to give every trader
     * something interesting at multiple price points.
     */
    public static List<WanderTrade> generateTrades(long seed) {
        Random rng = new Random(seed);
        List<WanderTrade> result = new ArrayList<>();

        // 3 from tier 1
        List<WanderTrade> t1 = new ArrayList<>(TIER_1);
        pickRandom(t1, 3, rng, result);
        // 2 from tier 2
        List<WanderTrade> t2 = new ArrayList<>(TIER_2);
        pickRandom(t2, 2, rng, result);
        // 1-2 from tier 3
        List<WanderTrade> t3 = new ArrayList<>(TIER_3);
        pickRandom(t3, rng.nextBoolean() ? 2 : 1, rng, result);
        // 0-1 from tier 4 (rare)
        if (rng.nextInt(3) == 0) {
            List<WanderTrade> t4 = new ArrayList<>(TIER_4);
            pickRandom(t4, 1, rng, result);
        }

        return result;
    }

    private static void pickRandom(List<WanderTrade> pool, int count,
                                   Random rng, List<WanderTrade> out) {
        for (int i = 0; i < count && !pool.isEmpty(); i++) {
            int idx = rng.nextInt(pool.size());
            out.add(pool.remove(idx));
        }
    }
}