package tterrag1112.life_in_the_village.Village.Planning.Adaptive;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.RoadShape;
import tterrag1112.life_in_the_village.Village.Planning.Graph.EdgeRole;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.RoadResult;

import java.util.List;

/**
 * Post-realisation snapshot of a {@link RoadDeclaration} after the
 * planner has invoked the primitive's {@code computeCenterline}.
 * Carries the centerline, role, tier, and {@link RoadResult} (which
 * has {@code isComplete()} + {@code TerminationReason}).
 *
 * <p>Distinct from {@code RoadGraph.Edge} (the pre-realisation graph
 * record): RoadGraph.Edge has node IDs and the primitive but no
 * RoadResult / truncation-status. RealisedEdge is what
 * {@link Anchor} resolvers (Phase B) need to look up — given a
 * {@link EdgeRef}, get the centerline and the truncation outcome.
 *
 * <p>Stored on PlanContext via {@code registerRealisedEdge} during
 * the planner's {@code realiseRoads} stage.
 */
public record RealisedEdge(
        EdgeRef ref,
        EdgeRole role,
        RoadShape.RoadTier tier,
        List<BlockPos> centerline,
        RoadResult result
) {
    public RealisedEdge {
        centerline = List.copyOf(centerline);
    }

    public boolean isComplete() {
        return result.isComplete();
    }
}
