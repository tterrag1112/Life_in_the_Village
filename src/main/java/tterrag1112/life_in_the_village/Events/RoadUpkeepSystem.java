package tterrag1112.life_in_the_village.Events;

import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Networking.WorldRoadSavedData;
import tterrag1112.life_in_the_village.Village.Decoration.VillageSizeTier;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;
import tterrag1112.life_in_the_village.Village.Roads.Economy.RoadUpkeepCalculator;
import tterrag1112.life_in_the_village.Village.Roads.Economy.VillageUpkeepLedger;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadEdge;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadNode;
import tterrag1112.life_in_the_village.Village.Roads.Graph.WorldRoadGraph;
import tterrag1112.life_in_the_village.Village.Village;
import tterrag1112.life_in_the_village.Village.Roads.Travel.RoadProximityCache;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Runs one road maintenance cycle per in-game day.
 *
 * <h3>Cycle flow</h3>
 * <ol>
 *   <li>Rebuild the village-to-edges reverse index so post-mutation maintainer
 *       lists are reflected accurately.</li>
 *   <li>For each village: determine capacity, pick paid edges, deduct treasury,
 *       record streaks in the {@link VillageUpkeepLedger}.</li>
 *   <li>For each edge: compute how many of its maintainers paid this cycle.
 *       All paid → +2 maintenance; none paid → −5; partial → no change.</li>
 *   <li>Detect maintenance-band crossings (Phase 6b bands: 0-19, 20-39, 40-59,
 *       60-79, 80-100) and flag edges for overgrowth re-decoration.</li>
 *   <li>Emit {@code [RoadUpkeep]} log warnings for chronic failure streaks ≥ 10.</li>
 *   <li>Prune stale maintainer entries for villages that no longer exist.</li>
 * </ol>
 *
 * <h3>Great roads</h3>
 * Invariant 3 is enforced throughout: GREAT_ROAD edges are skipped entirely —
 * no cost calculation, no maintenance change, no decay.
 */
public final class RoadUpkeepSystem {

    /** Consecutive failure streak that triggers a [RoadUpkeep] neglect log. */
    private static final int CHRONIC_NEGLECT_THRESHOLD = 10;

    private RoadUpkeepSystem() {}

    // =========================================================================
    // Entry point
    // =========================================================================

