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
    REQUIRED_FAILED
}
