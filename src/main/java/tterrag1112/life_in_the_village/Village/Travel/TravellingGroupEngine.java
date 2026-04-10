// src/main/java/tterrag1112/life_in_the_village/Village/Travel/TravellingGroupEngine.java
package tterrag1112.life_in_the_village.Village.Travel;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;

import java.util.List;

/**
 * Drives any {@link TravellingGroup} through its lifecycle once per
 * tick. Caravans, road events, future armies — all of them call
 * {@link #tick} from their owner's tick loop and the engine handles
 * spawn/despawn/progress/completion.
 *
 * <h3>Why this isn't a SavedData or tick subsystem itself</h3>
 * The engine is stateless. Each traveller type still owns its own
 * storage and dispatches itself from its own tick subsystem — the
 * engine just processes one group per call. This avoids forcing
 * unrelated systems (caravans, events, armies) to share storage,
 * lifecycle, or load order.
 *
 * <h3>Spawn / despawn radii</h3>
 * Spawn radius (smaller) is the distance at which a simulated group
 * promotes to spawned. Despawn radius (larger) is the distance at
 * which a spawned group demotes back to simulated. Two different
 * thresholds prevent flicker when a player walks back and forth
 * across a single boundary.
 *
 * <p>The values are intentionally larger than the previous
 * caravan-specific constants because they're now shared with smaller
 * groups (single pilgrims, lone bandits) that benefit from a wider
 * activation envelope.
 */
public final class TravellingGroupEngine {

    /** Distance at which a simulated group becomes spawned. */
    public static final int SPAWN_RADIUS   = 80;

    /** Distance at which a spawned group becomes simulated. */
    public static final int DESPAWN_RADIUS = 128;

    /**
     * Base progress added per engine tick before the group's speed
     * multiplier is applied. Matches the previous caravan constant
     * so caravan travel times are unchanged after migration.
     */
    public static final double BASE_PROGRESS_PER_TICK = 0.002;

    private TravellingGroupEngine() {}

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Processes one engine tick for one group. Call from each
     * traveller-owning saved data's own tick method, once per group.
     *
     * <h3>Tick order</h3>
     * <ol>
     *   <li>Resolve path. Skip if missing.</li>
     *   <li>Compute world position from progress.</li>
     *   <li>Check player proximity.</li>
     *   <li>Promote (simulated → spawned) or demote (spawned → simulated)
     *       if proximity has crossed the threshold.</li>
     *   <li>Run the appropriate per-tick hook.</li>
     *   <li>For simulated groups, advance progress.</li>
     *   <li>If progress crossed 1.0, fire {@link TravellingGroup#onPathComplete}.</li>
     * </ol>
     *
     * <h3>Returns</h3>
     * True if the group's state changed in a way that should mark its
     * owning saved data dirty. Callers should OR these together across
     * all groups in a tick and call {@code setDirty()} once at the end.
     */
    public static boolean tick(TravellingGroup group,
                               ServerLevel level,
                               VillageSavedData data,
                               long currentTick) {
        List<BlockPos> path = group.getPath(level, data);
        if (path == null || path.isEmpty()) return false;

        BlockPos currentPos = computePosition(group, path);
        if (currentPos == null) return false;

        boolean dirty = false;
        boolean wasCompletedThisTick = false;

        // ── Promotion / demotion ────────────────────────────────────────────
        boolean nearPlayer = isPlayerWithin(
                level, currentPos,
                group.isSpawned() ? DESPAWN_RADIUS : SPAWN_RADIUS);

        if (nearPlayer && !group.isSpawned()) {
            group.onSpawn(level, currentPos, data);
            dirty = true;
        } else if (!nearPlayer && group.isSpawned()) {
            group.onDespawn(level);
            dirty = true;
        }

        // ── Per-tick hook ───────────────────────────────────────────────────
        if (group.isSpawned()) {
            group.onTickSpawned(level, data);
        } else {
            // Simulated tick: advance progress, then run the hook
            double speed = group.getSpeedMultiplier(level, data);
            double inc = BASE_PROGRESS_PER_TICK * Math.max(1.0, speed);
            double oldProgress = group.getProgress();
            double newProgress = Math.min(1.0, oldProgress + inc);

            if (newProgress != oldProgress) {
                group.setProgress(newProgress);
                dirty = true;
            }
            group.onTickSimulated(level, data);

            if (oldProgress < 1.0 && newProgress >= 1.0) {
                wasCompletedThisTick = true;
            }
        }

        // ── Completion ──────────────────────────────────────────────────────
        if (wasCompletedThisTick) {
            group.onPathComplete(level, data);
            dirty = true;
        }

        return dirty;
    }

    // =========================================================================
    // Position helpers
    // =========================================================================

    /**
     * Returns the world block position for a group based on its current
     * progress along its path. Honours {@link TravellingGroup#isReversed}
     * so returning groups walk the path backward.
     *
     * <p>Public so callers that need the position outside of a tick
     * (UI rendering, debug overlays, distance queries) can compute it
     * with the same logic the engine uses.
     */
    public static BlockPos computePosition(TravellingGroup group, List<BlockPos> path) {
        if (path.isEmpty()) return null;
        double p = group.isReversed() ? 1.0 - group.getProgress() : group.getProgress();
        int idx = (int)(p * (path.size() - 1));
        idx = Math.max(0, Math.min(path.size() - 1, idx));
        return path.get(idx);
    }

    private static boolean isPlayerWithin(ServerLevel level,
                                          BlockPos pos, int radius) {
        for (ServerPlayer p : level.players()) {
            if (p.blockPosition().closerThan(pos, radius)) return true;
        }
        return false;
    }
}