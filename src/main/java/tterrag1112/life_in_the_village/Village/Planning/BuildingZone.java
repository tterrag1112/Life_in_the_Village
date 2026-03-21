// src/main/java/tterrag1112/life_in_the_village/Village/Planning/BuildingZone.java
package tterrag1112.life_in_the_village.Village.Planning;

/**
 * Functional zones that govern where each building type is placed
 * within the ring layout.
 *
 * <h3>Angular sector model</h3>
 * The village is divided into angular sectors centred on the town
 * hall. Each zone "owns" a preferred angular range. The planner
 * tries to place a building within its zone's sector; if no valid
 * slot is available there, it falls back to any free slot.
 *
 * <pre>
 *           DEFENSIVE (top/outer)
 *        270°          90°
 *  AGRI  ──────  TH  ──────  PROD
 *        180°
 *     RESIDENTIAL (bottom ring)
 *           CIVIC (near square)
 * </pre>
 */
public enum BuildingZone {

    /**
     * Town hall, guild hall, inn, market, town square.
     * Placed immediately around the town square — the social heart.
     * Preferred sector: near the square (south of town hall by default).
     */
    CIVIC(
            -45, 45,       // preferred angle range (degrees, relative to south)
            1,             // ring priority: fill ring 1 first
            true           // must be on a primary street
    ),

    /**
     * Blacksmith, carpentry, mill, bakery, candlemaker, weaver.
     * Placed on one side — downhill or downwind.
     * Preferred sector: east quadrant.
     */
    PRODUCTION(
            45, 135,
            1,
            true
    ),

    /**
     * Houses. Fill the remaining ring slots.
     * Preferred sector: any — distributed evenly.
     */
    RESIDENTIAL(
            0, 360,        // no preference — fill everywhere
            2,             // prefer ring 2 and beyond
            false
    ),

    /**
     * Farmhouses and farm plots. Placed in the flattest direction.
     * Preferred sector: determined by TerrainProfile.bestFlatDir.
     */
    AGRICULTURAL(
            180, 270,      // default south-west; overridden by flat direction
            2,
            false
    ),

    /**
     * Guard tower, watchtower, barracks, prison.
     * Placed on the outermost ring at high ground.
     * Preferred sector: outer ring, elevated terrain.
     */
    DEFENSIVE(
            0, 360,        // placed on outermost available ring
            3,             // ring 3 preferred
            false
    );

    /** Start angle of the preferred sector (degrees, clockwise from south). */
    public final int sectorStart;
    /** End angle of the preferred sector (degrees). */
    public final int sectorEnd;
    /** Preferred ring index (1 = innermost, 3 = outermost). */
    public final int preferredRing;
    /** Whether this zone requires placement adjacent to a primary street. */
    public final boolean requiresPrimaryStreet;

    BuildingZone(int sectorStart, int sectorEnd,
                 int preferredRing, boolean requiresPrimaryStreet) {
        this.sectorStart           = sectorStart;
        this.sectorEnd             = sectorEnd;
        this.preferredRing         = preferredRing;
        this.requiresPrimaryStreet = requiresPrimaryStreet;
    }

    /**
     * Returns true if {@code angleDeg} (0–360, clockwise from south)
     * falls within this zone's preferred sector.
     */
    public boolean containsAngle(double angleDeg) {
        if (sectorStart == 0 && sectorEnd == 360) return true;
        double norm = ((angleDeg % 360) + 360) % 360;
        if (sectorStart <= sectorEnd) {
            return norm >= sectorStart && norm <= sectorEnd;
        } else {
            // Wraps around 0°
            return norm >= sectorStart || norm <= sectorEnd;
        }
    }
}