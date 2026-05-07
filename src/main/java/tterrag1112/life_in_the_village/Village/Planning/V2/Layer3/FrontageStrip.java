package tterrag1112.life_in_the_village.Village.Planning.V2.Layer3;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * V2 building frontage. Records the strip of public street the
 * building occupies along the road it faces.
 *
 * <p>Carried on {@link PlacedBuilding}; reservation overlaps must
 * include the strip so adjacent buildings don't claim cells in
 * front of each other. The strip is also what Layer 5 plaza /
 * decoration code uses to know "this is the building's facade
 * side".
 *
 * @param buildingFront  midpoint of the building's front edge
 *                       (XZ at the front edge centre, Y at building
 *                       elevation)
 * @param frontDirection unit vector outward toward the road
 *                       (snapped to a cardinal in V1)
 * @param width          road width of the facing road (= 3 in V1)
 * @param length         building's footprint dimension parallel to
 *                       the road = the front edge length
 */
public record FrontageStrip(BlockPos buildingFront, Vec3 frontDirection,
                            int width, int length) {
}
