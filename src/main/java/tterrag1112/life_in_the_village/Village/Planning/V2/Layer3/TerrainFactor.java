package tterrag1112.life_in_the_village.Village.Planning.V2.Layer3;

/**
 * Per-position terrain scoring inputs. Each factor maps to a 0..1
 * value computed from the {@code V2FeatureMap} cell at the candidate
 * position; {@link PlacementProfile#terrainWeights} multiplies them
 * to form the position's terrain score.
 *
 * <p>Higher value = more attractive. Inverted distance fields use
 * {@code 1 / (1 + dist/villageRadius)} so the score is 1.0 at the
 * source and decays smoothly with distance.
 */
public enum TerrainFactor {
    /** {@code 1 - min(localSlope/4, 1)} — flat = 1, steep = 0. */
    FLAT,
    /** Inverted normalised distance to nearest WATER cell. */
    NEAR_WATER,
    /** Inverted normalised distance to nearest FOREST cell. */
    NEAR_FOREST,
    /** Inverted normalised distance to nearest STONE_EXPOSED cell. */
    NEAR_STONE,
    /** Inverted normalised distance to the nearest coastline boundary
     *  point. 0 if the scan has no coastline. */
    NEAR_COAST
}
