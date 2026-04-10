// src/main/java/tterrag1112/life_in_the_village/Village/Travel/TravellingGroup.java
package tterrag1112.life_in_the_village.Village.Travel;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;

import java.util.List;
import java.util.UUID;

/**
 * Contract for any persistent group of entities that travels along a path.
 *
 * <h3>What "travel" means here</h3>
 * A traveller is anything that has a path, a position along it, and
 * entities that should be visible only when a player is nearby. Caravans,
 * road events (wandering traders, pilgrim groups, bandit ambushes —
 * even though ambushes don't actually move), future armies, future
 * adventurer parties — they all share this shape.
 *
 * <h3>What this interface does NOT touch</h3>
 * <ul>
 *   <li><b>Storage.</b> Each traveller type owns its own collection in
 *       its own SavedData. There's no unified TravellerSavedData.</li>
 *   <li><b>Dispatch.</b> What creates new travellers (route daily roll,
 *       temple pilgrim spawn, kingdom muster order) is each type's
 *       responsibility.</li>
 *   <li><b>Entity AI.</b> The actual {@code moveTo} calls during
 *       spawned-entity locomotion live in profession goals
 *       ({@code CaravanMerchantGoal} etc). The engine drives the
 *       group state machine; the goal drives the per-tick walking.</li>
 * </ul>
 *
 * <h3>What it DOES unify</h3>
 * <ul>
 *   <li>Realising entities when a player approaches</li>
 *   <li>Despawning entities when the player leaves</li>
 *   <li>Advancing simulated progress when no player is around</li>
 *   <li>Computing the world position from the path + progress</li>
 *   <li>Detecting path completion</li>
 * </ul>
 *
 * <p>The {@link TravellingGroupEngine} consumes this interface and
 * provides the shared behaviour. Implementations only need to provide
 * the data and the spawn/despawn/completion hooks.
 */
public interface TravellingGroup {

    // =========================================================================
    // Identity
    // =========================================================================

    /** Stable unique identifier for this traveller. */
    UUID groupId();

    // =========================================================================
    // Path
    // =========================================================================

    /**
     * Returns the sequence of block positions this group travels along.
     * Resolved fresh on each tick — implementations can change the
     * source path if needed (caravans switching to a different road,
     * armies recomputing after a region becomes unsafe).
     *
     * <p>Returns an empty list if the path is no longer available, in
     * which case the engine skips this group for the tick.
     */
    List<BlockPos> getPath(ServerLevel level, VillageSavedData data);

    /**
     * True if the group is travelling backward along the path.
     * Caravans set this when returning home. Static events return false
     * unconditionally.
     */
    boolean isReversed();

    // =========================================================================
    // Progress
    // =========================================================================

    /** Current position along the path as a fraction in [0, 1]. */
    double getProgress();

    /** Sets the progress fraction. Caller is responsible for clamping. */
    void setProgress(double progress);

    /**
     * Speed multiplier applied to the base per-tick progress increment.
     * Returning 1.0 = base speed, 2.0 = double speed, 0.5 = half.
     * Caravans use road quality; pilgrims might use a per-type constant.
     * Static events should return 0.0.
     */
    double getSpeedMultiplier(ServerLevel level, VillageSavedData data);

    // =========================================================================
    // Spawn state
    // =========================================================================

    /** True if the group's entities are currently in the world. */
    boolean isSpawned();

    /**
     * Returns all entity UUIDs the group has spawned. Used for
     * tracking, distance checks, and bulk operations like dismissal
     * during world unload.
     */
    List<UUID> getEntityIds();

    // =========================================================================
    // Lifecycle hooks — implemented per traveller type
    // =========================================================================

    /**
     * Called by the engine when a player has approached the group's
     * current position and entities should be created. The implementation
     * is responsible for spawning entities, setting their position,
     * and recording their UUIDs in its own state.
     *
     * <p>After this method returns, {@link #isSpawned} should return true.
     */
    void onSpawn(ServerLevel level, BlockPos spawnPos, VillageSavedData data);

    /**
     * Called by the engine when the player has moved far enough away
     * that entities should be removed. The implementation is responsible
     * for discarding entities and clearing its tracked UUIDs.
     *
     * <p>After this method returns, {@link #isSpawned} should return false.
     * Progress is automatically preserved by the engine — implementations
     * do not need to do anything to "save" their position.
     */
    void onDespawn(ServerLevel level);

    /**
     * Called once per tick while the group is spawned. Use for
     * spawned-state work that doesn't fit in entity goals — usually
     * empty for groups whose entities have proper AI goals.
     *
     * <p>Caravans use this method as a no-op because
     * {@code CaravanMerchantGoal} handles all spawned-entity behaviour.
     * Road events also leave it empty for the same reason.
     */
    void onTickSpawned(ServerLevel level, VillageSavedData data);

    /**
     * Called once per tick while the group is NOT spawned. Use for
     * simulated-state work beyond plain progress advancement (which
     * the engine handles automatically).
     *
     * <p>Most implementations leave this empty. Travellers that should
     * react to events while simulated — say, an army recalculating its
     * route after a kingdom border changes — can override.
     */
    void onTickSimulated(ServerLevel level, VillageSavedData data);

    /**
     * Called when {@link #getProgress} reaches 1.0. The implementation
     * decides what happens — caravans switch to DELIVERING state,
     * pilgrims arrive at their shrine, armies reach their target.
     *
     * <p>The engine calls this exactly once per crossing of the 1.0
     * threshold; it does not loop. Implementations that want a return
     * trip should reset progress to 0 and reverse direction.
     */
    void onPathComplete(ServerLevel level, VillageSavedData data);
}