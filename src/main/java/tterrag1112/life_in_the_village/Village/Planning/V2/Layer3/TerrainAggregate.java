package tterrag1112.life_in_the_village.Village.Planning.V2.Layer3;

/**
 * Hard-required terrain aggregates. A building whose
 * {@link PlacementProfile#requiresAggregates} lists one of these is
 * dropped at the {@link BuildingSelector} stage if the
 * {@code V2FeatureMap} doesn't contain that aggregate.
 *
 * <p>Examples: MILLER requires {@link #RIVER}; MINE requires
 * {@link #STONE_REGION}; WOODCUTTER requires {@link #FOREST_REGION}.
 */
public enum TerrainAggregate {
    RIVER,
    COASTLINE,
    FOREST_REGION,
    STONE_REGION,
    HIGH_GROUND
}
