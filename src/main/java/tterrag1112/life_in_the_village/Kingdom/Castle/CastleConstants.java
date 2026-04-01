package tterrag1112.life_in_the_village.Kingdom.Castle;

/**
 * Shared constants used across all castle generation layers.
 * Centralised here so changing one value stays consistent everywhere.
 */
public final class CastleConstants {
    private CastleConstants() {}

    /** Clear height of the wall walkway interior in blocks. */
    public static final int WALKWAY_CLEAR_HEIGHT = 3;

    /** Height interval between tower interior floors. */
    public static final int FLOOR_INTERVAL = 5;

    /** How many arrow slit variants to scan per pool key. */
    public static final int MAX_TEMPLATE_VARIANTS = 8;
}