package tterrag1112.life_in_the_village.Village.Roads.Economy;

import tterrag1112.life_in_the_village.Village.Decoration.VillageSizeTier;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadEdge;
import tterrag1112.life_in_the_village.Village.Roads.Graph.WorldRoadGraph;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Pure-function cost model for the village road upkeep economy.
 *
 * <h3>Cost formula</h3>
 * {@code edgeCost = costPerCellForTier(tier) * (cellPath.size() / 4)}
 * The division by 4 keeps numbers manageable: a 20-cell connector costs 5 silver
 * per cycle; a 100-cell trunk costs 50 silver per cycle.
 *
 * <h3>Shared edges</h3>
 * When an edge has multiple maintainers each village pays
 * {@code edgeCost / maintainerCount} (integer division; remainder absorbed).
 *
 * <h3>Great roads</h3>
 * GREAT_ROAD always returns 0 from every method — invariant 3.
 */
public final class RoadUpkeepCalculator {

    private RoadUpkeepCalculator() {}

    // =========================================================================
    // Cost per tier
    // =========================================================================

    /**
     * Silver cost per 4-cell block of edge length per maintenance cycle.
     * GREAT_ROAD always returns 0 — invariant 3 (great roads never decay).
     */
    public static int costPerCellForTier(RoadEdge.EdgeTier tier) {
        return switch (tier) {
            case GREAT_ROAD -> 0;
            case TRUNK      -> 2;
            case CONNECTOR  -> 1;
            case LOCAL      -> 1;
        };
    }

    /**
     * Maximum silver a village can spend on road upkeep per cycle,
     * scaled by the village's current size tier.
     */
    public static int maxUpkeepForTier(VillageSizeTier tier) {
        return switch (tier) {
            case HAMLET   -> 10;
            case VILLAGE  -> 50;
            case TOWN     -> 200;
            case CITY     -> 600;
        };
    }

    // =========================================================================
    // Per-edge and per-village cost
    // =========================================================================

    /**
     * Total silver this edge costs per cycle, before splitting among maintainers.
     * Returns 0 for great roads or edges with no cell path / no maintainers.
     */
    public static int computeEdgeUpkeep(RoadEdge edge) {
        if (edge.getTier() == RoadEdge.EdgeTier.GREAT_ROAD) return 0;
        if (edge.getMaintainerVillageIds().isEmpty()) return 0;
        return costPerCellForTier(edge.getTier()) * (edge.getCellPath().size() / 4);
    }

    /**
     * Total silver this village owes per cycle across all its maintained edges.
     * Each edge cost is split equally among maintainers (integer division).
     */
    public static int computeVillageUpkeep(Village village, WorldRoadGraph graph) {
        UUID vid = village.getId();
        int total = 0;
        for (RoadEdge edge : graph.allEdges()) {
            List<UUID> maintainers = edge.getMaintainerVillageIds();
            if (!maintainers.contains(vid)) continue;
            int edgeCost = computeEdgeUpkeep(edge);
            total += edgeCost / Math.max(1, maintainers.size());
        }
        return total;
    }

    /**
     * This village's share of the edge upkeep cost in silver per cycle.
     * Returns 0 if the village is not a maintainer of the edge.
     */
    public static int villageShareOfEdge(UUID villageId, RoadEdge edge) {
        List<UUID> maintainers = edge.getMaintainerVillageIds();
        if (!maintainers.contains(villageId)) return 0;
        int edgeCost = computeEdgeUpkeep(edge);
        return edgeCost / Math.max(1, maintainers.size());
    }

    // =========================================================================
    // Capacity-based edge selection
    // =========================================================================

    /**
     * Returns the subset of {@code edges} that this village will pay for in this
     * cycle given its silver {@code capacity}.
     *
     * <p>Priority order (lower index = higher priority):
     * <ol>
     *   <li>CONNECTOR — most village-local, shortest first</li>
     *   <li>TRUNK — shorter first</li>
     *   <li>LOCAL — shorter first</li>
     * </ol>
     *
     * <p>Edges that exceed the remaining capacity are skipped even if later
     * cheaper edges could fit. This matches how a cash-strapped village would
     * actually prioritize: pay the most important short connectors first.
     */
    public static List<RoadEdge> pickPaidEdges(Village village,
                                                List<RoadEdge> edges,
                                                int capacity) {
        UUID vid = village.getId();
        List<RoadEdge> sorted = new ArrayList<>(edges);
        sorted.sort(Comparator
                .comparingInt((RoadEdge e) -> tierPriority(e.getTier()))
                .thenComparingInt(e -> e.getCellPath().size()));

        List<RoadEdge> paid = new ArrayList<>();
        int remaining = capacity;
        for (RoadEdge e : sorted) {
            int share = villageShareOfEdge(vid, e);
            if (share <= remaining) {
                paid.add(e);
                remaining -= share;
            }
        }
        return paid;
    }

    private static int tierPriority(RoadEdge.EdgeTier tier) {
        return switch (tier) {
            case CONNECTOR  -> 0;
            case TRUNK      -> 1;
            case LOCAL      -> 2;
            case GREAT_ROAD -> 3;
        };
    }
}
