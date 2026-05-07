package tterrag1112.life_in_the_village.Village.Planning.V2.Layer3;

/**
 * V2 building priority — drives tie-breaking in
 * {@link DependencyResolver}'s topological sort.
 *
 * <p>Order matches design-doc §"Building Priority Order":
 * civic before infrastructure before production before residential.
 * Within a priority, the topo sort tie-breaks by
 * {@code BuildingType} enum declaration order.
 */
public enum Priority {
    CIVIC,
    INFRASTRUCTURE,
    PRODUCTION,
    RESIDENTIAL
}
