package tterrag1112.life_in_the_village.Village.Planning.Primitives;

import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * Phase 16b: per-primitive truncation-aware result captured by recipes.
 *
 * <p>Wraps a {@link CenterlineResult} with the primitive that produced it
 * plus the intended vs actual length of the centerline. Recipes capture
 * one of these per {@link RoadPrimitive#computeCenterline} call and pass
 * it into slot emission; emission consumes {@link #completionRatio()}
 * to clamp candidate slots to the road's actual reach.
 *
 * <p>The point count is used as a length proxy — primitives produce
 * roughly one centerline point per block traversed, so {@code centerline
 * .size()} is comparable to {@link RoadPrimitive#intendedLength()}.
 *
 * @param primitive       the primitive whose centerline this result wraps
 * @param centerline      points actually accepted (= {@code raw.points()})
 * @param isComplete      true when the primitive walked its full geometry
 * @param reason          truncation reason; may be COMPLETED when complete
 *                        (kept non-null for stable downstream switches)
 * @param intendedLength  primitive's pre-truncation target length in blocks
 * @param actualLength    {@code centerline.size()} (cached for clarity)
 */
public record RoadResult(
        RoadPrimitive primitive,
        List<BlockPos> centerline,
        boolean isComplete,
        TerminationReason reason,
        int intendedLength,
        int actualLength) {

    /**
     * Fraction of intended geometry that was actually traversed.
     * 1.0 when the road completed; 0.0 when nothing was accepted.
     * Cascade engine compares against thresholds to choose status.
     */
    public double completionRatio() {
        return intendedLength <= 0 ? 1.0
                : Math.min(1.0, (double) actualLength / intendedLength);
    }
}
