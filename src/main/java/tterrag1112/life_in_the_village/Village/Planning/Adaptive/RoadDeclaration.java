package tterrag1112.life_in_the_village.Village.Planning.Adaptive;

import tterrag1112.life_in_the_village.Village.Decoration.Roads.RoadShape;
import tterrag1112.life_in_the_village.Village.Planning.Graph.EdgeRole;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.RoadPrimitive;

/**
 * Declarative road specification produced by a recipe. The recipe
 * instantiates the {@link RoadPrimitive} (existing classes:
 * StraightRoad, Spur, Arc, Ring) and binds it to a stable
 * {@link EdgeRef}. The planner runs the primitive at realisation time
 * and attaches the resulting {@code RoadResult} to the EdgeRef on
 * PlanContext, where {@link tterrag1112.life_in_the_village.Village
 * .Planning.Primitives.PlanContext#findEdge} can resolve it.
 *
 * <p>The role enum is the existing {@link EdgeRole} (SPINE, SPUR,
 * RING, OUTER_RING). The spec doc named this {@code RoadRole}; that
 * enum doesn't exist in this codebase — EdgeRole serves the same
 * purpose.
 */
public record RoadDeclaration(
        EdgeRef ref,
        EdgeRole role,
        RoadShape.RoadTier tier,
        RoadPrimitive primitive
) {}
