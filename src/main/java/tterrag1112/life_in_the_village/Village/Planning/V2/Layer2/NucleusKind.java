package tterrag1112.life_in_the_village.Village.Planning.V2.Layer2;

/**
 * Track E1 prompt-4 — nucleus kind taxonomy.
 *
 * <p>A nucleus is a strong attractor for building placement. Each
 * placed building has a preferred nucleus kind via its strategy's
 * {@link NucleusAffinity}; the placer scores cells by distance to
 * the nearest nucleus of that kind.
 *
 * <ul>
 *   <li>{@link #CIVIC} — the central public-life nucleus
 *       (town square, primary anchor). Civic buildings
 *       (TOWN_HALL / MARKET / INN / BLACKSMITH / BAKERY /
 *       CHAPEL) cluster here.</li>
 *   <li>{@link #RURAL} — a productive-land nucleus, typically a
 *       FARMHOUSE position. HOUSEs cluster around rural nuclei
 *       in AGRICULTURAL strategies (the Bauernhaus pattern).</li>
 *   <li>{@link #RESOURCE} — an industrial nucleus, typically the
 *       primary CLIFF_FACE / FOREST_EDGE anchor with a MINE or
 *       WOODCUTTER on it. Workshop-class buildings (BLACKSMITH /
 *       CARPENTRY) cluster here.</li>
 *   <li>{@link #SACRED} — a religious nucleus, typically the
 *       primary anchor with a CHAPEL / SHRINE. Quiet-zone
 *       penalties surround it.</li>
 *   <li>{@link #GATEWAY} — a network gateway node. In DEFENSIVE
 *       strategies, HOUSEs concentrate at gateways rather than
 *       at the keep (the "walls leak; gates concentrate"
 *       pattern from ACOUP part II).</li>
 * </ul>
 */
public enum NucleusKind {
    CIVIC,
    RURAL,
    RESOURCE,
    SACRED,
    GATEWAY
}
