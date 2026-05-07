package tterrag1112.life_in_the_village.Village.Planning.V2.Layer2;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * V2 trade-road hub. Designated at each spine path endpoint after
 * Phase 4 finalises the road network. Future trade-road graph
 * extension uses {@code direction} as the outward tangent.
 *
 * @param position   the hub block position (the spine path endpoint)
 * @param direction  raw local tangent at the endpoint (may be diagonal
 *                   if the endpoint lies on an arc-deflected segment;
 *                   trade roads can curve so we keep the unsnapped
 *                   tangent here)
 * @param openness   average admissibility (0..1) in a 50-block fan
 *                   extending from {@code position} along
 *                   {@code direction}. The hub with higher openness
 *                   is the village's "main" hub.
 */
public record Hub(BlockPos position, Vec3 direction, double openness) {
}
