package tterrag1112.life_in_the_village.Profession;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
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
                    XpSource.SELL_TO_NPC,    5,
                    XpSource.JOB_POSTING,    50,
                    XpSource.CRAFT_RELEVANT, 3
            )
    ),
    BLACKSMITH(
            "Blacksmith",
            new int[]{0, 150, 400, 900, 2000},
            Map.of(
                    XpSource.BREAK_BLOCK,    2,
                    XpSource.CRAFT_RELEVANT, 8,
                    XpSource.SELL_TO_NPC,    6,
                    XpSource.JOB_POSTING,    60
            )
    ),
    CARPENTER(
            "Carpenter",
            new int[]{0, 100, 300, 700, 1500},
            Map.of(
                    XpSource.BREAK_BLOCK,    2,
                    XpSource.CRAFT_RELEVANT, 8,
                    XpSource.SELL_TO_NPC,    5,
                    XpSource.JOB_POSTING,    50
            )
    ),
    MINER(
            "Miner",
            new int[]{0, 100, 250, 600, 1400},
            Map.of(
                    XpSource.BREAK_BLOCK,    5,
                    XpSource.SELL_TO_NPC,    4,
                    XpSource.JOB_POSTING,    40,
                    XpSource.CRAFT_RELEVANT, 2
            )
    );

    // -------------------------------------------------------------------------
    // XP sources
    // -------------------------------------------------------------------------

    public enum XpSource {
        BREAK_BLOCK,
        HARVEST_CROP,
        CRAFT_RELEVANT,
        SELL_TO_NPC,
        JOB_POSTING
    }

    // -------------------------------------------------------------------------
    // Level names
    // -------------------------------------------------------------------------

    public static final String[] LEVEL_NAMES = {
            "Apprentice",
            "Journeyman",
            "Expert",
            "Master",
            "Grandmaster"
    };

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    private final String displayName;
    private final int[] xpThresholds; // XP needed per level
    private final Map<XpSource, Integer> xpRewards;

    PlayerProfession(String displayName,
                     int[] xpThresholds,
                     Map<XpSource, Integer> xpRewards) {
        this.displayName  = displayName;
        this.xpThresholds = xpThresholds;
        this.xpRewards    = xpRewards;
    }

    public String getDisplayName()        { return displayName; }
    public int[] getXpThresholds()        { return xpThresholds; }

    public int getXpReward(XpSource source) {
        return xpRewards.getOrDefault(source, 0);
    }

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
        return getLevelFromXp(xp)
                >= xpThresholds.length - 1;
    }

    // -------------------------------------------------------------------------
    // Relevance checks — used by XP event hooks
    // -------------------------------------------------------------------------

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
        };
    }

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
        };
    }

    // -------------------------------------------------------------------------
    // Codec
    // -------------------------------------------------------------------------

    public static final Codec<PlayerProfession> CODEC =
            Codec.STRING.xmap(PlayerProfession::valueOf,
                    PlayerProfession::name);
}