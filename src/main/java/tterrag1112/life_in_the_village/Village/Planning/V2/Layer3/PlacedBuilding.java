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
 * <p>Layout Rework Stage 3c (the roads-last flip): {@code frontage}
 * and {@code facingRoad} are now <b>nullable</b>. Buildings are placed
 * into zones position-only with both null; the post-routing orientation
 * pass fills them via {@link #withOrientation} once the real road network
 * exists. Readers must tolerate a null {@code facingRoad} (the orientation
 * pass always sets a non-null {@code frontage}, but a building with no
 * routed road nearby keeps {@code facingRoad == null}). Layer 5 uses
 * {@code frontage} for facade decoration and {@code facingRoad} for
 * road-network adjacency queries.
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
        Parcel parcel) {

    /** Convenience overload that defaults {@code parcel} to null —
     *  preserves callers that don't reserve a complex parcel
     *  (foundation phase, iterative phase fallbacks, non-lead types). */
    public PlacedBuilding(BuildingType type, BlockPos centre,
                          Footprint footprint, Rotation rotation,
                          Priority priority, String variantId,
                          FrontageStrip frontage, RoadSegment facingRoad) {
        this(type, centre, footprint, rotation, priority, variantId,
                frontage, facingRoad, null);
    }

    /** Layout Rework Stage 3c — copy-with the post-routing orientation
     *  ({@code frontage} + {@code facingRoad}); rotation, footprint,
     *  parcel and everything else are preserved. Used by the orientation
     *  pass, which never changes the placement rotation (that would move
     *  the footprint AABB and risk overlaps) — it only attaches the
     *  building to its road. */
    public PlacedBuilding withOrientation(FrontageStrip newFrontage,
                                          RoadSegment newFacingRoad) {
        return new PlacedBuilding(type, centre, footprint, rotation, priority,
                variantId, newFrontage, newFacingRoad, parcel);
    }
}
