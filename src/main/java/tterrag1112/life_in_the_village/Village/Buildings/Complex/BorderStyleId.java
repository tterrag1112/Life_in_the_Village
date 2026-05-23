package tterrag1112.life_in_the_village.Village.Buildings.Complex;

/**
 * Detour A — identifier for a plot-border rendering style.
 *
 * <p>The enum values name the styles; the actual block-placement
 * recipes are defined by Prompt B's renderer keyed off these ids.
 * Spec ({@link BuildingComplexSpec#borderStylePool()}) carries a
 * list of these as the pool the per-plot border assigner draws
 * from.
 *
 * <p>Initial values cover the four farm-domain styles the prompt
 * specifies. Other complex domains (forge yard, sacred grove) can
 * add new ids here as their renderers are authored.
 */
public enum BorderStyleId {
    /** Living hedge — leaves + minor flora; soft visual boundary. */
    HEDGE,
    /** Mortared stone wall; hard boundary, suggests prosperity. */
    STONE_WALL,
    /** Wooden post-and-rail fence; light agricultural feel. */
    POST_AND_RAIL,
    /** Dry-stone wall (no mortar); medium hardness, regional. */
    DRYSTONE
}
