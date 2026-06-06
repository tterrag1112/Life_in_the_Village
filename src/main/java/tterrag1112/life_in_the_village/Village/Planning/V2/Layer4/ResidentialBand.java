package tterrag1112.life_in_the_village.Village.Planning.V2.Layer4;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Utilities.Geometry.Polygon;

import java.util.List;

/**
 * Part 2a — the residential centrality band carried planner→adapter so the
 * adapter can (a) fill the band's leftover with green-commons subdistricts and
 * (b) skip the band when running the village-wide park finder (de-dup).
 *
 * <ul>
 *   <li>{@code centre} / {@code innerR} / {@code outerR} — the band annulus
 *       around the anchor (for the post-pass park de-dup).</li>
 *   <li>{@code greens} — leftover blocks seated in the band (via the same seat
 *       sweep as precincts, so they avoid civic/market/precincts and join
 *       {@code residentialGates} → get a district node → the router connects
 *       them). The adapter renders each as a COTTAGE_GREEN {@code GardenPlot} so
 *       the band reads finished, never bald grass.</li>
 * </ul>
 */
public record ResidentialBand(BlockPos centre, int innerR, int outerR,
                              List<Polygon.AABB> greens) {

    /** True if (x,z) is within the band's OUTER radius — used to skip the
     *  village-wide park finder inside the band (it fills itself). */
    public boolean withinOuter(int x, int z) {
        long dx = x - centre.getX(), dz = z - centre.getZ();
        long r = outerR;
        return dx * dx + dz * dz <= r * r;
    }
}
