package tterrag1112.life_in_the_village.Village.Roads.Economy;

import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadEdge;
import tterrag1112.life_in_the_village.Village.Roads.Graph.WorldRoadGraph;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Applies tier promotion and demotion to road edges based on the current
 * size tiers of their maintainer villages.
 *
 * <p>Invariant 3 and 6 enforced throughout: great roads are never touched,
 * and promotion is always driven by village size, not caravan traffic.
 */
public final class EdgeTierManager {

    private EdgeTierManager() {}

    // =========================================================================
    // Bulk reconcile
    // =========================================================================

    /**
     * Scans every edge maintained by {@code village} and promotes or demotes
     * it to the effective tier computed from all its current maintainers.
     *
     * @return the edges that actually changed tier (may be empty)
     */
    public static List<RoadEdge> reconcileEdgesForVillage(
            Village village,
            WorldRoadGraph graph,
            VillageSavedData data) {

        List<RoadEdge> changed = new ArrayList<>();

        for (UUID edgeId : graph.edgesForVillage(village.getId())) {
            RoadEdge edge = graph.getEdge(edgeId);
            if (edge == null || !TierPromotionRules.canBeChanged(edge)) continue;

            List<Village> maintainers = resolveMaintainers(edge, data);
            if (maintainers.isEmpty()) continue;

            RoadEdge.EdgeTier effective = TierPromotionRules.effectiveTier(maintainers);
            if (effective != edge.getTier()) {
                System.out.println("[TierPromotion] Edge " + edgeId.toString().substring(0, 8)
                        + ": " + edge.getTier() + " → " + effective
                        + " (maintainers=" + maintainers.size() + ")");
                applyTierChange(edge, effective, null, graph);
                changed.add(edge);
            }
        }

        return changed;
    }

    // =========================================================================
    // Single edge
    // =========================================================================

    /**
     * Applies a tier change to a single edge:
     * <ul>
     *   <li>Sets the new tier (read by realizer at re-realization time)</li>
     *   <li>Calls {@code unrealize()} — clears blockPath, primitives, sets realized=false</li>
     *   <li>Clears stored decoration positions so the re-realization re-decorates fresh</li>
     * </ul>
     *
     * <p>Does nothing for GREAT_ROAD edges (invariant 3). The {@code level}
     * parameter is accepted for API consistency but not currently required.
     */
    public static void applyTierChange(RoadEdge edge, RoadEdge.EdgeTier newTier,
                                        @SuppressWarnings("unused") ServerLevel level,
                                        WorldRoadGraph graph) {
        if (!TierPromotionRules.canBeChanged(edge)) {
            System.out.println("[TierPromotion] Skipped GREAT_ROAD edge "
                    + edge.getEdgeId().toString().substring(0, 8));
            return;
        }
        RoadEdge.EdgeTier old = edge.getTier();
        edge.setTier(newTier);
        edge.clearDecorationPositions();
        edge.unrealize();
        System.out.println("[TierPromotion] applyTierChange: edge "
                + edge.getEdgeId().toString().substring(0, 8)
                + " " + old + " → " + newTier);
    }

    // =========================================================================
    // Helper
    // =========================================================================

    private static List<Village> resolveMaintainers(RoadEdge edge, VillageSavedData data) {
        List<Village> result = new ArrayList<>();
        for (UUID vid : edge.getMaintainerVillageIds()) {
            data.getVillageById(vid).ifPresent(result::add);
        }
        return result;
    }
}
