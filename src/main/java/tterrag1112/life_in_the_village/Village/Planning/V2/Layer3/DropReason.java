package tterrag1112.life_in_the_village.Village.Planning.V2.Layer3;

/**
 * Why a building was not placed. Recorded on each
 * {@link DroppedBuilding} so the debug command can explain
 * cascade effects.
 */
public enum DropReason {
    /** Selector skipped the building — no PlacementProfile authored, or
     *  required terrain aggregate (RIVER, FOREST, STONE, etc.) missing. */
    NOT_SELECTED,
    /** Topological dep failed: a {@code requires_present} entry wasn't
     *  successfully placed earlier. Cascades. */
    DEPENDENCY_MISSING,
    /** All candidate cells scored ≤ 0, or terrain pre-filter rejected
     *  every cell. */
    NO_VIABLE_CANDIDATE,
    /** The building was {@code required: true} (only TOWN_HALL in V1)
     *  and dropped — village fails entirely. */
    REQUIRED_FAILED,
    /** Phase 5 connectivity audit: the building's frontage doesn't
     *  overlap any road segment connected to the anchor, and the
     *  short-connector rescue couldn't reach without crossing
     *  another footprint. */
    ISOLATED_AFTER_REASSESS,
    /** Layer 5 TerrainAdapter: terrain under the building's footprint
     *  is too steep to level or platform within the V1 thresholds. */
    TERRAIN_UNADAPTABLE,
    /** Stage 4b — a FARMHOUSE could not reserve a viable farm-field
     *  parcel at planning time (no clear, low-slope box fits past the
     *  districts/other reservations). Non-fatal: FARMHOUSE isn't
     *  {@code required}, so the village stays viable with fewer farms —
     *  the gate exists so a fieldless farmhouse never ships (no strays). */
    NO_VIABLE_COMPLEX_PARCEL
}
