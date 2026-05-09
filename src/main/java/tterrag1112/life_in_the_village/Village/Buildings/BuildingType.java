// src/main/java/tterrag1112/life_in_the_village/Village/Buildings/BuildingType.java
package tterrag1112.life_in_the_village.Village.Buildings;

import tterrag1112.life_in_the_village.Profession.Profession;

public enum BuildingType {
    TOWN_HALL, INN, GUILD_HALL, GUARD_TOWER, STOCKPILE, FARMHOUSE, HOUSE, MARKET, MINE, BLACKSMITH, CASTLE,
    CARPENTRY, CHAPEL,

    // Tier 1
    WELL,

    // Tier 2
    BAKERY, STABLE, MILLER, WOODCUTTER, WAREHOUSE, SHRINE, VINEYARD, FISHERY, PIER,

    // Tier 3
    BARRACKS, TEMPLE, LIBRARY, APOTHECARY, WATCHTOWER,
    STONEMASON, WEAVER, CANDLEMAKER, PRISON, BELL_TOWER,
    HEALER_HUT,

    // Tier 4
    NOBLE_MANOR, WINERY, ARMORER, TOOLSMITH, ATELIER, DOCKS,

    // Track D1 (Phase 0) — landed-noble holding owned by a named
    // noble, smaller than a village, larger than a single farmstead.
    // Inert this phase: no spawn rule, no inhabitant populator, no
    // structure JSONs. D3 wires placement, ownership, and economic
    // flow from {@code Kingdom.subdivisionModel}.
    ESTATE,

    // Scribal (Phase 2 task 17)
    SCRIBE_WORKSHOP, SCHOLARS_RETREAT,

    // ── Guild halls (Phase 4 doc 27) ──────────────────────────────────────
    /**
     * Craftsmen's guild hall. Upgrades the village's implicit Craftsmen
     * guild to L1 on construction; offices populate via
     * {@code OfficeFramework}. Members are blacksmiths, carpenters,
     * weavers, stonemasons, candlemakers, and millers.
     */
    GUILD_HALL_CRAFTSMEN,
    /** Merchants' guild hall — merchants, innkeepers, stockpile keepers. */
    GUILD_HALL_MERCHANTS,
    /** Agricultural guild hall — farmers, bakers, millers (overlap). */
    GUILD_HALL_AGRICULTURAL,
    /** Religious guild hall — priests + religiously-aligned scholars. */
    GUILD_HALL_RELIGIOUS,
    /** Scholarly guild hall — scholars, scribes, librarians. */
    GUILD_HALL_SCHOLARLY,

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
