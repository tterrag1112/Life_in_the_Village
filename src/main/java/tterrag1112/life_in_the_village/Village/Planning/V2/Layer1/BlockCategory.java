package tterrag1112.life_in_the_village.Village.Planning.V2.Layer1;

/**
 * V2 Layer 1 surface-block classification. One label per scan cell.
 *
 * <p>Categories are mutually exclusive — the scan picks the most
 * specific label that fits. Resolution order (top wins):
 * STRUCTURE, WATER, SHORE, FOREST, STONE_EXPOSED, OPEN.
 */
public enum BlockCategory {
    /** Top block is water (block or fluid source). */
    WATER,
    /** Non-water cell with at least one water neighbour and surface y
     *  within ±2 of the water level. */
    SHORE,
    /** Surface is/has trees: surface block is a log, or the column
     *  above the surface contains ≥3 log/leaf blocks within 30 blocks. */
    FOREST,
    /** Surface block is natural overworld stone (stone, deepslate,
     *  granite, diorite, andesite, tuff) and not under tree cover. */
    STONE_EXPOSED,
    /** Surface block is a culture-style construction block (planks,
     *  bricks, cobblestone, stone bricks). Indicates a player or
     *  village built here. */
    STRUCTURE,
    /** Anything else — typical grass / dirt / sand surface. */
    OPEN
}
