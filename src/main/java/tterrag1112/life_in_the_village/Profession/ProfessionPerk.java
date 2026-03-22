// src/main/java/tterrag1112/life_in_the_village/Profession/Perks/ProfessionPerk.java
package tterrag1112.life_in_the_village.Profession;

import tterrag1112.life_in_the_village.Profession.PlayerProfession;

import java.util.*;


public enum ProfessionPerk {

    // =========================================================================
    // FARMER perks
    // =========================================================================

    /** Level 1 — Journeyman: small chance to not consume the planted seed. */
    FARMER_SEED_RETURN(PlayerProfession.FARMER, 1,
            "Careful Planting",
            "Chance to not consume seeds when planting."),

    FARMER_YIELD(PlayerProfession.FARMER,
            1,
            "Green Thumb",
            "Chance for extra crop drops"),


    /** Level 2 — Expert: harvest all mature crops in a 3×3 area at once. */
    FARMER_BULK_HARVEST(PlayerProfession.FARMER, 2,
            "Sweeping Harvest",
            "Harvesting a crop collects all mature crops within 1 block."),

    /** Level 3 — Master: 25% increased yield from all crops. */
    FARMER_ABUNDANCE(PlayerProfession.FARMER, 3,
            "Abundant Fields",
            "+25% crop yield on top of the normal yield bonus."),

    /** Level 4 — Grandmaster: nearby farm plots grow 50% faster. */
    FARMER_GROWTH_AURA(PlayerProfession.FARMER, 4,
            "Green Thumb",
            "Crops within 16 blocks of you grow noticeably faster."),
    FARMER_SEASONAL_EXPERT(PlayerProfession.FARMER,
            4,
            "Seasonal Expert",
            "No winter penalty to your farm plots"),

    // =========================================================================
    // BLACKSMITH perks
    // =========================================================================

    /** Level 1 — Journeyman: repair tools at reduced material cost. */
    SMITH_FAST_REPAIR(PlayerProfession.BLACKSMITH, 1,
            "Practiced Hand",
            "Repairing tools at an anvil costs 20% fewer materials."),
    BLACKSMITH_HASTE(PlayerProfession.BLACKSMITH,
            1,
            "Forge Rhythm",
            "Haste I while near a lit furnace"),


    /** Level 2 — Expert: smelt iron faster (haste effect at the furnace). */
    SMITH_EFFICIENT_SMELTING(PlayerProfession.BLACKSMITH, 2,
            "Efficient Forge",
            "Ore smelting time reduced; gain Haste I near active furnaces."),
    BLACKSMITH_DURABILITY (PlayerProfession.BLACKSMITH,
            2,
            "Quality Steel",
            "Crafted tools have +10% durability"),

    /** Level 3 — Master: crafted iron/gold tools and armour have bonus durability. */
    SMITH_MASTERWORK(PlayerProfession.BLACKSMITH, 3,
            "Masterwork",
            "Crafted metal tools and armour have +15% max durability."),
    BLACKSMITH_ALLOY(PlayerProfession.BLACKSMITH,
            3,
            "Alloy Craft",
            "Unlock alloy recipes at the smithing table"),

    /** Level 4 — Grandmaster: double output on relevant crafting recipes. */
    SMITH_DOUBLE_OUTPUT(PlayerProfession.BLACKSMITH, 4,
            "Grand Foundry",
            "Chance to produce a second item when crafting metal equipment."),
    BLACKSMITH_MASTER(PlayerProfession.BLACKSMITH,
            4,
            "Master Smith",
            "All crafted tools are Sharpness I / Efficiency I"),

    // =========================================================================
    // CARPENTER perks
    // =========================================================================

    /** Level 1 — Journeyman: extra plank per log. */
    CARPENTER_EFFICIENT_CUT(PlayerProfession.CARPENTER, 1,
            "Clean Cuts",
            "Each log craft yields one extra plank."),

    /** Level 2 — Expert: work faster with axes (haste when holding axe). */
    CARPENTER_WOODCUTTER(PlayerProfession.CARPENTER, 2,
            "Seasoned Woodcutter",
            "Gain Haste I when holding an axe."),
    CARPENTER_LOG_SPLITTER(PlayerProfession.CARPENTER,
            2,
            "Log Splitter",
            "Breaking a log drops 6 planks instead of 4"),


