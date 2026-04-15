// src/main/java/tterrag1112/life_in_the_village/Village/Planning/Zoning/SlotTag.java
package tterrag1112.life_in_the_village.Village.Planning.Zoning;

/**
 * Descriptive tags a {@link PlacementSlot} carries to advertise what it
 * offers. A single slot typically carries several tags (e.g. a plaza-
 * tangent slot is both {@code PRIME_CIVIC} and {@code ROAD_ADJACENT}).
 *
 * <p>Building profiles declare which tags they require and prefer; the
 * {@code PlacementMatcher} scores slots against profiles during placement.
 */
public enum SlotTag {
    // Centrality
    PRIME_CIVIC, SECONDARY_CIVIC, CIVIC_ADJACENT,
    // Production
    PRODUCTION_CLUSTER, PRODUCTION_SPUR_END, PRODUCTION_INFILL,
    // Residential
    RESIDENTIAL_CORE, RESIDENTIAL_INFILL, RESIDENTIAL_OUTER,
    // Agricultural
    FIELD_EDGE, PASTURE,
    // Defensive
    WALL_ADJACENT, GATE_ADJACENT, HIGH_GROUND,
    // Specialty (terrain- or feature-bound)
    SHORE, PIER_ADJACENT, TERRACE_EDGE, FOREST_EDGE,
    RIVER_BANK, HILLTOP_PEAK,
    // Generic fallback
    ROAD_ADJACENT, BACKFILL
}