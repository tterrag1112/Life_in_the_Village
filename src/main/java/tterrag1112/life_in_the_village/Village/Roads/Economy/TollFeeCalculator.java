package tterrag1112.life_in_the_village.Village.Roads.Economy;

import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadEdge;

/**
 * Pure fee computation for toll gate crossings.
 *
 * <h3>Base fees</h3>
 * <ul>
 *   <li>GREAT_ROAD gate: 20 bronze</li>
 *   <li>TRUNK gate: 10 bronze</li>
 * </ul>
 *
 * <h3>Caravan goods surcharge</h3>
 * {@code fee += goodsValueBronze / 50} — a laden caravan pays proportionally more.
 *
 * <h3>Reputation modifier</h3>
 * <pre>
 * 80–100 (honored):   fee = 0  (waved through)
 * 60–79  (respected): fee × 0.5
 * 20–59  (neutral):   fee × 1.0
 * 0–19   (suspicious): fee × 1.5
 * </pre>
 * Default reputation for unknown travelers is 50 (neutral).
 */
public final class TollFeeCalculator {

    /** Default reputation used when no record exists for the traveler. */
    public static final int DEFAULT_REPUTATION = 50;

    /** Reputation threshold for free passage. */
    public static final int HONORED_THRESHOLD  = 80;
    /** Reputation threshold for discounted passage. */
    public static final int RESPECTED_THRESHOLD = 60;
    /** Reputation threshold below which passage is more expensive. */
    public static final int SUSPICIOUS_THRESHOLD = 20;

    private TollFeeCalculator() {}

    // =========================================================================
    // Fee computation
    // =========================================================================

    /**
     * Computes the toll fee for a crossing scenario.
     *
     * @param tierAtGate            the edge tier at the gate position
     * @param goodsValueBronze      for caravans: the estimated value of their goods;
     *                              for players: pass 0
     * @param reputation            0–100 reputation score with the collecting kingdom;
     *                              use {@link #DEFAULT_REPUTATION} if unknown
     * @return a {@link TollFee} record with the computed amount and reason string
     */
    public static TollFee computeFee(RoadEdge.EdgeTier tierAtGate,
                                     long goodsValueBronze,
                                     int reputation) {
        int baseFee = baseFeeForTier(tierAtGate);
        long goodsSurcharge = goodsValueBronze / 50;
        long rawFee = baseFee + goodsSurcharge;

        if (reputation >= HONORED_THRESHOLD) {
            return new TollFee(0, "reputation_honored");
        }
        if (reputation >= RESPECTED_THRESHOLD) {
            return new TollFee((int) (rawFee / 2), "reputation_respected_50pct");
        }
        if (reputation < SUSPICIOUS_THRESHOLD) {
            return new TollFee((int) (rawFee * 3 / 2), "suspicious_150pct");
        }
        return new TollFee((int) rawFee, "standard");
    }

    /**
     * Convenience overload for an anonymous traveler (default reputation, no goods).
     */
    public static TollFee computeFeeDefault(RoadEdge.EdgeTier tierAtGate) {
        return computeFee(tierAtGate, 0, DEFAULT_REPUTATION);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    public static int baseFeeForTier(RoadEdge.EdgeTier tier) {
        return switch (tier) {
            case GREAT_ROAD -> 20;
            case TRUNK      -> 10;
            default         ->  5; // LOCAL/CONNECTOR — shouldn't gate, but safe fallback
        };
    }

    // =========================================================================
    // Result record
    // =========================================================================

    /**
     * @param amountBronze  bronze owed at this gate; 0 means free passage
     * @param reason        short tag for logging ("standard", "reputation_honored", etc.)
     */
    public record TollFee(int amountBronze, String reason) {
        public boolean isFree() { return amountBronze <= 0; }
    }
}
