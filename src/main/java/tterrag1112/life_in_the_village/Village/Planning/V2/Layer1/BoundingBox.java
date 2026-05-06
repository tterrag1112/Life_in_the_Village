package tterrag1112.life_in_the_village.Village.Planning.V2.Layer1;

/**
 * Inclusive 2D (XZ) bounding box in world coordinates. Used by the
 * V2 Layer 1 region records (HighGround, ForestRegion, StoneRegion).
 *
 * <p>Layer 1 regions are derived from a cell grid; a 2D bbox suffices.
 * Y information lives on the region records themselves where it
 * matters (e.g. {@link HighGround#peak}).
 */
public record BoundingBox(int minX, int minZ, int maxX, int maxZ) {
}
