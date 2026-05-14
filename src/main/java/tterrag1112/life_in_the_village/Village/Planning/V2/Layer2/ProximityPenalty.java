package tterrag1112.life_in_the_village.Village.Planning.V2.Layer2;

import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;

/**
 * Track E1 prompt-4 — penalty applied when two building types
 * land closer than {@code minDistance} from each other. The
 * penalty is symmetric (order of {@code a} / {@code b} doesn't
 * matter when matching against placed buildings).
 *
 * <p>Used for "quiet zone" rules — CHAPEL ↔ BLACKSMITH min 12,
 * HOUSE ↔ MINE min 20, etc.
 *
 * @param a               first building type
 * @param b               second building type
 * @param minDistance     blocks below which the penalty applies
 * @param penaltyWeight   magnitude of the penalty; scaled by how
 *                        much {@code minDistance} is undershot
 */
public record ProximityPenalty(
        BuildingType a,
        BuildingType b,
        int minDistance,
        double penaltyWeight) {

    public ProximityPenalty {
        if (a == null || b == null) {
            throw new IllegalArgumentException("ProximityPenalty endpoints required");
        }
        if (minDistance < 0) minDistance = 0;
        if (penaltyWeight < 0) penaltyWeight = 0;
    }

    /** True iff this penalty applies to the unordered pair {@code (x, y)}. */
    public boolean matches(BuildingType x, BuildingType y) {
        return (a == x && b == y) || (a == y && b == x);
    }
}
