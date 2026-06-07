package tterrag1112.life_in_the_village.Village.Gathering;

import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Religion Rework R2a — the shared contract for a scheduled, located,
 * attended village gathering. Both a secular {@code VillageEvent} and a
 * religious {@code RiteExecution} implement it: they fundamentally do the
 * same thing (a scheduled gathering people attend), they are merely used
 * differently — secular events are calendar/prosperity-driven, while a
 * rite is the <em>blessing-extension</em> attached to a gathering, adding
 * an officiant and an outcome on top.
 *
 * <h3>Why an interface (not a base class)</h3>
 * {@code VillageEvent} is an existing class and {@code RiteExecution} is a
 * record; an interface lets both conform with delegating/derived methods
 * without disrupting either, and without renaming any existing accessor.
 *
 * <h3>Registries stay separate</h3>
 * This unifies the <em>contract</em>, not the storage —
 * {@code RiteSavedData} and the {@code VillageSavedData} event store remain
 * distinct. A cross-system consumer discovers both kinds through
 * {@code CommunityGatherings}, behind this interface.
 *
 * <p>Deliberately neutral: this package depends on neither the event nor
 * the religion subsystem, so both can implement it without a package
 * cycle, and future kingdom/military gatherings can adopt it too.</p>
 */
public interface CommunityGathering {

    /** Stable per-gathering id (the event id / the rite id). */
    UUID gatheringId();

    /** Owning village. */
    UUID villageId();

    /** Venue, when one is pinned (festivals may be village-wide → empty). */
    Optional<BlockPos> gatheringLocation();

    /** First tick the gathering is under way. */
    long startTick();

    /** Tick the gathering is over (start + duration). */
    long endTick();

    /** Normalized lifecycle status. */
    GatheringStatus gatheringStatus();

    /** Attendees expected to show (required core participants). */
    List<UUID> requiredAttendees();

    /** Attendees invited but optional. */
    List<UUID> invitedAttendees();

    /** Attendees recorded as actually present. */
    List<UUID> actualAttendees();

    /** The gathering's focal NPC, if any (groom, deceased, ordinand…). */
    Optional<UUID> primarySubjectId();

    /**
     * Whether the gathering is under way at {@code tick}. Covers both an
     * explicitly-ACTIVE gathering and a SCHEDULED one whose start/end window
     * contains {@code tick} (rites have no separate ACTIVE state — they are
     * PENDING until resolved, so their active window is derived here).
     */
    default boolean isActiveAt(long tick) {
        return gatheringStatus() == GatheringStatus.ACTIVE
                || (gatheringStatus() == GatheringStatus.SCHEDULED
                    && startTick() <= tick && tick < endTick());
    }
}
