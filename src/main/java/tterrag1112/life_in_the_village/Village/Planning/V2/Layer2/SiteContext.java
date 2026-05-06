package tterrag1112.life_in_the_village.Village.Planning.V2.Layer2;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import tterrag1112.life_in_the_village.Village.Planning.V2.Culture.Culture;
import tterrag1112.life_in_the_village.Village.Planning.V2.Inclination;

/**
 * V2 Layer 2 → Layer 3 hand-off. Carries the four decisions Layer 2
 * makes for a candidate village site.
 *
 * <p>{@code primarySpineDirection} is a unit vector in the XZ plane
 * (y = 0). Layer 4 may discretise to cardinals if needed; Layer 2
 * keeps it continuous.
 *
 * <p>If {@code tier == UNVIABLE}, the other fields are populated for
 * debug-output convenience but the planner should bail before
 * Layer 3.
 */
public record SiteContext(
        BlockPos anchor,
        Vec3 primarySpineDirection,
        ViabilityTier tier,
        Inclination inclination,
        Culture culture,
        long seed) {
}
