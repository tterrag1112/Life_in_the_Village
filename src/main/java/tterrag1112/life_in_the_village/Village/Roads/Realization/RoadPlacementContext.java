package tterrag1112.life_in_the_village.Village.Roads.Realization;

/**
 * Thread-local flag that suppresses terrain-change events fired by road
 * realization itself, preventing the self-triggering re-realization loop.
 *
 * <p>Usage: wrap all road block-placement calls with
 * {@link #withSuppression(Runnable)} so that {@link
 * tterrag1112.life_in_the_village.Events.RoadTerrainChangeListener} ignores
 * the events produced by those placements.
 */
public final class RoadPlacementContext {

    private static final ThreadLocal<Boolean> SUPPRESS =
            ThreadLocal.withInitial(() -> false);

    private RoadPlacementContext() {}

    /**
     * Runs {@code r} with the suppression flag set, then restores the previous
     * state. Nested calls are safe: inner invocations inherit the outer flag
     * and the flag is only cleared when the outermost call returns.
     */
    public static void withSuppression(Runnable r) {
        boolean prev = SUPPRESS.get();
        SUPPRESS.set(true);
        try {
            r.run();
        } finally {
            SUPPRESS.set(prev);
        }
    }

    /** Returns {@code true} if road placement is currently in progress on this thread. */
    public static boolean isSuppressed() {
        return SUPPRESS.get();
    }
}
