package tterrag1112.life_in_the_village.Village.Planning;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Village.Buildings.FarmPlot;

/**
 * Phase 17: planning-time farm plot specification.
 *
 * <p>Emitted alongside a {@link tterrag1112.life_in_the_village.Village
 * .Planning.Zoning.PlacementSlot} by
 * {@code RecipeHelpers.emitFarmPlotSlots}, validated and claimed by
 * {@code VillagePlanner.runFarmPlotPass}, then consumed by
 * {@code FarmPlotPlacer.placeAll} at realisation. The realiser
 * regenerates the plot's organic shape from {@link #edgeJitterSeed}
 * so plot boundaries are reproducible across save/reload — only the
 * planning-time half-dimensions and a deterministic seed are stored.
 *
 * <p>The owner reference is a {@link BlockPos}, not a UUID, because
 * Building UUIDs do not exist at planning time. The realiser resolves
 * the planned position to the persisted {@code Building.getId()} by
 * looking up the farmhouse whose origin is closest to
 * {@code ownerFarmhousePos}.
 *
 * @param ownerFarmhousePos position of the FARMHOUSE LayoutSlot the
 *                          plot was claimed under; the realiser
 *                          maps this to a Building UUID
 * @param subtype           CROP_FIELD or ANIMAL_PEN
 * @param halfW             plot half-width (X axis); realiser builds
 *                          the shape with this as the base
 * @param halfL             plot half-length (Z axis); base for the
 *                          shape generator
 * @param edgeJitterSeed    seed for the realiser's per-column edge
 *                          random offsets — picks the same shape on
 *                          every realisation pass for the same plot
 */
public record FarmPlotSpec(
        BlockPos ownerFarmhousePos,
        FarmPlot.PlotSubtype subtype,
        int halfW,
        int halfL,
        int edgeJitterSeed) {
}
