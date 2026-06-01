package tterrag1112.life_in_the_village.Village.Planning.V2.Layer3;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer4.RoadSegment;

/**
 * V2 Layer 3 → Layer 4 hand-off. Records a building's chosen
 * position, the actual variant footprint, the rotation, the
 * priority, the variant id, the frontage strip on the facing
 * road, the road segment the building faces, and an optional
 * adjunct-plot reservation (B2.1).
 *
 * <p>Both {@code frontage} and {@code facingRoad} are populated by
 * the phased planner at placement time. Layer 5 uses
 * {@code frontage} for facade decoration and {@code facingRoad}
 * for road-network adjacency queries.
 *
 * <p>{@code adjunct} is non-null when the building's manifest or
 * the {@code AdjunctPlotRegistry} declared an adjunct, AND the
 * planner found an acceptable side. The V2 spawn adapter
 * materialises a persisted
 * {@link tterrag1112.life_in_the_village.Village.Decoration.Adjunct.AdjunctPlot}
 * once the parent building's UUID is known.
 *
 * <p>{@code parcel} (Layout Rework Stage 2a) is non-null for FARMHOUSE
 * / MARKET lead buildings when the planner reserved an interior complex
 * budget box. Stage 2b seeds the farm/market complex planners from it.
 */
public record PlacedBuilding(
        BuildingType type,
        BlockPos centre,
        Footprint footprint,
        Rotation rotation,
        Priority priority,
        String variantId,
        FrontageStrip frontage,
        RoadSegment facingRoad,
        PlannedAdjunct adjunct,
        Parcel parcel) {

    /** Convenience overload that defaults {@code adjunct} + {@code parcel}
     *  to null — preserves callers that don't plan adjuncts (foundation
     *  phase, iterative phase fallbacks). */
    public PlacedBuilding(BuildingType type, BlockPos centre,
                          Footprint footprint, Rotation rotation,
                          Priority priority, String variantId,
                          FrontageStrip frontage, RoadSegment facingRoad) {
        this(type, centre, footprint, rotation, priority, variantId,
                frontage, facingRoad, null, null);
    }

    /** Convenience overload that defaults {@code parcel} to null —
     *  preserves callers that plan an adjunct but no complex parcel. */
    public PlacedBuilding(BuildingType type, BlockPos centre,
                          Footprint footprint, Rotation rotation,
                          Priority priority, String variantId,
                          FrontageStrip frontage, RoadSegment facingRoad,
                          PlannedAdjunct adjunct) {
        this(type, centre, footprint, rotation, priority, variantId,
                frontage, facingRoad, adjunct, null);
    }
}
