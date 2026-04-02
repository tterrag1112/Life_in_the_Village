// src/main/java/tterrag1112/life_in_the_village/Village/Decoration/StreetTier.java
package tterrag1112.life_in_the_village.Village.Decoration.Roads;

/**
 * Defines the three tiers of streets in a village.
 *
 * <ul>
 *   <li>{@link #PRIMARY} — main roads between the town square and civic/
 *       production buildings. 5 blocks wide, cobblestone, frequent
 *       lampposts.</li>
 *   <li>{@link #SECONDARY} — connecting streets between primary roads
 *       and smaller buildings. 3 blocks wide, gravel/dirt-path mix.</li>
 *   <li>{@link #TERTIARY} — alleys to houses, farms, minor buildings.
 *       1 block wide, plain dirt path.</li>
 * </ul>
 */
public enum StreetTier {

    PRIMARY(
            7,         // width in blocks (half-width = 2)
            8,         // lamppost spacing (blocks)
            true,      // use cobblestone
            false       // place kerb blocks on edges
    ),
    SECONDARY(
            5,         // half-width = 1
            12,
            false,     // gravel / dirt-path
            false
    ),
    TERTIARY(
            3,         // half-width = 0 (single block)
            20,
            false,
            false
    );

    public final int  widthBlocks;
    public final int  halfWidth;
    public final int  lampostSpacing;
    public final boolean cobblestone;
    public final boolean hasKerb;

    StreetTier(int widthBlocks, int lampostSpacing,
               boolean cobblestone, boolean hasKerb) {
        this.widthBlocks    = widthBlocks;
        this.halfWidth      = (widthBlocks - 1) / 2;
        this.lampostSpacing = lampostSpacing;
        this.cobblestone    = cobblestone;
        this.hasKerb        = hasKerb;
    }

    /**
     * Returns the Minecraft road quality for {@link
     * tterrag1112.life_in_the_village.Village.Economy.Trade.RoadRouter.RoadQuality}
     * that matches this tier.
     */
    public tterrag1112.life_in_the_village.Village.Economy.Trade
            .RoadRouter.RoadQuality toRoadQuality() {
        return switch (this) {
            case PRIMARY   ->
                    tterrag1112.life_in_the_village.Village.Economy.Trade
                            .RoadRouter.RoadQuality.COBBLESTONE;
            case SECONDARY ->
                    tterrag1112.life_in_the_village.Village.Economy.Trade
                            .RoadRouter.RoadQuality.GRAVEL;
            case TERTIARY  ->
                    tterrag1112.life_in_the_village.Village.Economy.Trade
                            .RoadRouter.RoadQuality.DIRT;
        };
    }
}