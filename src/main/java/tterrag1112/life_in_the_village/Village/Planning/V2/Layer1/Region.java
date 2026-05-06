package tterrag1112.life_in_the_village.Village.Planning.V2.Layer1;

import net.minecraft.core.BlockPos;

/**
 * A connected component summary used by {@link V2FeatureMap}'s
 * aggregate queries — flat regions, exposed-stone regions, etc.
 *
 * @param centre  world-space BlockPos at the component's centroid (y is
 *                the centroid cell's surface y)
 * @param area    cell count in the component (NOT a block area —
 *                multiply by {@code cellSize²} for blocks)
 * @param minX    inclusive bbox min X in world coords
 * @param minZ    inclusive bbox min Z in world coords
 * @param maxX    inclusive bbox max X in world coords
 * @param maxZ    inclusive bbox max Z in world coords
 */
public record Region(BlockPos centre, int area,
                     int minX, int minZ, int maxX, int maxZ) {
}
