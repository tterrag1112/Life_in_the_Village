package tterrag1112.life_in_the_village.Village.Planning.V2.Layer2;

import tterrag1112.life_in_the_village.Village.Planning.Primitives.RoadPrimitive;

/**
 * Track E1 prompt-3 — a graph edge in the village road network.
 *
 * <p>An edge carries the {@link RoadPrimitive} instance that
 * realises it (its centerline is owned by the primitive, not
 * duplicated here). Width is the painted-strip width the road
 * realiser applies for this edge — typically {@code 3} for
 * VILLAGE_ROAD tier; the network grower may bump it for primary
 * roads on larger tiers.
 *
 * @param id          stable identifier ({@code "e0"}, {@code "e1"}, …)
 * @param fromNodeId  id of the source {@link NetworkNode}
 * @param toNodeId    id of the target {@link NetworkNode}
 * @param primitive   road primitive that realises this edge
 * @param width       painted strip width in blocks
 */
public record NetworkEdge(
        String id,
        String fromNodeId,
        String toNodeId,
        RoadPrimitive primitive,
        int width) {
    public NetworkEdge {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("NetworkEdge id must be non-blank");
        }
        if (fromNodeId == null || toNodeId == null) {
            throw new IllegalArgumentException("NetworkEdge node ids required");
        }
        if (primitive == null) {
            throw new IllegalArgumentException("NetworkEdge primitive required");
        }
        if (width <= 0) {
            throw new IllegalArgumentException("NetworkEdge width must be positive");
        }
    }
}
