package tterrag1112.life_in_the_village.Village.Planning.V2.Layer2;

/**
 * V2 viability tier — the size class a site can support, derived in
 * Layer 2 from {@link tterrag1112.life_in_the_village.Village.Planning
 * .V2.Layer1.V2FeatureMap}'s flat-region area and overall slope
 * fraction.
 *
 * <p>{@code minFlatRegionBlocks} is in BLOCK units (cell count ×
 * cellSize²), which makes the design-doc thresholds (80×80 = 6400)
 * directly comparable.
 *
 * <p>{@code maxSlopeFraction} is the maximum permitted fraction of
 * cells with {@code localSlope > 2}. Higher tiers demand flatter
 * sites; OUTPOST tolerates up to 60% steep terrain.
 */
public enum ViabilityTier {
    CITY    (50, 6400, 0.15f),
    TOWN    (25, 1600, 0.25f),
    HAMLET  (10, 400,  0.40f),
    OUTPOST (4,  100,  0.60f),
    UNVIABLE(0,  0,    1.0f);

    public final int targetBuildingCount;
    public final int minFlatRegionBlocks;
    public final float maxSlopeFraction;

    ViabilityTier(int targetBuildingCount, int minFlatRegionBlocks,
                  float maxSlopeFraction) {
        this.targetBuildingCount = targetBuildingCount;
        this.minFlatRegionBlocks = minFlatRegionBlocks;
        this.maxSlopeFraction = maxSlopeFraction;
    }
}
