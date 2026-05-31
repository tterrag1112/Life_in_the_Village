// src/main/java/tterrag1112/life_in_the_village/Village/Planning/Zoning/SlotTag.java
package tterrag1112.life_in_the_village.Village.Planning.Zoning;

/**
 * Descriptive tags a placement slot carries to advertise what it
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
    // Farm plots (Phase 17). Emitted by RecipeHelpers.emitFarmPlotSlots
    // and claimed by VillagePlanner.runFarmPlotPass — NOT by the building
    // matcher. They are not building-targeted at all; they are
    // recipe-emitted-and-claimed-by-the-plot-pass.
    FARM_PLOT_CROP, FARM_PLOT_ANIMAL,
    // Defensive
    WALL_ADJACENT, GATE_ADJACENT, HIGH_GROUND,
    // Specialty (terrain- or feature-bound)
    SHORE, PIER_ADJACENT, TERRACE_EDGE, FOREST_EDGE,
    RIVER_BANK, HILLTOP_PEAK,
    // Generic fallback
    ROAD_ADJACENT, BACKFILL,
    // Plaza-relative (Phase 16 doc 04). Emitted by prompt 17's
    // polygon plaza generator for slots that lie within ~8 blocks
    // of a PlazaRegion's polygon edge. Building profiles for
    // civic / market / temple buildings can prefer this tag to
    // cluster around the plaza without recipes hardcoding ring
    // geometry. No layout emits this tag in prompt 16; the value
    // is appended ahead of time so consumer code can rely on it.
    PLAZA_ADJACENT
}