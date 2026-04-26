package tterrag1112.life_in_the_village.Npc.BusinessFront;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Per-building greeter state. Spec lines 70-83 — strictly transient
 * (server-life-cycle), so no persistence is needed.
 *
 * <p>Mutators are server-thread-only; the tick + greeter-assignment
 * code paths run on the main thread, and listeners on
 * {@link BuildingPresenceTracker} dispatch synchronously.</p>
 */
public final class BusinessFrontTracker {

    private static final Map<UUID, BusinessFrontState> STATES = new HashMap<>();

    /**
     * Recent CLOSED-refusal counts per (player, building) for the
     * mood-penalty-on-repeat behaviour described in spec line 216.
     * Cleared when the player leaves the building.
     */
    private static final Map<RefusalKey, Integer> CLOSED_REFUSALS = new HashMap<>();

    private BusinessFrontTracker() {}

    // ── State ─────────────────────────────────────────────────────────────

    public static BusinessFrontState getOrCreate(UUID buildingId) {
        return STATES.computeIfAbsent(buildingId, BusinessFrontState::none);
    }

    public static Optional<BusinessFrontState> get(UUID buildingId) {
        return Optional.ofNullable(STATES.get(buildingId));
    }

    public static void put(BusinessFrontState state) {
        if (state != null) STATES.put(state.buildingId(), state);
    }

    public static void clear(UUID buildingId) {
        STATES.remove(buildingId);
    }

    public static Map<UUID, BusinessFrontState> snapshot() {
        return Collections.unmodifiableMap(new HashMap<>(STATES));
    }

    // ── Refusal counter ───────────────────────────────────────────────────

    /** Increment + return the new count for this player/building pair. */
    public static int recordRefusal(UUID playerId, UUID buildingId) {
        RefusalKey key = new RefusalKey(playerId, buildingId);
        int next = CLOSED_REFUSALS.getOrDefault(key, 0) + 1;
        CLOSED_REFUSALS.put(key, next);
        return next;
    }

    /** Reset on building-leave so the count is per-visit. */
    public static void clearRefusalsFor(UUID playerId, UUID buildingId) {
        CLOSED_REFUSALS.remove(new RefusalKey(playerId, buildingId));
    }

    private record RefusalKey(UUID playerId, UUID buildingId) {}
}
