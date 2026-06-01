package tterrag1112.life_in_the_village.Village.Planning.V2.Layer2;

import net.minecraft.core.BlockPos;

/**
 * Layout Rework Stage 3b — the two village gateway positions, derived
 * before the road network so they are available as router terminals and
 * (in Stage 3d) as the source of {@code RealizedLayout} gate positions.
 *
 * <p>A symmetric pair projected from the primary position along the
 * site's primary axis (±). Both ends are well-defined so axis-aligned
 * and feature-aligned spines have stable endpoints to attach trade
 * roads to.
 *
 * @param primary   the +axis gateway (the "PRIMARY" gate)
 * @param secondary the −axis gateway (the "SECONDARY" gate)
 */
public record Gateways(BlockPos primary, BlockPos secondary) {
}
