package tterrag1112.life_in_the_village.Village.Planning.V2.Layer3;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import tterrag1112.life_in_the_village.Village.Decoration.Adjunct.AdjunctPlotType;

/**
 * B2.1 — Layer 3 reservation for an adjunct plot, planned alongside
 * the parent building's footprint and frontage. Carries the data
 * needed to materialise an
 * {@link tterrag1112.life_in_the_village.Village.Decoration.Adjunct.AdjunctPlot}
 * record once the parent {@link
 * tterrag1112.life_in_the_village.Village.Building#getId() building UUID}
 * is known (which doesn't happen until V2's spawn adapter calls
 * {@code BuildingPlacer.placeAndRegister}).
 *
 * <p>The legacy probe-and-place flow inside
 * {@code AdjunctPlotPlacer.tryPlace} has been retired: every
 * collision check, slope tolerance, and side preference is now
 * resolved during {@code PhasedPlanner.findBestCandidate}. The
 * realiser's job is purely to render reserved rectangles, never to
 * search for a fit.</p>
 *
 * @param type            the content type — drives the eventual
 *                        renderer (FORGE_YARD, KITCHEN_GARDEN, ...).
 *                        Resolved at planning time from
 *                        {@code AdjunctPlotRegistry}.
 * @param origin          plot footprint centre, at the building's
 *                        floor Y. Layer 3 stores the parent's
 *                        chosen Y; subsystem renderers may resample
 *                        the world heightmap when stamping content.
 * @param halfWidthX      half-extent along the parent's local X axis.
 * @param halfLengthZ     half-extent along the parent's local Z axis.
 * @param facingFromParent which side of the parent the adjunct
 *                        landed on, in absolute world coords.
 */
public record PlannedAdjunct(
        AdjunctPlotType type,
        BlockPos origin,
        int halfWidthX,
        int halfLengthZ,
        Direction facingFromParent) {
}
