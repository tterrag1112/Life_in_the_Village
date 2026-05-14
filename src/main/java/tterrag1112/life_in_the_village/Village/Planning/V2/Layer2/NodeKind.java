package tterrag1112.life_in_the_village.Village.Planning.V2.Layer2;

/**
 * Track E1 prompt-3 — node taxonomy for the village road network.
 *
 * <ul>
 *   <li>{@link #ANCHOR} — a feature anchor's centre (the strategy's
 *       primary or one of its secondaries).</li>
 *   <li>{@link #GATEWAY} — an external arm endpoint where the trade
 *       road graph attaches. Today only emitted for {@code PRIMARY}
 *       gateway slots derived from the spine endpoints.</li>
 *   <li>{@link #JUNCTION} — a road-road intersection (e.g. a Spur
 *       branching off a Ring at a non-anchor point).</li>
 *   <li>{@link #SYNTHETIC} — a recipe-generated intermediate point
 *       that isn't an anchor or gateway, e.g. the nearest point on
 *       a Ring where a Spur attaches.</li>
 * </ul>
 */
public enum NodeKind {
    ANCHOR,
    GATEWAY,
    JUNCTION,
    SYNTHETIC
}
