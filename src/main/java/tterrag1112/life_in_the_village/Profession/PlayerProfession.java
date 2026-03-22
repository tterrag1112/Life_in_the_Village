// src/main/java/tterrag1112/life_in_the_village/Profession/PlayerProfession.java
package tterrag1112.life_in_the_village.Profession;

import com.mojang.serialization.Codec;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Items;

import java.util.*;

public enum PlayerProfession {

    FARMER(
            "Farmer",
            new int[]{0, 100, 300, 700, 1500},
            Map.of(
                    XpSource.HARVEST_CROP,   10,
                    XpSource.SELL_TO_NPC,     5,
                    XpSource.JOB_POSTING,    50,
                    XpSource.CRAFT_RELEVANT,  3
            )
    ),

    BLACKSMITH(
            "Blacksmith",
            new int[]{0, 150, 400, 900, 2000},
            Map.of(
                    XpSource.BREAK_BLOCK,     2,
                    XpSource.CRAFT_RELEVANT,  8,
                    XpSource.SELL_TO_NPC,     6,
                    XpSource.JOB_POSTING,    60
            )
    ),

    CARPENTER(
            "Carpenter",
            new int[]{0, 100, 300, 700, 1500},
            Map.of(
                    XpSource.BREAK_BLOCK,     2,
                    XpSource.CRAFT_RELEVANT,  8,
                    XpSource.SELL_TO_NPC,     5,
                    XpSource.JOB_POSTING,    50
            )
    ),

    MINER(
            "Miner",
            new int[]{0, 100, 250, 600, 1400},
            Map.of(
                    XpSource.BREAK_BLOCK,     5,
                    XpSource.SELL_TO_NPC,     4,
                    XpSource.JOB_POSTING,    40,
                    XpSource.CRAFT_RELEVANT,  2
            )
    ),

    /**
     * MERCHANT — unlocked by working at a MARKET building.
     *
     * <h3>XP sources</h3>
     * The primary activity is selling goods to NPCs and completing
     * trade assignments. Crafting trade goods (paper, maps, books)
     * also awards small amounts.
     *
     * <h3>Perks</h3>
     * Journeyman: MERCHANT_BETTER_PRICES — NPC buy prices +10%<br>
     * Expert:     MERCHANT_TRADE_EXEMPTION — no kingdom trade penalty<br>
     * Master:     MERCHANT_JOB_BONUS — job posting coin rewards +25%<br>
     * Grandmaster: MERCHANT_REPUTATION_TRADER — double rep per trade
     */
    MERCHANT(
            "Merchant",
            new int[]{0, 120, 350, 800, 1800},
            Map.of(
                    XpSource.SELL_TO_NPC,    15,   // primary — every trade tick
                    XpSource.JOB_POSTING,    70,   // completing trade assignments
                    XpSource.CRAFT_RELEVANT,  4,   // crafting trade goods
                    XpSource.BREAK_BLOCK,     0    // not applicable
            )
    ),

    /**
     * GUARD — unlocked by working at a BARRACKS or GUARD_TOWER building.
     *
     * <h3>XP sources</h3>
     * Guards earn XP primarily from completing patrol and security
     * job postings. A small amount comes from crafting weapons and
     * armour, representing equipment upkeep. BREAK_BLOCK yields
     * a tiny amount as a proxy for clearing threats (e.g. chopping
     * down hostile mob nests). A dedicated KILL_MOB source could be
     * wired in later for more precise tracking.
     *
     * <h3>Perks</h3>
     * Journeyman: GUARD_TOUGHNESS — −10% damage from hostile mobs<br>
     * Expert:     GUARD_RALLY — nearby guards alerted when you take damage<br>
     * Master:     GUARD_EXTENDED_PATROL — patrol radius increased<br>
     * Grandmaster: GUARD_INSPIRE — nearby players gain Strength I on village alarm
     */
    GUARD(
            "Guard",
            new int[]{0, 120, 350, 800, 1800},
            Map.of(
                    XpSource.JOB_POSTING,    60,   // patrol/security assignments
                    XpSource.CRAFT_RELEVANT,  6,   // crafting weapons & armour
                    XpSource.BREAK_BLOCK,     1,   // minor — clearing threats
                    XpSource.SELL_TO_NPC,     3    // selling confiscated goods
            )
    );

    // =========================================================================
    // XP sources
    // =========================================================================

    public enum XpSource {
        BREAK_BLOCK,
        HARVEST_CROP,
        CRAFT_RELEVANT,
        SELL_TO_NPC,
        JOB_POSTING
    }

    // =========================================================================
    // Level names
    // =========================================================================

    public static final String[] LEVEL_NAMES = {
            "Apprentice",
            "Journeyman",
            "Expert",
            "Master",
            "Grandmaster"
    };

    // =========================================================================
    // Fields
    // =========================================================================

    private final String displayName;
    private final int[]  xpThresholds;
    private final Map<XpSource, Integer> xpRewards;

