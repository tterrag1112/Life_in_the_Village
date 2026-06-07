package tterrag1112.life_in_the_village.Village.Gathering;

/**
 * Normalized lifecycle status shared by every {@link CommunityGathering},
 * regardless of which subsystem owns the concrete record. Religion Rework
 * R2a.
 *
 * <p>The underlying systems keep their own richer status vocabularies
 * ({@code VillageEvent.EventStatus}, {@code RiteOutcome}); this is the
 * common projection a cross-system consumer (e.g. R2b's attend-event
 * behavior) reasons about.</p>
 */
public enum GatheringStatus {
    /** Created, not yet under way. */
    SCHEDULED,
    /** Currently happening. */
    ACTIVE,
    /** Finished normally. */
    COMPLETED,
    /** Dropped / skipped / disrupted before completing. */
    CANCELLED
}
