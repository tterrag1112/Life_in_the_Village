package tterrag1112.life_in_the_village.Village.Planning.V2.Layer1;

/**
 * Per-cell scan record for {@link V2FeatureMap}. Mutable so the BFS
 * distance fields can be filled in after the initial classification
 * pass; everything else is final after construction.
 */
public final class Cell {

    private static final int UNREACHED = Integer.MAX_VALUE;

    private final int elevationY;
    private final int localSlope;
    private final BlockCategory category;
    /** Effective water y at this cell if it is WATER (or the water
     *  height observed for SHORE classification); -1 otherwise. */
    private final int waterY;

    private int distToWater = UNREACHED;
    private int distToForest = UNREACHED;
    private int distToStone = UNREACHED;
    /** Layout Rework Stage 3a — terrain-warped cost-distance from the
     *  village anchor, filled by Layer 2's ZonePartition after the scan
     *  (mirrors the BFS dist fields, but the cost-distance flows around
     *  water/cliffs). {@link #UNREACHED} when the cell is non-buildable
     *  or unreachable from the anchor. */
    private int distToAnchor = UNREACHED;

    public Cell(int elevationY, int localSlope, BlockCategory category, int waterY) {
        this.elevationY = elevationY;
        this.localSlope = localSlope;
        this.category = category;
        this.waterY = waterY;
    }

    public int elevationY() { return elevationY; }
    public int localSlope() { return localSlope; }
    public BlockCategory category() { return category; }
    public int waterY() { return waterY; }

    public int distToWater()  { return distToWater; }
    public int distToForest() { return distToForest; }
    public int distToStone()  { return distToStone; }
    public int distToAnchor() { return distToAnchor; }

    void setDistToWater(int d)  { this.distToWater = d; }
    void setDistToForest(int d) { this.distToForest = d; }
    void setDistToStone(int d)  { this.distToStone = d; }
    void setDistToAnchor(int d) { this.distToAnchor = d; }
}
