package tterrag1112.life_in_the_village.Village.Farms.Complex;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Utilities.Geometry.Polygon;
import tterrag1112.life_in_the_village.Village.Buildings.FarmPlot.CropType;

/**
 * Detour A — intermediate plot description emitted by
 * {@link BspSubdivider} and consumed by
 * {@link PathTopologyPlanner} / {@link BorderStyleAssigner}.
 *
 * <p>Distinct from
 * {@link tterrag1112.life_in_the_village.Village.Buildings.FarmPlot}:
 * that's the persisted, NPC-facing record (carries a farmhouseId,
 * codec, plot subtype, etc.). This intermediate type carries only
 * what the algorithms need — the polygon and the crop assignment
 * — so each Stage-2 function can stay free of the wider persistence
 * surface.
 *
 * <p>Stage 4's {@code FarmComplexPlanner} composes
 * {@code BspSubdivider} output with farmhouse identity to mint the
 * final {@code FarmPlot} records.
 *
 * @param plotIndex zero-based index within the subdivision result;
 *                  stable across re-runs with the same seed, used as
 *                  the deterministic tiebreak in border assignment.
 * @param polygon   plot bounds (already clipped against the region
 *                  polygon and against {@code buildingBounds} where
 *                  applicable).
 * @param centroid  cached centroid for path-topology branching.
 * @param crop      assigned crop type from the spec's
 *                  {@code plotTypeMix} weighted draw.
 * @param cellCount number of arable cells inside the polygon — the
 *                  true plot area in BSP cells, separate from polygon
 *                  area (region-polygon clipping can leave a leaf
 *                  with fewer cells than the bbox suggests). Used by
 *                  crop-by-size matching in
 *                  {@link tterrag1112.life_in_the_village.Village.Farms.Complex.CropSizePreferences}.
 */
public record PlotPlan(int plotIndex,
                       Polygon polygon,
                       BlockPos centroid,
                       CropType crop,
                       int cellCount) {
}
