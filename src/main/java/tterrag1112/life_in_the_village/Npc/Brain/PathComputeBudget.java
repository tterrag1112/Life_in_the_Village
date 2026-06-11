package tterrag1112.life_in_the_village.Npc.Brain;

import net.minecraft.server.level.ServerLevel;

/**
 * D1-minimal (city perf) — global per-server-tick budget for NEW path
 * computations.
 *
 * <p>Gate-0 fix 1 registered PATH / CANT_REACH_WALK_TARGET_SINCE and made
 * Brain navigation real for the first time. In a CITY village (~50+ NPCs,
 * 240 force-loaded chunks) every behavior cycle that wants to walk now
 * triggers an A* path compute; with FOLLOW_RANGE=32 the pathfinder explores
 * up to ~512 nodes per compute, and unreachable cross-city targets burn the
 * full cap every time. Uncapped, a morning commute is 50 simultaneous
 * cross-city path computes.</p>
 *
 * <p>This is the minimal global brake: at most {@link #BUDGET_PER_TICK} new
 * path computations may start per server tick, server-wide. Deniers simply
 * defer — {@link BudgetedMoveToTargetSink} returns {@code false} from its
 * start check WITHOUT erasing WALK_TARGET, so the walk request survives and
 * is retried on the next executed brain tick. Worst case with every NPC
 * contending at once is a ~13-tick (0.65 s) start delay for the last NPC,
 * invisible in-game.</p>
 *
 * <p>Server-thread only (Brain ticking and Goal ticking both run on the
 * server thread); no synchronization needed.</p>
 */
public final class PathComputeBudget {

    /** Max new path computations per server tick, server-wide. */
    private static final int BUDGET_PER_TICK = 4;

    private static long currentTick = Long.MIN_VALUE;
    private static int used = 0;

    private PathComputeBudget() {}

    /**
     * Acquire one path-compute slot for this server tick. Returns
     * {@code true} when the caller may compute a path now; {@code false}
     * means "defer and retry next tick" — the caller must leave its walk
     * request (WALK_TARGET memory) intact.
     */
    public static boolean tryAcquire(ServerLevel level) {
        long now = level.getServer().getTickCount();
        if (now != currentTick) {
            currentTick = now;
            used = 0;
        }
        if (used >= BUDGET_PER_TICK) return false;
        used++;
        return true;
    }
}