    /** Level 3 — Master: crafted wooden items are stronger. */
    CARPENTER_QUALITY_WOOD(PlayerProfession.CARPENTER, 3,
            "Seasoned Timber",
            "Crafted wooden tools have +20% max durability."),
    CARPENTER_FURNITURE(PlayerProfession.CARPENTER,
            3,
            "Furniture Maker",
            "Unlock decorative furniture recipes"),

    /** Level 4 — Grandmaster: chance to produce rare wood items from normal logs. */
    CARPENTER_RARE_YIELD(PlayerProfession.CARPENTER, 4,
            "Master Joiner",
            "Chance to produce extra crafting output when working with wood."),

    // =========================================================================
    // MERCHANT perks
    // =========================================================================

    /** Level 1 — Journeyman: sell items to NPCs for a slightly better price. */
    MERCHANT_BETTER_PRICES(PlayerProfession.MERCHANT, 1,
            "Haggler",
            "NPC buy prices for your items are 10% higher."),

    /** Level 2 — Expert: no tax on cross-village trades. */
    MERCHANT_TRADE_EXEMPTION(PlayerProfession.MERCHANT, 2,
            "Trade License",
            "Trade route kingdom penalties do not apply to your personal trades."),

    /** Level 3 — Master: double coins on job-posting rewards. */
    MERCHANT_JOB_BONUS(PlayerProfession.MERCHANT, 3,
            "Sharp Dealings",
            "Coin rewards from job postings are increased by 25%."),

    /** Level 4 — Grandmaster: passive reputation gain from every trade. */
    MERCHANT_REPUTATION_TRADER(PlayerProfession.MERCHANT, 4,
            "Well-Known Trader",
            "Every trade gives double village reputation score."),



    // =========================================================================
    // GUARD perks
    // =========================================================================

    /** Level 1 — Journeyman: take less damage from hostile mobs. */
    GUARD_TOUGHNESS(PlayerProfession.GUARD, 1,
            "Hardened",
            "Take 10% less damage from hostile mobs while on duty."),

    /** Level 2 — Expert: rallying cry alerts nearby guards to threats. */
    GUARD_RALLY(PlayerProfession.GUARD, 2,
            "War Cry",
            "On taking damage, nearby village NPCs become temporarily braver."),

    /** Level 3 — Master: patrols cover more ground. */
    GUARD_EXTENDED_PATROL(PlayerProfession.GUARD, 3,
            "Watchful Eye",
            "Patrol radius increased; detect threats from further away."),

    /** Level 4 — Grandmaster: inspire nearby players during combat. */
    GUARD_INSPIRE(PlayerProfession.GUARD, 4,
            "Inspiring Presence",
            "Nearby players gain Strength I for 30s when a village alarm is raised.");

    // =========================================================================
    // Enum fields
    // =========================================================================

    public final PlayerProfession profession;
    /**
     * Level at which this perk is unlocked (1 = Journeyman, 2 = Expert,
     * 3 = Master, 4 = Grandmaster).
     */
    public final int unlockLevel;
    public final String displayName;
    public final String description;

    ProfessionPerk(PlayerProfession profession, int unlockLevel,
                   String displayName, String description) {
        this.profession   = profession;
        this.unlockLevel  = unlockLevel;
        this.displayName  = displayName;
        this.description  = description;
    }

    // -------------------------------------------------------------------------
    // Static lookup helpers
    // -------------------------------------------------------------------------

    private static final Map<PlayerProfession, List<ProfessionPerk>> BY_PROFESSION
            = new EnumMap<>(PlayerProfession.class);

    static {
        for (ProfessionPerk perk : values()) {
            BY_PROFESSION.computeIfAbsent(
                    perk.profession, p -> new ArrayList<>()).add(perk);
        }
    }

    /**
     * Returns all perks for the given profession, ordered by unlock level.
     */
    public static List<ProfessionPerk> forProfession(PlayerProfession profession) {
        return BY_PROFESSION.getOrDefault(profession, List.of());
    }

    /**
     * Returns the perk unlocked at exactly {@code level} for {@code profession},
     * or empty if none is defined for that level.
     */
    public static Optional<ProfessionPerk> atLevel(PlayerProfession profession,
                                                   int level) {
        return forProfession(profession).stream()
                .filter(p -> p.unlockLevel == level)
                .findFirst();
    }
}