package tterrag1112.life_in_the_village.Village.Planning.V2.Layer4;

import tterrag1112.life_in_the_village.Village.Decoration.Roads.RoadShape;

/**
 * V2 road tier — categorises roles in the V2 road graph (spine,
 * connectors, ring). Distinct from V1's
 * {@link RoadShape.RoadTier} which categorises material/width.
 *
 * <p>{@link #v1Tier()} maps each V2 role to the V1 tier the
 * underlying primitive should be constructed with. V1 has no
 * {@code RING_ROAD} tier today; rings render as
 * {@link RoadShape.RoadTier#VILLAGE_PATH}.
 */
public enum RoadTier {
    VILLAGE_ROAD(RoadShape.RoadTier.VILLAGE_ROAD),
    VILLAGE_PATH(RoadShape.RoadTier.VILLAGE_PATH),
    RING_ROAD(RoadShape.RoadTier.VILLAGE_PATH);

    private final RoadShape.RoadTier v1Tier;

    RoadTier(RoadShape.RoadTier v1Tier) {
        this.v1Tier = v1Tier;
    }

    public RoadShape.RoadTier v1Tier() {
        return v1Tier;
    }
}
