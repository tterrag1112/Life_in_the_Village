package tterrag1112.life_in_the_village.Profession;

import java.util.*;

/**
 * All profession perks, deduplicated and organized.
 *
 * <h3>Changes from previous version</h3>
 * <ul>
 *   <li>Removed FARMER_YIELD — was identical purpose to FARMER_SEED_RETURN
 *       at the same level. Merged into FARMER_SEED_RETURN which now also
 *       covers the "extra drops" concept.</li>
 *   <li>Renamed FARMER_GROWTH_AURA display name from "Green Thumb" (duplicate
 *       of the removed FARMER_YIELD) to "Verdant Aura".</li>
 *   <li>Removed BLACKSMITH_HASTE — was identical to SMITH_FAST_REPAIR at
 *       level 1. The Haste-near-furnace effect is now part of
 *       SMITH_FAST_REPAIR's description and implementation.</li>
 *   <li>Removed BLACKSMITH_DURABILITY — was identical purpose to
 *       SMITH_MASTERWORK at a lower level. Merged: SMITH_EFFICIENT_SMELTING
 *       (level 2) now covers the +10% durability, SMITH_MASTERWORK (level 3)
 *       covers +15%.</li>
 *   <li>Removed FARMER_SEASONAL_EXPERT — was at level 4 alongside
 *       FARMER_GROWTH_AURA. Merged into FARMER_GROWTH_AURA which now
 *       also removes winter penalty.</li>
 * </ul>
 *
 * <h3>Perk level convention</h3>
 * <ul>
 *   <li>Level 1 = Journeyman (first unlock, should feel immediately useful)</li>
 *   <li>Level 2 = Expert (meaningful quality-of-life upgrade)</li>
 *   <li>Level 3 = Master (strong numeric bonus)</li>
 *   <li>Level 4 = Grandmaster (game-changing passive or aura)</li>
 * </ul>
 */
public enum ProfessionPerk {

    // =========================================================================
    // FARMER perks
    // =========================================================================

    /** Level 1: Chance to not consume seeds + chance for extra crop drops. */
    FARMER_SEED_RETURN(PlayerProfession.FARMER, 1,
            "Careful Planting",
            "Chance to not consume seeds when planting, and occasional extra crop drops."),

    /** Level 2: Harvest mature crops in a 3×3 area at once. */
    FARMER_BULK_HARVEST(PlayerProfession.FARMER, 2,
            "Sweeping Harvest",
            "Harvesting a crop collects all mature crops within 1 block."),

    /** Level 3: +25% crop yield. */
    FARMER_ABUNDANCE(PlayerProfession.FARMER, 3,
            "Abundant Fields",
            "+25% crop yield on top of the normal yield bonus."),

    /** Level 4: Crops grow faster nearby + no winter penalty. */
    FARMER_GROWTH_AURA(PlayerProfession.FARMER, 4,
            "Verdant Aura",
            "Crops within 16 blocks grow faster. Winter growth penalty removed for your plots."),

    // =========================================================================
    // BLACKSMITH perks
    // =========================================================================

    /** Level 1: Repair tools cheaper + Haste I near lit furnaces. */
    SMITH_FAST_REPAIR(PlayerProfession.BLACKSMITH, 1,
            "Practiced Hand",
            "Repairing at an anvil costs 20% less. Gain Haste I near active furnaces."),

    /** Level 2: Faster smelting + crafted tools have +10% durability. */
    SMITH_EFFICIENT_SMELTING(PlayerProfession.BLACKSMITH, 2,
            "Quality Steel",
            "Ore smelting time reduced. Crafted metal tools have +10% durability."),

    /** Level 3: Crafted tools and armour have +15% max durability. */
    SMITH_MASTERWORK(PlayerProfession.BLACKSMITH, 3,
            "Masterwork",
            "Crafted metal tools and armour have +15% max durability."),

    /** Level 4: Chance to produce bonus items when smelting. */
    SMITH_SMELTING_BONUS(PlayerProfession.BLACKSMITH, 4,
            "Master of the Forge",
            "Chance to produce an extra ingot when smelting ore. Haste II near active furnaces."),

    // =========================================================================
    // CARPENTER perks
    // =========================================================================

    /** Level 1: Haste while holding an axe. */
    CARPENTER_WOODCUTTER(PlayerProfession.CARPENTER, 1,
            "Lumberjack",
            "Gain Haste I while holding an axe."),

    /** Level 2: Wood drops increased. */
    CARPENTER_YIELD(PlayerProfession.CARPENTER, 2,
            "Efficient Cuts",
            "Trees drop 25% more logs when felled."),

