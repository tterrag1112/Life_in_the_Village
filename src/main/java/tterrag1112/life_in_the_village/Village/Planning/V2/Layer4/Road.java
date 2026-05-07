package tterrag1112.life_in_the_village.Village.Planning.V2.Layer4;

import tterrag1112.life_in_the_village.Village.Planning.Primitives.RoadPrimitive;

/**
 * V2 road. Wraps an existing V1 {@link RoadPrimitive} with the V2
 * {@link RoadTier} category and the culture's road material id.
 *
 * @param tier      V2 categorisation (spine / connector / ring)
 * @param primitive the underlying V1 primitive that produces a
 *                  centerline at decoration time
 * @param material  block id from {@code culture.roadMaterial()}
 *                  (e.g. {@code "minecraft:dirt_path"}). Layer 5
 *                  resolves it to actual blocks.
 */
public record Road(RoadTier tier, RoadPrimitive primitive, String material) {
}
