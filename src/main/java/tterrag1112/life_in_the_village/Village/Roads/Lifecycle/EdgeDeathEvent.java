package tterrag1112.life_in_the_village.Village.Roads.Lifecycle;

import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadEdge;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Phase 9 — fire-and-forget hook fired whenever an edge transitions through a
 * {@link DeadEdgeState.DeathPhase} boundary, including the initial transition
 * from "alive" to RECENT, and the final transition to "removed" when the
 * cleanup pass deletes a RECLAIMED edge from the graph.
 *
 * <p>Hooks are simple static listener lists. No existing logic subscribes —
 * Phase 10 / future work will. Listener exceptions are caught and printed to
 * stderr so a faulty subscriber can't crash the realisation pipeline.
 */
public final class EdgeDeathEvent {

    private EdgeDeathEvent() {}

    public enum Kind {
        /** Edge has just been marked dead (entered RECENT phase). */
        MARKED_DEAD,
        /** Edge has crossed into a new {@link DeadEdgeState.DeathPhase}. */
        PHASE_CHANGED,
        /** Edge was removed from the graph after the RECLAIMED grace period. */
        REMOVED
    }

    public record Event(
            UUID edgeId,
            RoadEdge.EdgeTier tier,
            Kind kind,
            DeadEdgeState.DeathPhase phase,
            long tick
    ) {}

    public interface Listener { void onEdgeDeath(Event event); }

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    public static void subscribe(Listener listener) { LISTENERS.add(listener); }

    public static void unsubscribe(Listener listener) { LISTENERS.remove(listener); }

    public static void fire(Event event) {
        for (Listener l : LISTENERS) {
            try { l.onEdgeDeath(event); }
            catch (Exception e) {
                System.err.println("[EdgeDeathEvent] listener threw: " + e);
            }
        }
    }
}