    /** Level 3: Crafted wooden items have bonus durability. */
    CARPENTER_MASTERWORK(PlayerProfession.CARPENTER, 3,
            "Fine Carpentry",
            "Crafted wooden tools and shields have +20% durability."),

    /** Level 4: Nearby trees regrow saplings automatically. */
    CARPENTER_REGROWTH(PlayerProfession.CARPENTER, 4,
            "Forester",
            "Trees within 16 blocks have a chance to drop saplings that auto-plant."),

    // =========================================================================
    // MINER perks
    // =========================================================================

    /** Level 1: Night Vision in caves. */
    MINER_NIGHT_VISION(PlayerProfession.MINER, 1,
            "Cave Eyes",
            "Gain Night Vision while below Y=50."),

    /** Level 2: Extra ore drops. */
    MINER_FORTUNE(PlayerProfession.MINER, 2,
            "Prospector",
            "Chance for bonus ore drops when mining."),

    /** Level 3: Speed while underground. */
    MINER_SPEED(PlayerProfession.MINER, 3,
            "Tunnel Runner",
            "Gain Speed I while below Y=50."),

    /** Level 4: Detect nearby ores (particle effect). */
    MINER_ORE_SENSE(PlayerProfession.MINER, 4,
            "Ore Sense",
            "Nearby valuable ores emit subtle particles visible only to you."),

    // =========================================================================
    // MERCHANT perks
    // =========================================================================

    /** Level 1: NPC buy prices for your items are 10% higher. */
    MERCHANT_BETTER_PRICES(PlayerProfession.MERCHANT, 1,
            "Haggler",
            "NPC buy prices for your items are 10% higher."),

    /** Level 2: No tax on cross-village trades. */
    MERCHANT_TRADE_EXEMPTION(PlayerProfession.MERCHANT, 2,
            "Trade License",
            "Trade route kingdom penalties do not apply to your personal trades."),

    /** Level 3: +25% coins from job posting rewards. */
    MERCHANT_JOB_BONUS(PlayerProfession.MERCHANT, 3,
            "Sharp Dealings",
            "Coin rewards from job postings are increased by 25%."),

    /** Level 4: Double reputation per completed trade. */
    MERCHANT_REPUTATION_TRADER(PlayerProfession.MERCHANT, 4,
            "Well-Known Trader",
            "Every trade gives double village reputation score."),

    // =========================================================================
    // GUARD perks
    // =========================================================================

    /** Level 1: Take 10% less damage from hostile mobs. */
    GUARD_TOUGHNESS(PlayerProfession.GUARD, 1,
            "Hardened",
            "Take 10% less damage from hostile mobs while on duty."),

    /** Level 2: On damage, nearby guards rally to the threat. */
    GUARD_RALLY(PlayerProfession.GUARD, 2,
            "War Cry",
            "On taking damage, nearby village NPCs become temporarily braver."),

    /** Level 3: Extended patrol radius, detect threats further. */
    GUARD_EXTENDED_PATROL(PlayerProfession.GUARD, 3,
            "Watchful Eye",
            "Patrol radius increased; detect threats from further away."),

    /** Level 4: Nearby players gain Strength I when alarm is raised. */
    GUARD_INSPIRE(PlayerProfession.GUARD, 4,
            "Inspiring Presence",
            "Nearby players gain Strength I for 30s when a village alarm is raised.");

    // =========================================================================
    // Enum fields
    // =========================================================================

    public final PlayerProfession profession;
    public final int level;
    public final String displayName;
    public final String description;

    ProfessionPerk(PlayerProfession profession, int level,
                   String displayName, String description) {
        this.profession  = profession;
        this.level       = level;
        this.displayName = displayName;
        this.description = description;
    }

    // =========================================================================
    // Lookup helpers
    // =========================================================================

    private static final Map<PlayerProfession, List<ProfessionPerk>> BY_PROFESSION;

    static {
        Map<PlayerProfession, List<ProfessionPerk>> map = new EnumMap<>(PlayerProfession.class);
        for (ProfessionPerk perk : values()) {
            map.computeIfAbsent(perk.profession, k -> new ArrayList<>()).add(perk);
        }
        // Sort each list by level
        map.values().forEach(list -> list.sort(Comparator.comparingInt(p -> p.level)));
        BY_PROFESSION = Collections.unmodifiableMap(map);
    }

    /** Returns all perks for a profession, sorted by level. */
    public static List<ProfessionPerk> forProfession(PlayerProfession profession) {
        return BY_PROFESSION.getOrDefault(profession, List.of());
    }

    /** Returns the perk unlocked at the given level for a profession, or null. */
    public static ProfessionPerk atLevel(PlayerProfession profession, int level) {
        return forProfession(profession).stream()
                .filter(p -> p.level == level)
                .findFirst()
                .orElse(null);
    }
}