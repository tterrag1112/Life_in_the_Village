package tterrag1112.life_in_the_village.Village.Roads.Travel;

import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadEdge;

/**
 * Computes the movement-speed bonus a player receives when walking on a
 * maintained road.
 *
 * <h3>Bonus table</h3>
 * <pre>
 * Tier        maint ≥ 80   maint 40-79   maint 20-39   maint &lt; 20
 * GREAT_ROAD    +0.30        +0.20          linear→0      0.0
 * TRUNK         +0.20        +0.10          linear→0      0.0
 * CONNECTOR     +0.15        +0.05          linear→0      0.0
 * LOCAL         +0.08        +0.02          linear→0      0.0
 * </pre>
 *
 * <p>The bonus is applied as an {@link net.minecraft.world.entity.ai.attributes.AttributeModifier}
 * with {@code ADD_MULTIPLIED_BASE} operation on {@link net.minecraft.world.entity.ai.attributes.Attributes#MOVEMENT_SPEED}.
 * A bonus of {@code 0.30} therefore yields a 30% speed increase over the base value.
 *
 * <p>Between the maintenance bands the bonus is linearly interpolated so there is
 * no hard step at the band boundaries from a player's perspective.
 */
public final class RoadSpeedModifier {

    private RoadSpeedModifier() {}

    // ── Per-tier ceiling and floor bonuses ───────────────────────────────────

    private static double highBonus(RoadEdge.EdgeTier tier) {
        return switch (tier) {
            case GREAT_ROAD -> 0.30;
            case TRUNK      -> 0.20;
            case CONNECTOR  -> 0.15;
            case LOCAL      -> 0.08;
            // Track C3.3 — SEA edges have no walked surface; players
            // don't get speed bonuses from them.
            case SEA        -> 0.0;
        };
    }

    private static double midBonus(RoadEdge.EdgeTier tier) {
        return switch (tier) {
            case GREAT_ROAD -> 0.20;
            case TRUNK      -> 0.10;
            case CONNECTOR  -> 0.05;
            case LOCAL      -> 0.02;
            case SEA        -> 0.0;
        };
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Returns the speed bonus (0.0–0.30) for a given tier and maintenance level.
     * The value is suitable for use as an {@code ADD_MULTIPLIED_BASE} modifier amount.
     *
     * @param tier        the edge tier
     * @param maintenance the edge maintenance level, clamped to [0, 100]
     * @return speed bonus; 0.0 if maintenance is below 20
     */
    public static double speedBonus(RoadEdge.EdgeTier tier, int maintenance) {
        if (maintenance < 20) return 0.0;

        double high = highBonus(tier);
        double mid  = midBonus(tier);

        if (maintenance >= 80) return high;

        if (maintenance >= 40) {
            // linear from mid (at 40) to high (at 80)
            double t = (maintenance - 40) / 40.0;
            return mid + t * (high - mid);
        }

        // linear from 0 (at 20) to mid (at 40)
        double t = (maintenance - 20) / 20.0;
        return t * mid;
    }

    /**
     * Returns the speed multiplier (1.0 = no change) for the given tier and
     * maintenance level. Convenience wrapper: {@code 1.0 + speedBonus(tier, maint)}.
     */
    public static double speedMultiplier(RoadEdge.EdgeTier tier, int maintenance) {
        return 1.0 + speedBonus(tier, maintenance);
    }
}
