// src/main/java/tterrag1112/life_in_the_village/Profession/Profession.java
package tterrag1112.life_in_the_village.Profession;

import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;

public enum Profession {
    NONE, CITIZEN, MERCHANT, WANDERING_TRADER, FARMER, FARMHAND, BLACKSMITH, BUILDER,
    GUARD, STOCKPILE_KEEPER, INNKEEPER, MINER,
    VILLAGE_LEADER, KINGDOM_RULER, CARPENTER,
    MILLER,          // MILLER building — grinds wheat to flour
    BAKER,           // BAKERY building — bakes bread from flour or wheat
    STONEMASON,      // STONEMASON building — cuts stone into bricks/slabs/stairs
    WEAVER,          // WEAVER building — wool into carpets and cloth goods
    CANDLEMAKER,     // CANDLEMAKER building — honeycomb + string into candles
    GUILDMASTER, GUILDWORKER, ADVENTURER, COMPANY_WORKER,

    // ── Capital professions ───────────────────────────────────────────────────

    /**
     * Town crier and royal announcer. Assigned to the TOWN_HALL or
     * CHANCELLERY. Announces events, greets players, and broadcasts
     * kingdom decrees via chat messages. Wanders the main plaza during
     * the day.
     */
    HERALD,

    /**
     * Head of the CHANCELLERY. Manages the kingdom's administrative
     * affairs — tax collection records, trade agreements, and expansion
     * requests. The highest-ranking NPC below the KINGDOM_RULER.
     */
    CHANCELLOR,

    /**
     * Researcher assigned to the SCHOLARS_RETREAT (or LIBRARY when no
     * retreat exists). Studies books, authors original works, and
     * teaches advanced lessons. Phase 2 task 17 wires the work goal.
     */
    SCHOLAR,

    /**
     * Spiritual leader assigned to the TEMPLE. Performs blessings
     * during festivals. Provides a minor wellbeing bonus to nearby NPCs.
     * Interactable — trades rare items for reputation.
     */
    PRIEST,

    /**
     * Literate craftsman of the SCRIBE_WORKSHOP. Writes letters, copies
     * books, and drafts contracts on commission. Phase 2 task 17.
     */
    SCRIBE,

    /**
     * Curator of the LIBRARY. Catalogs and lends books, runs schooling
     * sessions, and archives village records. Phase 2 task 17.
     */
    LIBRARIAN;

    public String getDisplayName() {
        return switch (this) {
            case NONE             -> "Unemployed";
            case CITIZEN          -> "Citizen";
            case KINGDOM_RULER    -> "Ruler";
            case VILLAGE_LEADER   -> "Village Leader";
            case STOCKPILE_KEEPER -> "Stockpile Keeper";
            case COMPANY_WORKER   -> "Company Worker";
            default -> name().charAt(0) + name().substring(1).toLowerCase();
        };
    }

    public static Profession professionFor(BuildingType type) {
        return switch (type) {
            case BLACKSMITH      -> Profession.BLACKSMITH;
            case FARMHOUSE       -> Profession.FARMER;
            case MINE            -> Profession.MINER;
            case MARKET          -> Profession.MERCHANT;
            case INN             -> Profession.INNKEEPER;
            case STOCKPILE       -> Profession.STOCKPILE_KEEPER;
            case GUARD_TOWER     -> Profession.GUARD;
            case CARPENTRY       -> Profession.CARPENTER;
            case GUILD_HALL      -> Profession.GUILDWORKER;
            case TOWN_HALL       -> Profession.VILLAGE_LEADER;
            case CASTLE          -> Profession.KINGDOM_RULER;
            case CHANCELLERY     -> Profession.CHANCELLOR;
            case LIBRARY         -> Profession.LIBRARIAN;
            case SCRIBE_WORKSHOP -> Profession.SCRIBE;
            case SCHOLARS_RETREAT-> Profession.SCHOLAR;
            case TEMPLE          -> Profession.PRIEST;
            case TREASURY        -> Profession.GUARD;   // guarded, not staffed
            case HOUSE           -> Profession.NONE;
            case MILLER          -> Profession.MILLER;
            case STONEMASON      -> Profession.STONEMASON;
            case WEAVER          -> Profession.WEAVER;
            case CANDLEMAKER     -> Profession.CANDLEMAKER;
            case BAKERY ->  Profession.BAKER;
            default              -> Profession.NONE;
        };
    }
}