    PlayerProfession(String displayName,
                     int[] xpThresholds,
                     Map<XpSource, Integer> xpRewards) {
        this.displayName  = displayName;
        this.xpThresholds = xpThresholds;
        this.xpRewards    = xpRewards;
    }

    // =========================================================================
    // Accessors
    // =========================================================================

    public String getDisplayName()  { return displayName; }
    public int[]  getXpThresholds() { return xpThresholds; }

    public int getXpReward(XpSource source) {
        return xpRewards.getOrDefault(source, 0);
    }

    // =========================================================================
    // Level maths
    // =========================================================================

    public int getLevelFromXp(int xp) {
        for (int i = xpThresholds.length - 1; i >= 0; i--) {
            if (xp >= xpThresholds[i]) return i;
        }
        return 0;
    }

    public String getLevelName(int xp) {
        return LEVEL_NAMES[getLevelFromXp(xp)];
    }

    public int getXpToNextLevel(int xp) {
        int level = getLevelFromXp(xp);
        if (level >= xpThresholds.length - 1) return 0;
        return xpThresholds[level + 1] - xp;
    }

    public boolean isMaxLevel(int xp) {
        return getLevelFromXp(xp) >= xpThresholds.length - 1;
    }

    // =========================================================================
    // Relevance checks — used by XP event hooks
    // =========================================================================

    /**
     * Returns true if breaking this block should award XP for this profession.
     */
    public boolean isRelevantBlock(
            net.minecraft.world.level.block.state.BlockState state) {
        return switch (this) {
            case MINER      -> state.is(BlockTags.MINEABLE_WITH_PICKAXE)
                    && !state.is(net.minecraft.world.level.block.Blocks.STONE)
                    && !state.is(net.minecraft.world.level.block.Blocks.COBBLESTONE);
            case CARPENTER  -> state.is(BlockTags.LOGS)
                    || state.is(BlockTags.PLANKS);
            case FARMER     -> state.is(BlockTags.CROPS);
            case BLACKSMITH -> false;
            // Guards earn a tiny amount when clearing hostile mob spawning
            // blocks (e.g. breaking spawners or mob-nest blocks). Using the
            // WOOL tag as a proxy for structural blocks guards might clear.
            case GUARD      -> false; // reserved — wire KILL_MOB later
            case MERCHANT   -> false;
        };
    }

    /**
     * Returns true if crafting this item should award XP for this profession.
     */
    public boolean isRelevantCraft(
            net.minecraft.world.item.ItemStack result) {
        return switch (this) {
            case BLACKSMITH -> result.is(ItemTags.SWORDS)
                    || result.is(ItemTags.PICKAXES)
                    || result.is(ItemTags.AXES)
                    || result.getItem() == Items.IRON_INGOT
                    || result.getItem() == Items.GOLD_INGOT;

            case CARPENTER  -> result.is(ItemTags.PLANKS)
                    || result.getItem() == Items.CHEST
                    || result.getItem() == Items.BARREL
                    || result.is(ItemTags.DOORS)
                    || result.is(ItemTags.FENCES);

            case FARMER     -> result.getItem() == Items.BREAD
                    || result.getItem() == Items.CAKE
                    || result.getItem() == Items.COOKIE;

            case MINER      -> result.getItem() == Items.COBBLESTONE
                    || result.getItem() == Items.STONE_BRICKS;

            // Merchants craft trade goods: maps, paper, books, lanterns
            case MERCHANT   -> result.getItem() == Items.PAPER
                    || result.getItem() == Items.BOOK
                    || result.getItem() == Items.MAP
                    || result.getItem() == Items.LANTERN
                    || result.getItem() == Items.WRITABLE_BOOK;

            // Guards craft weapons and armour for equipment upkeep
            case GUARD      -> result.is(ItemTags.SWORDS)
                    || result.is(ItemTags.BOW_ENCHANTABLE)
                    || result.is(ItemTags.ARMOR_ENCHANTABLE)
                    || result.getItem() == Items.SHIELD
                    || result.getItem() == Items.ARROW
                    || result.getItem() == Items.CROSSBOW;
        };
    }

    // =========================================================================
    // Legacy helpers preserved for backwards compat
    // =========================================================================

    public double getTradeDiscount(int level) {
        return switch (level) {
            case 0  -> 0.00;
            case 1  -> 0.05;
            case 2  -> 0.10;
            case 3  -> 0.15;
            default -> 0.20;
        };
    }

    public double getYieldBonus(int level) {
        return switch (level) {
            case 0  -> 0.00;
            case 1  -> 0.10;
            case 2  -> 0.25;
            case 3  -> 0.50;
            default -> 1.00;
        };
    }

    public boolean canHireWorkers(int level)        { return level >= 3; }
    public boolean hasUnlockedRecipes(int level)    { return level >= 2; }
    public int     getNpcRespectBonus(int level)    { return level * 5; }

    // =========================================================================
    // Codec
    // =========================================================================

    public static final Codec<PlayerProfession> CODEC =
            Codec.STRING.xmap(PlayerProfession::valueOf,
                    PlayerProfession::name);
}