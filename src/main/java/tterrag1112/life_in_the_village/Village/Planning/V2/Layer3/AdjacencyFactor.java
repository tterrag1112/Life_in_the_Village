package tterrag1112.life_in_the_village.Village.Planning.V2.Layer3;

/**
 * Per-position adjacency scoring inputs. Each factor maps to a 0..1
 * value computed from the candidate position relative to the site
 * anchor, primary spine, or already-placed buildings.
 *
 * <p>{@code NEAR_*} factors use {@code 1 / (1 + dist/villageRadius)}
 * (1.0 at the source, decays with distance). {@code FAR_FROM_*}
 * factors use {@code dist / (dist + villageRadius)} (0 at the source,
 * approaches 1 far away).
 *
 * <p>NEAR / FAR are mutually exclusive in a profile — pick one.
 */
public enum AdjacencyFactor {
    /** Closer to the {@code SiteContext.anchor} is better. */
    NEAR_ANCHOR,
    /** Same shape as {@link #NEAR_ANCHOR} until V2 grows a separate
     *  civic centre concept; today the anchor IS the civic centre. */
    NEAR_CIVIC_CENTRE,
    /** Inverted perpendicular distance to the nearest spine
     *  segment in the routed network skeleton. */
    NEAR_MAIN_ROAD,
    /** Farther from the anchor is better. */
    FAR_FROM_ANCHOR,
    /** Symmetric counterpart to {@link #NEAR_CIVIC_CENTRE}. */
    FAR_FROM_CIVIC_CENTRE,
    /** Distance to the nearest already-placed building of the same
     *  type. Returns 1.0 when none of the type are placed yet, so the
     *  first instance always passes; later instances are pushed apart. */
    FAR_FROM_SAME_TYPE
}
