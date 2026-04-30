package tterrag1112.life_in_the_village.Village.Planning.Sectors;

import tterrag1112.life_in_the_village.Village.Planning.BuildingZone;
import tterrag1112.life_in_the_village.Village.Planning.Features.PolygonXZ;
import tterrag1112.life_in_the_village.Village.Planning.Zoning.PlacementSlot;

import java.util.List;

/**
 * A region of placement intent within a village layout. Owns an ordered
 * slot pool, a capacity, a growth strategy, and an optional parent road
 * edge. Recipes emit sectors during compose; the matcher fills them
 * greedily and invokes growth on overflow.
 *
 * <p>See {@code 01-PLACEMENT-ABSTRACTIONS.md} section "Sector" for
 * author rules and overlap policy.
 *
 * @param id              stable identifier, e.g. "civic_ring", "spur_NE"
 * @param role            sector role for matcher peer-overflow lookups
 * @param zoneHint        ideal occupant zone; matcher hint, not a filter
 * @param slots           ordered candidate positions (mutable via grow)
 * @param capacity        matcher prefers not to exceed; 0 = reservation
 * @param canGrow         if false, overflow is dropped silently
 * @param growth          strategy invoked on overflow when canGrow=true
 * @param parentEdgeId    RoadGraph edge this sector hangs off, -1 if floating
 * @param exclusionShape  optional polygon excluding growth into a region
 */
public record Sector(
        String id,
        SectorRole role,
        BuildingZone zoneHint,
        List<PlacementSlot> slots,
        int capacity,
        boolean canGrow,
        GrowthPolicy growth,
        int parentEdgeId,
        PolygonXZ exclusionShape
) {
    public Sector {
        // Defensive copy of slots so callers can't mutate the record's list
        // after construction. Phase 10's grow returns new lists; the matcher
        // must rebuild the Sector when growing rather than mutating in place.
        slots = List.copyOf(slots);
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Sector id must be non-empty");
        }
        if (role == null) {
            throw new IllegalArgumentException("Sector role must be non-null");
        }
        if (growth == null) {
            throw new IllegalArgumentException(
                "Sector growth must be non-null (use FixedGrowth.INSTANCE)");
        }
    }

    /**
     * Convenience: returns true if {@code canGrow} is true AND the growth
     * strategy is not {@link FixedGrowth}. Use this to short-circuit
     * "should I even ask?" checks in the matcher.
     */
    public boolean canActuallyGrow() {
        return canGrow && !(growth instanceof FixedGrowth);
    }

    /**
     * Returns a new Sector with the given slot list appended. Used by the
     * matcher after a successful grow round.
     */
    public Sector withAdditionalSlots(List<PlacementSlot> additional) {
        java.util.ArrayList<PlacementSlot> combined =
            new java.util.ArrayList<>(slots.size() + additional.size());
        combined.addAll(slots);
        combined.addAll(additional);
        return new Sector(id, role, zoneHint, combined, capacity, canGrow,
                          growth, parentEdgeId, exclusionShape);
    }
}
