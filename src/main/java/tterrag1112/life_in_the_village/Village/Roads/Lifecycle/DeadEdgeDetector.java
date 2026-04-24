package tterrag1112.life_in_the_village.Village.Roads.Lifecycle;

import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Networking.WorldRoadSavedData;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadEdge;
import tterrag1112.life_in_the_village.Village.Roads.Graph.WorldRoadGraph;

import java.util.UUID;

/**
 * Phase 9 — once-per-day scan that flags edges whose maintainers have all
 * died. Runs from {@link tterrag1112.life_in_the_village.Events.KingdomTaxEvent}
 * (or any other host that ticks daily) at most once per
 * {@link #SCAN_INTERVAL_TICKS} game ticks.
 *
 * <h3>What counts as "dead"</h3>
 * An edge becomes dead when:
 * <ul>
 *   <li>Tier is not {@link RoadEdge.EdgeTier#GREAT_ROAD} (invariant 3).</li>
 *   <li>{@code maintainerVillageIds} is non-empty (the edge had at least one
 *       maintainer at some point — this filters out always-unmaintained
 *       edges).</li>
 *   <li>None of those maintainer UUIDs resolve to a village in
 *       {@link VillageSavedData} — i.e. every previous maintainer is gone.</li>
 *   <li>Edge is not already dead.</li>
 * </ul>
 *
 * <p>Phase 9 also fires {@link EdgeDeathEvent} on first marking and on phase
 * transitions detected during the same scan.
 */
public final class DeadEdgeDetector {

    private DeadEdgeDetector() {}

    /** Once-per-day cadence. */
    public static final long SCAN_INTERVAL_TICKS = 24000L;

    private static long lastScanTick = -SCAN_INTERVAL_TICKS;

    /**
     * Runs the scan if at least {@link #SCAN_INTERVAL_TICKS} have passed since
     * the last invocation; otherwise no-ops. Returns the number of edges
     * newly-marked dead (0 if scan was skipped).
     */
    public static int maybeScan(ServerLevel level) {
        long tick = level.getGameTime();
        if (tick - lastScanTick < SCAN_INTERVAL_TICKS) return 0;
        lastScanTick = tick;
        return scanForDeadEdges(level);
    }

    /**
     * Forces an immediate scan, ignoring the schedule. Used by debug commands.
     */
    public static int scanForDeadEdges(ServerLevel level) {
        WorldRoadGraph graph = WorldRoadSavedData.get(level).getGraph();
        VillageSavedData villages = VillageSavedData.get(level);
        long currentTick = level.getGameTime();

        int newlyDead = 0;
        int phaseChanged = 0;

        for (RoadEdge edge : graph.allEdges()) {
            if (edge.getTier() == RoadEdge.EdgeTier.GREAT_ROAD) continue;

            if (edge.isDead()) {
                phaseChanged += maybeFirePhaseTransition(edge, currentTick);
                continue;
            }

            if (edge.getMaintainerVillageIds().isEmpty()) continue;

            boolean hasLiveMaintainer = false;
            for (UUID vid : edge.getMaintainerVillageIds()) {
                if (villages.getVillageById(vid).isPresent()) {
                    hasLiveMaintainer = true;
                    break;
                }
            }
            if (hasLiveMaintainer) continue;

            edge.markDead(currentTick);
            newlyDead++;
            EdgeDeathEvent.fire(new EdgeDeathEvent.Event(
                    edge.getEdgeId(), edge.getTier(),
                    EdgeDeathEvent.Kind.MARKED_DEAD,
                    DeadEdgeState.DeathPhase.RECENT, currentTick));
        }

        if (newlyDead > 0 || phaseChanged > 0) {
            WorldRoadSavedData.get(level).markDirty();
            System.out.println("[DeadEdgeDetector] tick=" + currentTick
                    + " newlyDead=" + newlyDead
                    + " phaseChanged=" + phaseChanged);
        }
        return newlyDead;
    }

    /**
     * Tracks the last observed phase per dead edge so phase-transition events
     * fire exactly once at each boundary. The map is cleared when an edge is
     * removed.
     */
    private static final java.util.Map<UUID, DeadEdgeState.DeathPhase> LAST_PHASE = new java.util.HashMap<>();

    private static int maybeFirePhaseTransition(RoadEdge edge, long currentTick) {
        DeadEdgeState state = edge.getDeadState().orElse(null);
        if (state == null) return 0;
        DeadEdgeState.DeathPhase phase = state.phaseAtTick(currentTick);
        DeadEdgeState.DeathPhase prev = LAST_PHASE.get(edge.getEdgeId());
        if (prev == phase) return 0;
        LAST_PHASE.put(edge.getEdgeId(), phase);

        // Stamp reclaimedAt the first time we observe RECLAIMED.
        if (phase == DeadEdgeState.DeathPhase.RECLAIMED && state.reclaimedAt().isEmpty()) {
            edge.setDeadState(state.withReclaimedAt(currentTick));
        }

        if (prev != null) {
            EdgeDeathEvent.fire(new EdgeDeathEvent.Event(
                    edge.getEdgeId(), edge.getTier(),
                    EdgeDeathEvent.Kind.PHASE_CHANGED,
                    phase, currentTick));
            return 1;
        }
        return 0;
    }

    /** Forgets phase-tracking state for a removed edge. */
    public static void forget(UUID edgeId) { LAST_PHASE.remove(edgeId); }
}
