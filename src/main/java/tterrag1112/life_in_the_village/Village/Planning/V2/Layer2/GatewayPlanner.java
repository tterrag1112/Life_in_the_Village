package tterrag1112.life_in_the_village.Village.Planning.V2.Layer2;

import net.minecraft.core.BlockPos;

/**
 * Layout Rework Stage 3b — gateways-first derivation, extracted from
 * {@link NetworkPlanner} (behaviour-preserving).
 *
 * <p>Computes the two gateway positions from {@code primaryPos +
 * primaryAxis + tier}, projecting a symmetric pair along the primary
 * axis at {@link #gatewayDistance}. This is exactly the math
 * {@code NetworkPlanner} ran inline through Stage 3a; pulling it out
 * makes the gateways available <i>before</i> and independent of the
 * network — required terminals for the Stage 3b block-serving router,
 * and (Stage 3d) the source of hub/gate positions. {@code NetworkPlanner}
 * now consumes the result via {@link SiteContext#gateways()}.
 */
public final class GatewayPlanner {

    private GatewayPlanner() {}

    /**
     * Derive the symmetric gateway pair.
     *
     * @param primaryPos the primary anchor centre (or the site anchor
     *                   when there is no primary anchor)
     * @param axis       the site's primary cardinal axis
     * @param tier       village size class — scales the projection
     *                   distance
     */
    public static Gateways derive(BlockPos primaryPos, CardinalAxis axis,
                                  ViabilityTier tier) {
        int dist = gatewayDistance(tier);
        BlockPos primary = step(primaryPos,
                axis == CardinalAxis.X ? +1 : 0,
                axis == CardinalAxis.X ? 0 : +1,
                dist);
        BlockPos secondary = step(primaryPos,
                axis == CardinalAxis.X ? -1 : 0,
                axis == CardinalAxis.X ? 0 : -1,
                dist);
        return new Gateways(primary, secondary);
    }

    /** Projection distance per tier (blocks). Unchanged from the
     *  pre-extraction {@code NetworkPlanner.gatewayDistance}. */
    public static int gatewayDistance(ViabilityTier tier) {
        return switch (tier) {
            case CITY    -> 80;
            case TOWN    -> 50;
            case HAMLET  -> 24;
            case OUTPOST, UNVIABLE -> 12;
        };
    }

    private static BlockPos step(BlockPos from, double dirX, double dirZ, int n) {
        return new BlockPos(
                from.getX() + (int) Math.round(dirX * n),
                from.getY(),
                from.getZ() + (int) Math.round(dirZ * n));
    }
}
