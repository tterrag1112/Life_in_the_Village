// src/main/java/tterrag1112/life_in_the_village/Village/Buildings/BuildingType.java
package tterrag1112.life_in_the_village.Village.Buildings;

import tterrag1112.life_in_the_village.Profession.Profession;

public enum BuildingType {
    TOWN_HALL, INN, GUILD_HALL, GUARD_TOWER, STOCKPILE, FARMHOUSE, HOUSE, MARKET, MINE, BLACKSMITH, CASTLE,
    CARPENTRY, CHAPEL,

    // Tier 1
    WELL,

    // Tier 2
    BAKERY, STABLE, MILLER, WOODCUTTER,

    // Tier 3
    BARRACKS, TEMPLE, LIBRARY, APOTHECARY, WATCHTOWER,
    STONEMASON, WEAVER, CANDLEMAKER, PRISON, BELL_TOWER,

    // Tier 4
    NOBLE_MANOR, WINERY, ARMORER, TOOLSMITH, ATELIER, DOCKS,

    // ── Capital-exclusive ─────────────────────────────────────────────────────

    /**
     * The administrative heart of a royal capital. Houses the CHANCELLOR
     * and their staff. Handles taxation records, royal decrees, and
     * diplomatic correspondence.
     *
     * Required for the CHANCELLOR profession to function.
     * Placed in CIVIC zone, inner ring, priority 0 (before guild hall).
     */
    CHANCELLERY,

    /**
     * The kingdom treasury vault. Stores the kingdom's gold reserves,
     * collects tax income from trade routes, and pays kingdom upkeep.
     * Distinct from STOCKPILE — holds only currency, not raw goods.
     *
     * Guarded by a GUARD assigned here at spawn.
     */
    TREASURY,

    // Special — procedurally generated, not a placeable NBT structure
    TOWN_SQUARE

}
