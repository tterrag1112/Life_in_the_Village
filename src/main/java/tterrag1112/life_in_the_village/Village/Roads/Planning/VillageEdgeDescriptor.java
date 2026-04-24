package tterrag1112.life_in_the_village.Village.Roads.Planning;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Village.Roads.Graph.VillageRoadEdge;

import java.util.List;

/**
 * Lightweight descriptor for one internal village road edge, produced by
 * {@link tterrag1112.life_in_the_village.Village.Planning.Primitives.ShapeRecipe#describeInternalRoads}.
 *
 * <p>Descriptors are converted to {@link VillageRoadEdge} objects (with INTERIOR or GATEWAY
 * node endpoints) by {@link InternalRoadCommitter} after the gateway nodes are present in
 * the graph.
 *
 * @param fromPos  start position — should match a gateway position or an interior junction
 * @param toPos    end position — should match a gateway position or an interior junction
 * @param path     block-granularity positions along this edge (may be empty for a stub)
 * @param character visual and traversal character of the edge
 */
public record VillageEdgeDescriptor(
        BlockPos fromPos,
        BlockPos toPos,
        List<BlockPos> path,
        VillageRoadEdge.EdgeCharacter character
) {}
