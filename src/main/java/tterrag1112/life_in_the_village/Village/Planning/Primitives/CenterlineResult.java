package tterrag1112.life_in_the_village.Village.Planning.Primitives;

import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * Result of {@link RoadPrimitive#computeCenterline(PrimitiveContext)}.
 *
 * <p>Phase 16 plumbing. Replaces the old {@code List<BlockPos>} return.
 * Carries the centerline points that were accepted plus a reason
 * explaining why the walk stopped — {@link TerminationReason#COMPLETED}
 * if the full geometric path was traversable, otherwise the specific
 * refusal kind.
 *
 * @param points     centerline points, surface-snapped, in walk order.
 *                   Never null; may be empty if termination happened
 *                   before any point was accepted.
 * @param reason     why the centerline ended.
 * @param refusedAt  the geometric position the primitive was about to
 *                   sample when it stopped, or null if reason is
 *                   COMPLETED. Useful for diagnostics — does NOT appear
 *                   in {@code points}.
 */
public record CenterlineResult(
        List<BlockPos> points,
        TerminationReason reason,
        BlockPos refusedAt) {

    public boolean isComplete() {
        return reason == TerminationReason.COMPLETED;
    }

    public boolean isEmpty() {
        return points.isEmpty();
    }

    public BlockPos lastPoint() {
        return points.isEmpty() ? null : points.get(points.size() - 1);
    }

    public static CenterlineResult complete(List<BlockPos> points) {
        return new CenterlineResult(points, TerminationReason.COMPLETED, null);
    }
}
