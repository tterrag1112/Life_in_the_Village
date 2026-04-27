package tterrag1112.life_in_the_village.Village.Roads.Events;

import net.minecraft.server.level.ServerLevel;

/**
 * Phase 10 — the per-type factory that materialises a {@link RoadEvent}'s
 * blocks (or NPCs, future) and tears them down on expiration.
 *
 * <p>Factories are looked up via {@link RoadEventRegistry#get(String)}; they
 * are not serialised. Re-registration after a code change picks up new
 * implementations on the next world load.
 *
 * <h3>Idempotency</h3>
 * {@code create} may be called more than once for the same site if a previous
 * realisation pass crashed or was interrupted. Factories should be idempotent
 * where reasonable — placing the same blocks twice is harmless.
 */
public interface EventFactory {

    /**
     * Build the event in the world and return its persisted record. The
     * returned {@link RoadEvent} must carry the position, owning edge / node,
     * placedTick, and any {@code expiresAtTick} the event uses; the planner /
     * realiser do not synthesise these.
     *
     * @return the new RoadEvent, or {@code null} if placement was rejected
     *         (e.g. terrain unsuitable). Null results are silently dropped by
     *         {@link EventRealizer}.
     */
    RoadEvent create(EventPlacementContext ctx);

    /**
     * Remove this event's structures from the world. Called when the event's
     * {@code expiresAtTick} passes or when a debug command despawns it.
     * Best-effort — chunks may be unloaded; factories should skip silently
     * rather than throw.
     */
    void despawn(ServerLevel level, RoadEvent event);
}
