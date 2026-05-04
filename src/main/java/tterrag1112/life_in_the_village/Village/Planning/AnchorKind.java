package tterrag1112.life_in_the_village.Village.Planning;

/**
 * Phase 19: canonical names for spatial anchors on a {@link LayoutPlan}.
 *
 * <p>{@link LayoutPlan#anchors()} maps each present {@code AnchorKind} to
 * a {@code BlockPos}. Anchors are the recipe-set "named points" that
 * downstream consumers (spawner, decorator, expansion, post-rework
 * strategy composition) need to reference without scraping mutable
 * {@link VillageLayout} fields.
 *
 * <p>The enum is the vocabulary; not every village populates every
 * kind, and not every consumer reads every kind. Reserved values
 * (BRIDGE_HEAD, HARBOR, KEEP) have no current producers — they're
 * declared so future phases can add producers without re-versioning
 * the enum surface.
 */
public enum AnchorKind {
    /** Plaza centre — set by every plaza-emitting recipe. */
    TOWN_SQUARE,
    /** Primary outbound road endpoint. */
    MAIN_GATE,
    /** Additional gate for multi-gate recipes (DUMBELL, DUAL_PLAZA). */
    SECONDARY_GATE,
    /** River-bank attach point for RIVERINE recipes. */
    RIVER_LANDING,
    /** Peak / keep position for HILLTOP recipes. */
    HIGH_GROUND,
    /** Bridge attach point — reserved for post-rework Bridge primitive. */
    BRIDGE_HEAD,
    /** Coastal attach point — reserved for post-rework waterway phase. */
    HARBOR,
    /** Castle / capital position — reserved for kingdom rework. */
    KEEP
}
