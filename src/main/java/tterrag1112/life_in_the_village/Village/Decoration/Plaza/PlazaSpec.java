package tterrag1112.life_in_the_village.Village.Decoration.Plaza;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.Optional;

/**
 * Doc 04 §"Plaza generation algorithm" — input bundle for
 * {@link PlazaGenerator#generate(tterrag1112.life_in_the_village
 * .Village.Planning.Primitives.PlanContext, PlazaSpec)}.
 *
 * <p>Recipes build a spec describing what they want, hand it to the
 * generator, and the generator deals with shape generation, terrain
 * accommodation, and registration on PlanContext.</p>
 *
 * @param purpose          CIVIC / MARKET / RELIGIOUS_COURTYARD —
 *                         drives interior content (vendor reservation
 *                         etc.); does not affect polygon generation
 * @param targetCenter     world position where the plaza should land;
 *                         not necessarily geometric village centre
 * @param targetArea       blocks² target — tier-derived (VILLAGE=50,
 *                         TOWN=150, CITY=300). Actual area may shrink
 *                         due to terrain accommodation
 * @param preferredShape   layout-derived shape preference
 * @param majorAxis        long-axis hint for LINEAR shapes
 *                         ({@link Optional#empty()} for non-LINEAR);
 *                         shapes other than LINEAR ignore it
 */
public record PlazaSpec(
        PlazaPurpose purpose,
        BlockPos targetCenter,
        int targetArea,
        PlazaShape preferredShape,
        Optional<Direction> majorAxis
) {
    public PlazaSpec {
        if (purpose == null) throw new IllegalArgumentException("purpose");
        if (targetCenter == null) throw new IllegalArgumentException("targetCenter");
        if (preferredShape == null) throw new IllegalArgumentException("preferredShape");
        if (majorAxis == null) majorAxis = Optional.empty();
        if (targetArea < 9) targetArea = 9;
    }
}
