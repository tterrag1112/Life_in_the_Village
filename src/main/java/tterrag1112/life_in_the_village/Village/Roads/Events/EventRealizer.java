package tterrag1112.life_in_the_village.Village.Roads.Events;

import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Networking.WorldRoadSavedData;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadEdge;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadNode;
import tterrag1112.life_in_the_village.Village.Roads.Graph.WorldRoadGraph;
import tterrag1112.life_in_the_village.Village.Roads.Realization.RoadPlacementContext;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 10 — turns {@link EventSitePlanner.PlannedEvent}s into actual placed
 * {@link RoadEvent}s. Calls each type's factory, registers the result in
 * {@link WorldRoadSavedData}, and threads the event ID into the owning edge or
 * node.
 *
 * <h3>Idempotency</h3>
 * Re-planning is deterministic, so on a re-realisation pass the planner emits
 * the same site positions. The realiser skips any planned site that already
 * has an event of the same type within {@code minSpacingBlocks} on the same
 * edge / node, so duplicate placement is silently elided.
 *
 * <h3>Failure handling</h3>
 * Factory exceptions are caught and logged. The faulty event is skipped; the
 * rest of the plan continues. World-unique types that lose a race silently
 * skip via the registry's {@code isWorldUniquePlaced} guard checked twice
 * (once by the planner, once at realisation just before the factory runs).
 */
public final class EventRealizer {

    private EventRealizer() {}

    public static int realizeEvents(ServerLevel level,
                                     RoadEdge edge,
                                     List<EventSitePlanner.PlannedEvent> plans,
                                     WorldRoadGraph graph) {
        if (plans.isEmpty()) return 0;
        WorldRoadSavedData saved = WorldRoadSavedData.get(level);
        long currentTick = level.getGameTime();
        int placed = 0;

        for (EventSitePlanner.PlannedEvent plan : plans) {
            if (alreadyPlacedNearby(saved, edge.getEventIds(), plan)) continue;
            if (plan.type().worldUnique() && saved.isWorldUniquePlaced(plan.type().typeId())) continue;

            RoadEvent event = invokeFactory(level, plan, edge, Optional.empty(), currentTick);
            if (event == null) continue;

            saved.registerEvent(event);
            edge.addEventId(event.eventId());
            if (plan.type().worldUnique()) saved.markWorldUniquePlaced(plan.type().typeId());
            placed++;
        }
        if (placed > 0) saved.markDirty();
        return placed;
    }

    public static int realizeEvents(ServerLevel level,
                                     RoadNode node,
                                     List<EventSitePlanner.PlannedEvent> plans,
                                     WorldRoadGraph graph) {
        if (plans.isEmpty()) return 0;
        WorldRoadSavedData saved = WorldRoadSavedData.get(level);
        long currentTick = level.getGameTime();
        int placed = 0;

        for (EventSitePlanner.PlannedEvent plan : plans) {
            if (alreadyPlacedNearby(saved, node.getEventIds(), plan)) continue;
            if (plan.type().worldUnique() && saved.isWorldUniquePlaced(plan.type().typeId())) continue;

            RoadEvent event = invokeFactory(level, null, plan, node, currentTick);
            if (event == null) continue;

            saved.registerEvent(event);
            node.addEventId(event.eventId());
            if (plan.type().worldUnique()) saved.markWorldUniquePlaced(plan.type().typeId());
            placed++;
        }
        if (placed > 0) saved.markDirty();
        return placed;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static RoadEvent invokeFactory(ServerLevel level,
                                            EventSitePlanner.PlannedEvent plan,
                                            RoadEdge edge,
                                            Optional<RoadNode> node,
                                            long currentTick) {
        return invokeFactory(level, edge, plan, node.orElse(null), currentTick);
    }

    private static RoadEvent invokeFactory(ServerLevel level,
                                            RoadEdge edge,
                                            EventSitePlanner.PlannedEvent plan,
                                            RoadNode node,
                                            long currentTick) {
        EventPlacementContext ctx = new EventPlacementContext(
                level, edge, Optional.ofNullable(node),
                plan.sitePosition(), currentTick, plan.rng());
        try {
            return RoadPlacementContext.withSuppressionReturning(
                    () -> plan.type().factory().create(ctx));
        } catch (Exception e) {
            System.err.println("[EventRealizer] factory '" + plan.type().typeId()
                    + "' threw: " + e + " — skipping site at " + plan.sitePosition());
            return null;
        }
    }

    /**
     * Returns true if {@code ownerEvents} already contains an event of the same
     * type within {@code plan.type().minSpacingBlocks()} of the planned site.
     * Used to make re-realisation idempotent.
     */
    private static boolean alreadyPlacedNearby(WorldRoadSavedData saved,
                                                List<UUID> ownerEvents,
                                                EventSitePlanner.PlannedEvent plan) {
        if (ownerEvents.isEmpty()) return false;
        int spacingSq = plan.type().minSpacingBlocks() * plan.type().minSpacingBlocks();
        for (UUID id : ownerEvents) {
            RoadEvent ev = saved.getEvent(id).orElse(null);
            if (ev == null) continue;
            if (!ev.typeId().equals(plan.type().typeId())) continue;
            int dx = ev.position().getX() - plan.sitePosition().getX();
            int dz = ev.position().getZ() - plan.sitePosition().getZ();
            if ((long) dx * dx + (long) dz * dz < spacingSq) return true;
        }
        return false;
    }
}
