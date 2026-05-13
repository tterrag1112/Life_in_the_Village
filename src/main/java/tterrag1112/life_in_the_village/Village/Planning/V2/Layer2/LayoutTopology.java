package tterrag1112.life_in_the_village.Village.Planning.V2.Layer2;

/**
 * Track E1B — declarative village forms (glumbosch taxonomy).
 *
 * <p>Each {@link LayoutStrategy} declares one topology. Prompt 3
 * (network grower) reads the topology and applies the matching
 * primitive-emission algorithm:
 * <ul>
 *   <li>{@link #HAUFENDORF} — short Ring or StraightRoad nucleus
 *       + radial Spurs.</li>
 *   <li>{@link #REIHENDORF} — one StraightRoad / CurvedRoad along
 *       a linear feature + Spurs at intervals.</li>
 *   <li>{@link #ANGERDORF} — Ring or Arc around a focal central
 *       feature + spurs outward through gateways.</li>
 *   <li>{@link #RUNDLING} — concentric Ring + Arc with ArmApproach
 *       gateways for defensive use.</li>
 *   <li>{@link #EINZELHOF} — single compound: short StraightRoad
 *       from gateway to manor + Spurs to support buildings.</li>
 *   <li>{@link #CLUSTER} — generic fallback; loose StraightRoad +
 *       Spur. Used when no other topology fits.</li>
 * </ul>
 *
 * <p>Adding a new village form is a new enum value + a strategy
 * registration that declares it — no dispatch wiring required
 * until prompt 3 reads it.
 */
public enum LayoutTopology {
    HAUFENDORF,
    REIHENDORF,
    ANGERDORF,
    RUNDLING,
    EINZELHOF,
    CLUSTER
}
