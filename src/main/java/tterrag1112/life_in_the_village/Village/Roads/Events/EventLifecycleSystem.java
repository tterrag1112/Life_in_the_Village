package tterrag1112.life_in_the_village.Village.Roads.Events;

import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Networking.WorldRoadSavedData;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadEdge;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadNode;
import tterrag1112.life_in_the_village.Village.Roads.Graph.WorldRoadGraph;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Phase 10 — once-per-day pass that despawns expired road events. Wired into
 * {@link tterrag1112.life_in_the_village.Events.KingdomTaxEvent} alongside the
 * Phase 9 dead-edge detector so all daily lifecycle work runs in one place.
 */
public final class EventLifecycleSystem {

    private EventLifecycleSystem() {}

    public static final long TICK_INTERVAL_TICKS = 24000L;

    private static long lastTickAt = -TICK_INTERVAL_TICKS;

    public static int maybeTickExpirations(ServerLevel level) {
        long tick = level.getGameTime();
        if (tick - lastTickAt < TICK_INTERVAL_TICKS) return 0;
        lastTickAt = tick;
        return tickExpirations(level);
    }

    public static int tickExpirations(ServerLevel level) {
        WorldRoadSavedData saved = WorldRoadSavedData.get(level);
        WorldRoadGraph graph = saved.getGraph();
        long currentTick = level.getGameTime();

        List<RoadEvent> expired = new ArrayList<>();
        for (RoadEvent e : saved.allEvents()) {
            if (e.hasExpired(currentTick)) expired.add(e);
        }
        if (expired.isEmpty()) return 0;

        for (RoadEvent event : expired) {
            despawnEvent(level, saved, graph, event);
        }
        saved.markDirty();
        System.out.println("[EventLifecycleSystem] tick=" + currentTick
                + " despawned " + expired.size() + " expired event(s)");
        return expired.size();
    }

    /**
     * Removes a single event from the world (best-effort despawn via factory)
     * and clears its references in the graph + saved-data event map.
     */
    public static void despawnEvent(ServerLevel level,
                                     WorldRoadSavedData saved,
                                     WorldRoadGraph graph,
                                     RoadEvent event) {
        UUID eventId = event.eventId();

        // Best-effort factory teardown.
        RoadEventRegistry.get(event.typeId()).ifPresent(type -> {
            try {
                type.factory().despawn(level, event);
            } catch (Exception e) {
                System.err.println("[EventLifecycleSystem] despawn for type '"
                        + event.typeId() + "' threw: " + e);
            }
            // World-unique types unlock when their last instance despawns.
            if (type.worldUnique()) saved.unmarkWorldUniquePlaced(type.typeId());
        });

        // Detach from graph attachments.
        event.containingEdgeId().ifPresent(edgeId -> {
            RoadEdge edge = graph.getEdge(edgeId);
            if (edge != null) edge.removeEventId(eventId);
        });
        event.containingNodeId().ifPresent(nodeId -> {
            RoadNode node = graph.getNode(nodeId);
            if (node != null) node.removeEventId(eventId);
        });

        saved.removeEvent(eventId);
    }
}
