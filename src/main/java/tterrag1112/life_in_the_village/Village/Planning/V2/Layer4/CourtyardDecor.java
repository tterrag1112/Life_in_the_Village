package tterrag1112.life_in_the_village.Village.Planning.V2.Layer4;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Utilities.Geometry.Polygon;

import java.util.List;

/**
 * Layout Rework — courtyard variant decoration carried planner→adapter render
 * (sibling to {@link InternalPath}; the courtyard's entry path rides the lane
 * seam, this carries the well + border enclosure):
 * <ul>
 *   <li>{@code wellCentre} — yard-centre point (surface-snapped) where the
 *       plaza {@code well_hamlet} stamp goes.</li>
 *   <li>{@code block} — the block AABB whose perimeter is enclosed by borders.</li>
 *   <li>{@code houseFootprints} — placed house AABBs, so border columns skip any
 *       perimeter cell a house sits on.</li>
 *   <li>{@code entryGate} — boundary point the entry path crosses; borders leave
 *       a gap there.</li>
 *   <li>{@code seed} — picks the (seed-varied) border style + RNG.</li>
 * </ul>
 * The well reuses the plaza centerpiece stamp; the borders reuse the farm border
 * generators — no new painters (see the adapter render).
 */
public record CourtyardDecor(BlockPos wellCentre, Polygon.AABB block,
                             List<Polygon.AABB> houseFootprints, BlockPos entryGate,
                             long seed) {}