    /**
     * Runs one upkeep cycle. May be called from the scheduled tick or from
     * debug commands (trigger_upkeep, force_decay_cycle).
     *
     * @param bankrupt if {@code true}, all villages are treated as having an
     *                 empty treasury — used by force_decay_cycle to simulate
     *                 N consecutive bankrupt cycles quickly.
     */
    public static void runCycle(ServerLevel level,
                                 VillageSavedData villageData,
                                 boolean bankrupt) {
        WorldRoadSavedData roadData = WorldRoadSavedData.get(level);
        WorldRoadGraph graph = roadData.getGraph();

        // Ensure the maintainer index is fresh (catches post-add mutations)
        graph.rebuildVillageMaintainerIndex();

        // paidCount[edgeId] = number of maintainers who successfully paid this cycle
        Map<UUID, Integer> paidCount = new HashMap<>();
        // totalCount[edgeId] = total number of (non-great-road) maintainers for this edge
        Map<UUID, Integer> totalCount = new HashMap<>();

        // Initialise totalCount from the graph (to count zero-paying villages too)
        for (RoadEdge edge : graph.allEdges()) {
            if (edge.getTier() == RoadEdge.EdgeTier.GREAT_ROAD) continue;
            if (!edge.getMaintainerVillageIds().isEmpty()) {
                totalCount.put(edge.getEdgeId(), edge.getMaintainerVillageIds().size());
            }
        }

        // ── Per-village upkeep ────────────────────────────────────────────────
        for (Village village : villageData.getAllVillages()) {
            UUID vid = village.getId();

            Set<UUID> edgeIds = new HashSet<>(graph.edgesForVillage(vid));
            if (edgeIds.isEmpty()) continue;

            // Collect non-great-road edges this village maintains
            List<RoadEdge> maintainedEdges = new ArrayList<>();
            for (UUID eid : edgeIds) {
                RoadEdge e = graph.getEdge(eid);
                if (e != null && e.getTier() != RoadEdge.EdgeTier.GREAT_ROAD) {
                    maintainedEdges.add(e);
                }
            }
            if (maintainedEdges.isEmpty()) continue;

            VillageSizeTier sizeTier = VillageSizeTier.fromBuildingCount(
                    village.getBuildingIds().size());
            int capacity = RoadUpkeepCalculator.maxUpkeepForTier(sizeTier);

            // Warn if treasury is negative (shouldn't happen but be safe)
            if (village.getTreasuryBronze() < 0) {
                System.out.println("[RoadUpkeep] WARNING: village " + village.getName()
                        + " has negative treasury (" + village.getTreasuryBronze() + " bronze); treating as 0");
            }

            List<RoadEdge> prioritisedEdges = bankrupt
                    ? List.of()
                    : RoadUpkeepCalculator.pickPaidEdges(village, maintainedEdges, capacity);
            Set<UUID> prioritisedIds = new HashSet<>();
            for (RoadEdge e : prioritisedEdges) prioritisedIds.add(e.getEdgeId());

            VillageUpkeepLedger ledger = roadData.getOrCreateLedger(vid);

            for (RoadEdge edge : maintainedEdges) {
                boolean inPrioritised = prioritisedIds.contains(edge.getEdgeId());

                if (inPrioritised && !bankrupt) {
                    int share = RoadUpkeepCalculator.villageShareOfEdge(vid, edge);
                    long shareBronze = (long) share * CurrencyValue.SILVER_VALUE;

                    boolean canAfford = shareBronze == 0 || village.getTreasuryBronze() >= shareBronze;
                    if (canAfford) {
                        if (shareBronze > 0) village.withdrawFromTreasury(shareBronze);
                        paidCount.merge(edge.getEdgeId(), 1, Integer::sum);
                        ledger.resetFailureStreak(edge.getEdgeId());
                        ledger.recordPaid();
                    } else {
                        // Capacity said we could afford it but treasury is insufficient
                        // (race condition: another edge drained treasury first)
                        ledger.incrementFailureStreak(edge.getEdgeId());
                        ledger.recordFailed();
                    }
                } else {
                    // Capacity exhausted or bankrupt mode
                    ledger.incrementFailureStreak(edge.getEdgeId());
                    ledger.recordFailed();
                }
            }

            ledger.setLastCycleTick(level.getGameTime());
        }

        // ── Per-edge maintenance update ────────────────────────────────────────
        for (RoadEdge edge : graph.allEdges()) {
            if (edge.getTier() == RoadEdge.EdgeTier.GREAT_ROAD) continue; // invariant 3

            List<UUID> maintainers = edge.getMaintainerVillageIds();

            // Prune stale maintainer entries (village deleted)
            boolean removedStale = maintainers.removeIf(
                    vid -> villageData.getVillageById(vid).isEmpty());
            if (removedStale) {
                System.out.println("[RoadUpkeep] Pruned stale maintainer(s) from edge "
                        + edge.getEdgeId().toString().substring(0, 8));
            }

            if (maintainers.isEmpty()) {
                // Unmaintained edge decays slowly. Track C3.2: POI
                // subroads (LOCAL edge with a POI_STUB endpoint) decay
                // faster — no village pays, no village walks, the
                // forest reclaims the path.
                applyDecay(edge, decayDeltaForUnmaintained(edge, graph));
                continue;
            }

            int total = totalCount.getOrDefault(edge.getEdgeId(), maintainers.size());
            int paid  = paidCount.getOrDefault(edge.getEdgeId(), 0);

            int prevBand = maintenanceBand(edge.getMaintenance());

            if (paid >= total) {
                // All maintainers paid — gradual repair
                edge.setMaintenance(edge.getMaintenance() + 2);
            } else if (paid == 0) {
                // Nobody paid — decay (tier-aware via Track C3.2 helper)
                applyDecay(edge, decayDeltaForUnmaintained(edge, graph));
            }
            // Partial payment — maintenance stays flat (no change)

            if (maintenanceBand(edge.getMaintenance()) != prevBand) {
                edge.setNeedsDecorationRefresh(true);
            }
        }

        // ── Chronic neglect warnings ───────────────────────────────────────────
        for (Village village : villageData.getAllVillages()) {
            VillageUpkeepLedger ledger = roadData.getLedger(village.getId());
            if (ledger == null) continue;
            for (Map.Entry<UUID, Integer> entry : ledger.getEdgeFailureStreaks().entrySet()) {
                if (entry.getValue() >= CHRONIC_NEGLECT_THRESHOLD) {
                    RoadEdge edge = graph.getEdge(entry.getKey());
                    String edgeLabel = edge != null
                            ? entry.getKey().toString().substring(0, 8)
                            : entry.getKey().toString().substring(0, 8) + "(missing)";
                    System.out.println("[RoadUpkeep] CHRONIC NEGLECT: " + village.getName()
                            + " has failed to maintain edge " + edgeLabel
                            + " for " + entry.getValue() + " consecutive cycles.");
                }
            }
        }

        // Invalidate the road proximity cache so Phase 8b spawn suppression
        // reflects the new maintenance scores immediately in the next in-game day.
        RoadProximityCache.invalidate();

        roadData.markDirty();
        villageData.setDirty();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Applies a maintenance delta to an edge, clamped 0–100, and flags for
     * re-decoration if the maintenance band changed.
     */
    static void applyDecay(RoadEdge edge, int delta) {
        int prev = edge.getMaintenance();
        int prevBand = maintenanceBand(prev);
        edge.setMaintenance(prev + delta);
        if (maintenanceBand(edge.getMaintenance()) != prevBand) {
            edge.setNeedsDecorationRefresh(true);
        }
    }

    /**
     * Maps a maintenance score to one of 5 Phase-6b overgrowth bands:
     * 0=0-19, 1=20-39, 2=40-59, 3=60-79, 4=80-100.
     */
    static int maintenanceBand(int maintenance) {
        if (maintenance < 20) return 0;
        if (maintenance < 40) return 1;
        if (maintenance < 60) return 2;
        if (maintenance < 80) return 3;
        return 4;
    }

    /**
     * Track C3.2 — returns the per-tick decay delta for an unmaintained
     * edge. POI subroads (LOCAL tier with at least one POI_STUB
     * endpoint) decay at –8 per cycle instead of the default –5;
     * forests reclaim them faster than other LOCAL edges. All other
     * tiers fall back to the original –5.
     *
     * <p>Note this only fires on the no-maintainer / no-payer paths.
     * Edges with maintainers paying upkeep aren't subject to decay
     * regardless of tier.
     */
    static int decayDeltaForUnmaintained(RoadEdge edge, WorldRoadGraph graph) {
        if (edge.getTier() == RoadEdge.EdgeTier.LOCAL) {
            RoadNode a = graph.getNode(edge.getNodeAId());
            RoadNode b = graph.getNode(edge.getNodeBId());
            boolean isPoiSubroad =
                    (a != null && a.type() == RoadNode.NodeType.POI_STUB)
                            || (b != null && b.type() == RoadNode.NodeType.POI_STUB);
            if (isPoiSubroad) return -8;
        }
        return -5;
    }
}
