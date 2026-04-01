package tterrag1112.life_in_the_village.Kingdom.Castle;

/**
 * Tags a horizontal row within an NBT wall design, controlling how it
 * behaves when the design is stretched or compressed to fit a different
 * height than it was authored at.
 *
 * Mirrors TMA's Layer Marker system exactly.
 */
public enum LayerTrait {

    /** Always present — never removed, never cloned. */
    STANDARD,

    /**
     * Cloned up to once when the target is taller than the source.
     * Use for mid-wall bands that can repeat.
     */
    CLONE_ONCE,

    /**
     * Cloned up to three times when the target is taller.
     * Use for the main fill rows of a wall.
     */
    CLONE_THRICE,

    /**
     * Dropped when the target is shorter than the source.
     * Use for decorative rows that aren't structurally required.
     */
    OPTIONAL,

    /**
     * Does not add to the design height. Merges upward into the next
     * design above. Use for top-of-wall decoration that overlaps
     * battlement territory.
     */
    MERGE_ABOVE,

    /**
     * Does not add to the design height. Overwrites the row below it.
     * Use for roofs that need to merge into the top floor of the wall.
     */
    MERGE_BELOW
}