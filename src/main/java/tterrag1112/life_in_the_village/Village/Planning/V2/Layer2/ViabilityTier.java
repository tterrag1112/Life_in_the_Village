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
 *
 * <p>{@code minPopulation}/{@code maxPopulation} is the target NPC
 * population band for the tier, consumed by {@code PopulationRoster}
 * (Layout Rework Step 3) to derive building counts population-first.
 * The roster samples a target within {@code [min,max]} deterministically
 * from the site seed, then sizes housing + services to hit it. These
 * are starting values meant for in-world tuning. (Distinct from
 * {@code targetBuildingCount}, which Layer-2 {@code StrategyConditions}
 * still reads for strategy tier-gating.)
 */
public enum ViabilityTier {
    CITY    (50, 6400, 0.15f, 70, 150),
    TOWN    (25, 1600, 0.25f, 30,  55),
    HAMLET  (10, 400,  0.40f, 12,  30),
    OUTPOST (4,  100,  0.60f,  5,  12),
    UNVIABLE(0,  0,    1.0f,   0,   0);

    public final int targetBuildingCount;
    public final int minFlatRegionBlocks;
    public final float maxSlopeFraction;
    public final int minPopulation;
    public final int maxPopulation;

    ViabilityTier(int targetBuildingCount, int minFlatRegionBlocks,
                  float maxSlopeFraction, int minPopulation, int maxPopulation) {
        this.targetBuildingCount = targetBuildingCount;
        this.minFlatRegionBlocks = minFlatRegionBlocks;
        this.maxSlopeFraction = maxSlopeFraction;
        this.minPopulation = minPopulation;
        this.maxPopulation = maxPopulation;
    }
}
