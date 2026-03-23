// src/main/java/tterrag1112/life_in_the_village/Profession/Profession.java
package tterrag1112.life_in_the_village.Profession;

import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;

public enum Profession {
    NONE, CITIZEN, MERCHANT, FARMER, FARMHAND, BLACKSMITH, BUILDER,
    GUARD, STOCKPILE_KEEPER, INNKEEPER, MINER,
    VILLAGE_LEADER, KINGDOM_RULER, CARPENTER,
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
     * Researcher assigned to the LIBRARY. Generates small passive
     * reputation bonuses for the village over time by attracting
     * visitors. Interactable — offers knowledge trades with the player.
     */
    SCHOLAR,

    /**
     * Spiritual leader assigned to the TEMPLE. Performs blessings
     * during festivals. Provides a minor wellbeing bonus to nearby NPCs.
     * Interactable — trades rare items for reputation.
     */
    PRIEST;

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
            case LIBRARY         -> Profession.SCHOLAR;
            case TEMPLE          -> Profession.PRIEST;
            case TREASURY        -> Profession.GUARD;   // guarded, not staffed
            case HOUSE           -> Profession.NONE;
            default              -> Profession.NONE;
        };
    }
}