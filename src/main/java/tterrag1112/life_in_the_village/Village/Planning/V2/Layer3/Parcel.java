package tterrag1112.life_in_the_village.Village.Planning.V2.Layer3;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import tterrag1112.life_in_the_village.Utilities.Geometry.Polygon;

/**
 * Layout Rework Stage 2a — a building-owned reserved region for a
 * domain-specific "complex" to fill (a farm complex around a FARMHOUSE,
 * a market pad around a MARKET).
 *
 * <p>Replaces the dead {@code ComplexRegion}/{@code PlanningPhase}
 * socket. Unlike that socket, a {@code Parcel} is reserved live during
 * planning: {@code PhasedPlanner} sizes a budget AABB from the complex
 * spec, validates it (overlap / corridor / terrain) like an adjunct,
 * folds it into the building's {@code Reservation} so later buildings
 * avoid it, and attaches it to the {@link PlacedBuilding}.
 *
 * <p><b>By decision the reservation is a bounding-box AABB</b> (the
 * reservation/overlap system is AABB-only). The {@link #budget} is that
 * box as a 4-corner rectangular {@link Polygon}; the organic complex
 * shape is filled <i>inside</i> it at realization (Stage 2b), recorded
 * in {@link #realizedRegion}.
 *
 * <p>No building UUID is carried — the parcel is attached directly to
 * its {@link PlacedBuilding}, and the spawn
 * adapter pairs the {@code PlacedBuilding} with the persisted
 * {@code Building} (UUID) in its post-spawn loop.
 *
 * @param kind            FARM or MARKET (more domains later).
 * @param budget          reserved budget box, a rectangular XZ polygon.
 * @param anchor          lead building centre — the seed point; carries
 *                        the pad Y the realizer builds at.
 * @param growthDirection cardinal direction the complex grows (away from
 *                        the lead building's road frontage).
 * @param realizedRegion  nullable until Stage 2b fills the organic shape.
 * @param buildingBounds  nullable; the lead building's footprint box, for
 *                        the realizer to knock out a hole.
 */
public record Parcel(
        Kind kind,
        Polygon budget,
        BlockPos anchor,
        Direction growthDirection,
        Polygon realizedRegion,
        Polygon buildingBounds) {

    public enum Kind { FARM, MARKET }

    public Parcel {
        if (kind == null) throw new IllegalArgumentException("Parcel.kind required");
        if (budget == null) throw new IllegalArgumentException("Parcel.budget required");
        if (anchor == null) throw new IllegalArgumentException("Parcel.anchor required");
        if (growthDirection == null) growthDirection = Direction.SOUTH;
    }

    /** Reservation-only parcel (no realized region yet — Stage 2a). */
    public Parcel(Kind kind, Polygon budget, BlockPos anchor,
                  Direction growthDirection, Polygon buildingBounds) {
        this(kind, budget, anchor, growthDirection, null, buildingBounds);
    }

    /** Returns a copy with the realized region filled in (Stage 2b). */
    public Parcel withRealized(Polygon region) {
        return new Parcel(kind, budget, anchor, growthDirection, region, buildingBounds);
    }

    /** Budget-box bounds as an XZ AABB. */
    public Polygon.AABB budgetBounds() {
        return Polygon.boundingBox(budget);
    }
}
