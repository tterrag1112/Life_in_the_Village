package tterrag1112.life_in_the_village.Village.Roads.Lifecycle;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.Optional;

/**
 * Phase 9 — dead-edge state attached to a {@link
 * tterrag1112.life_in_the_village.Village.Roads.Graph.RoadEdge}.
 *
 * <p>An edge is "dead" when its last maintainer village has died. Dead edges
 * progress through {@link DeathPhase}s based on elapsed game time since the
 * last maintainer death:
 *
 * <table>
 *   <tr><th>Phase</th><th>Days since last maintainer died</th></tr>
 *   <tr><td>RECENT</td><td>0–7</td></tr>
 *   <tr><td>DECAYING</td><td>7–30</td></tr>
 *   <tr><td>OVERGROWN</td><td>30–90</td></tr>
 *   <tr><td>TRACE</td><td>90–180</td></tr>
 *   <tr><td>RECLAIMED</td><td>180+ (eligible for graph removal)</td></tr>
 * </table>
 *
 * <h3>Persistence</h3>
 * Persisted alongside the edge via codec. Old saves with no dead state load
 * with {@link RoadEdge#getDeadState()} returning {@code Optional.empty()} —
 * the optionalFieldOf("deadState") gracefully handles the absence.
 *
 * <h3>Reclaimed-at tick</h3>
 * {@link #reclaimedAt} is set the first time the edge enters RECLAIMED phase.
 * The cleanup pass uses (currentTick − reclaimedAt) against a grace period
 * before removing the edge from the graph entirely.
 */
public record DeadEdgeState(
        long firstMarkedTick,
        long lastMaintainerDiedTick,
        Optional<Long> reclaimedAt
) {

    public enum DeathPhase {
        /** 0–7 days since maintainer died — looks like a normal road. */
        RECENT,
        /** 7–30 days — moss accumulation, edges fraying. */
        DECAYING,
        /** 30–90 days — grass and saplings on the road, fallen logs. */
        OVERGROWN,
        /** 90–180 days — rare road blocks peeking through reclaiming vegetation. */
        TRACE,
        /** 180+ days — eligible for graph removal after grace period. */
        RECLAIMED;

        public static final Codec<DeathPhase> CODEC =
                Codec.STRING.xmap(DeathPhase::valueOf, DeathPhase::name);
    }

    /** Standard ticks-per-day (one full day-night cycle in vanilla MC). */
    public static final long TICKS_PER_DAY = 24000L;

    /** Day thresholds for phase transitions. */
    public static final long DECAYING_DAYS  = 7L;
    public static final long OVERGROWN_DAYS = 30L;
    public static final long TRACE_DAYS     = 90L;
    public static final long RECLAIMED_DAYS = 180L;

    /** Grace period after entering RECLAIMED before the edge is removable. */
    public static final long RECLAIM_GRACE_DAYS = 30L;

    // =========================================================================
    // Phase computation
    // =========================================================================

    /**
     * Computes the current death phase from elapsed-since-last-maintainer-died.
     */
    public DeathPhase phaseAtTick(long currentTick) {
        long elapsedDays = Math.max(0L, currentTick - lastMaintainerDiedTick) / TICKS_PER_DAY;
        if (elapsedDays >= RECLAIMED_DAYS) return DeathPhase.RECLAIMED;
        if (elapsedDays >= TRACE_DAYS)     return DeathPhase.TRACE;
        if (elapsedDays >= OVERGROWN_DAYS) return DeathPhase.OVERGROWN;
        if (elapsedDays >= DECAYING_DAYS)  return DeathPhase.DECAYING;
        return DeathPhase.RECENT;
    }

    /**
     * Returns the next phase boundary in ticks (or {@code -1} if already
     * RECLAIMED). Used by debug tooling to report "remaining until next phase".
     */
    public long ticksUntilNextPhase(long currentTick) {
        long elapsedDays = Math.max(0L, currentTick - lastMaintainerDiedTick) / TICKS_PER_DAY;
        long nextDays;
        if (elapsedDays < DECAYING_DAYS)        nextDays = DECAYING_DAYS;
        else if (elapsedDays < OVERGROWN_DAYS)  nextDays = OVERGROWN_DAYS;
        else if (elapsedDays < TRACE_DAYS)      nextDays = TRACE_DAYS;
        else if (elapsedDays < RECLAIMED_DAYS)  nextDays = RECLAIMED_DAYS;
        else                                    return -1L;
        return (nextDays - elapsedDays) * TICKS_PER_DAY;
    }

    /**
     * Returns a copy of this state with reclaimedAt set to the given tick.
     * The copy is created if reclaimedAt is currently empty; otherwise this
     * state is returned unchanged (idempotent).
     */
    public DeadEdgeState withReclaimedAt(long tick) {
        if (reclaimedAt.isPresent()) return this;
        return new DeadEdgeState(firstMarkedTick, lastMaintainerDiedTick, Optional.of(tick));
    }

    /**
     * Returns a copy backdated so the next phase-check at {@code currentTick}
     * will yield {@code phase}. Used by the {@code force_death_phase} debug
     * command to set a specific phase for testing decoration.
     */
    public DeadEdgeState atPhase(DeathPhase phase, long currentTick) {
        long offsetDays = switch (phase) {
            case RECENT     -> 0L;
            case DECAYING   -> DECAYING_DAYS;
            case OVERGROWN  -> OVERGROWN_DAYS;
            case TRACE      -> TRACE_DAYS;
            case RECLAIMED  -> RECLAIMED_DAYS;
        };
        long backdated = currentTick - (offsetDays * TICKS_PER_DAY) - 1L;
        Optional<Long> reclaim = phase == DeathPhase.RECLAIMED
                ? Optional.of(reclaimedAt.orElse(currentTick))
                : reclaimedAt;
        return new DeadEdgeState(backdated, backdated, reclaim);
    }

    // =========================================================================
    // Codec
    // =========================================================================

    public static final Codec<DeadEdgeState> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.LONG.fieldOf("firstMarkedTick").forGetter(DeadEdgeState::firstMarkedTick),
            Codec.LONG.fieldOf("lastMaintainerDiedTick").forGetter(DeadEdgeState::lastMaintainerDiedTick),
            Codec.LONG.optionalFieldOf("reclaimedAt").forGetter(DeadEdgeState::reclaimedAt)
    ).apply(i, DeadEdgeState::new));

    /**
     * Convenience: construct a fresh dead state at {@code tick}, with
     * {@code firstMarkedTick == lastMaintainerDiedTick == tick}.
     */
    public static DeadEdgeState markedAt(long tick) {
        return new DeadEdgeState(tick, tick, Optional.empty());
    }
}
