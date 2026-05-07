package tterrag1112.life_in_the_village.Village.Planning.V2.Layer3;

/**
 * V2 building footprint — XZ dimensions of the actual variant NBT
 * after rotation has been applied (or pre-rotation if rotation is
 * NONE). Carried on {@link PlacedBuilding} so Layers 4 and 5 work
 * with real dimensions, not the type-default-variant assumption
 * that bit V1.
 *
 * <p>Distinct from V1's {@code StructureSizeCache.FootprintInfo}
 * because V2 doesn't carry an {@code overlapRadius}; reservation
 * checks use AABB rectangles instead of chebyshev radii.
 */
public record Footprint(int width, int length) {
}
