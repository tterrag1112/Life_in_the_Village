package tterrag1112.life_in_the_village.Entities;

import javax.annotation.Nullable;

/**
 * Structured representation of what an NPC is currently doing and, optionally,
 * what is preventing them from working.
 *
 * <h3>Display contract</h3>
 * <ul>
 *   <li>{@link #toDisplayString()} — shown in the NPC nameplate. Includes
 *       {@code activity} and {@code detail} but never the blocking reason, so
 *       onlookers see "Crafting: iron sword" not internal error messages.</li>
 *   <li>The blocking reason is shown only in debug output (stick right-click)
 *       so developers can diagnose why an NPC is stuck without cluttering the
 *       in-game experience.</li>
 * </ul>
 *
 * <h3>Backward compat</h3>
 * {@code TownspersonMob.setCurrentActivity(String)} delegates to
 * {@link #of(String)}, so all existing goal call sites continue to compile
 * unchanged. Goals that want to report a blocking condition call
 * {@link #ofBlocked(String, String)} or chain {@link #withBlocking(String)}.
 */
public record ActivityState(
        String activity,
        @Nullable String detail,
        @Nullable String blockingReason
) {
    // ── Sentinel values ───────────────────────────────────────────────────────

    /** The NPC is not doing anything notable right now. */
    public static final ActivityState IDLE = new ActivityState("", null, null);

    // ── Factories ─────────────────────────────────────────────────────────────

    /** Simple activity with no extra detail and no blocking condition. */
    public static ActivityState of(String activity) {
        if (activity == null || activity.isBlank()) return IDLE;
        return new ActivityState(activity, null, null);
    }

    /** Activity with a detail suffix shown in the nameplate. */
    public static ActivityState of(String activity, String detail) {
        return new ActivityState(activity, detail, null);
    }

    /**
     * NPC is in a blocked/waiting state. The {@code blockingReason} is shown
     * only in debug output — the nameplate shows {@code activity} to indicate
     * what they're trying to do ("Waiting at market", "Looking for supplies").
     */
    public static ActivityState ofBlocked(String activity, String blockingReason) {
        return new ActivityState(activity, null, blockingReason);
    }

    // ── Mutation helpers (returns new instance; records are immutable) ────────

    /** Attach a blocking reason to an existing activity state. */
    public ActivityState withBlocking(String reason) {
        return new ActivityState(activity, detail, reason);
    }

    /** Remove a previously set blocking reason. */
    public ActivityState clearBlocking() {
        return new ActivityState(activity, detail, null);
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    /** True if there is no meaningful activity set. */
    public boolean isEmpty() {
        return activity == null || activity.isBlank();
    }

    /** True if the NPC is in a blocked/waiting state. */
    public boolean isBlocked() {
        return blockingReason != null && !blockingReason.isBlank();
    }

    /**
     * String shown in the NPC nameplate — includes activity and detail but
     * never the internal blocking reason.
     */
    public String toDisplayString() {
        if (isEmpty()) return "";
        if (detail != null && !detail.isBlank()) {
            return activity + ": " + detail;
        }
        return activity;
    }
}
