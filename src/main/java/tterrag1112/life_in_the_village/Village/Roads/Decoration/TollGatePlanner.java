package tterrag1112.life_in_the_village.Village.Roads.Decoration;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Kingdom.Kingdom;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadEdge;
import tterrag1112.life_in_the_village.Village.Roads.Graph.WorldRoadGraph;
import tterrag1112.life_in_the_village.World.Atlas.AtlasCell;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Detects kingdom border crossings on TRUNK and GREAT_ROAD edges and
 * produces placement plans for toll gate structures.
 *
 * <h3>Border detection</h3>
 * For each qualifying edge the planner walks the cell path, looking up the
 * owning kingdom for each cell via the territorial claim index built from
 * {@link VillageSavedData}. A crossing occurs when the owning kingdom
 * changes between two consecutive cells. The gate is placed 2 cells inside
 * the territory of the incoming kingdom (the one that collects the toll).
 *
 * <h3>Gate probability</h3>
 * <ul>
 *   <li>TRUNK edges: 100% — all border crossings get a gate.</li>
 *   <li>GREAT_ROAD edges: 80% — some crossings are unguarded (neutral
 *       territory, historical right-of-passage).</li>
 * </ul>
 *
 * <h3>Invariants</h3>
 * <ul>
 *   <li>Only one gate per distinct cell position (deduplication).</li>
 *   <li>Only TRUNK and GREAT_ROAD edges.</li>
 *   <li>Cells owned by no kingdom produce no crossing.</li>
 *   <li>Same kingdom on both sides: no crossing.</li>
 * </ul>
 */
public final class TollGatePlanner {

    /** How many cells inside the kingdom's territory the gate is positioned. */
    private static final int INTERIOR_BUFFER_CELLS = 2;

    private TollGatePlanner() {}

    // =========================================================================
    // Plan record
    // =========================================================================

    /**
     * A planned toll gate position on a specific edge.
     *
     * @param edgeId              the edge being crossed
     * @param splitCellKey        the atlas cell key at the gate position
     * @param gatePosition        approximate world position (cell centre)
     * @param collectingKingdomId the kingdom that receives the toll
     * @param entrySideNodeId     the node "outside" (traveller comes from this side)
     * @param exitSideNodeId      the node "inside" (traveller exits toward this side)
     * @param tierAtGate          edge tier (for fee calculation)
     * @param structureSeed       deterministic seed for visual variant selection
     */
    public record TollGatePlan(
            UUID edgeId,
            long splitCellKey,
            BlockPos gatePosition,
            UUID collectingKingdomId,
            UUID entrySideNodeId,
            UUID exitSideNodeId,
            RoadEdge.EdgeTier tierAtGate,
            long structureSeed
    ) {}

    // =========================================================================
    // Entry point
    // =========================================================================

    /**
     * Scans all TRUNK and GREAT_ROAD edges in the graph for kingdom border
     * crossings and returns gate placement plans.
     *
     * @param graph      world road graph
     * @param villageData for querying kingdom territorial claims
     * @param worldSeed  used to seed per-gate structure variants
     */
    public static List<TollGatePlan> planForGraph(WorldRoadGraph graph,
                                                   VillageSavedData villageData,
                                                   long worldSeed) {
        // Build cell→kingdomId lookup from all kingdom territorial claims
        Map<Long, UUID> cellToKingdom = buildCellKingdomIndex(villageData);
        if (cellToKingdom.isEmpty()) return List.of();

        List<TollGatePlan> plans = new ArrayList<>();
        Set<Long> usedCells = new HashSet<>(); // deduplication: one gate per cell

        for (RoadEdge edge : graph.allEdges()) {
            if (edge.getTier() != RoadEdge.EdgeTier.TRUNK
                    && edge.getTier() != RoadEdge.EdgeTier.GREAT_ROAD) continue;

            List<Long> cellPath = edge.getCellPath();
            if (cellPath.size() < 4) continue;

            List<TollGatePlan> edgePlans =
                    detectCrossings(edge, cellPath, cellToKingdom, usedCells, worldSeed);
            plans.addAll(edgePlans);
        }

        return plans;
    }

    // =========================================================================
    // Border crossing detection
    // =========================================================================

    private static List<TollGatePlan> detectCrossings(RoadEdge edge,
                                                        List<Long> cellPath,
                                                        Map<Long, UUID> cellToKingdom,
                                                        Set<Long> usedCells,
                                                        long worldSeed) {
        List<TollGatePlan> result = new ArrayList<>();

        UUID prevKingdom = cellToKingdom.get(cellPath.get(0)); // may be null (wilderness)

        for (int i = 1; i < cellPath.size(); i++) {
            long key = cellPath.get(i);
            UUID kingdom = cellToKingdom.get(key);

            // Only care about transitions FROM one kingdom INTO another kingdom
            if (kingdom != null && !kingdom.equals(prevKingdom)) {
                // Place gate INTERIOR_BUFFER_CELLS inside the new (collecting) kingdom
                int gateIdx = Math.min(i + INTERIOR_BUFFER_CELLS, cellPath.size() - 1);
                long gateCellKey = cellPath.get(gateIdx);

                if (!usedCells.contains(gateCellKey)) {
                    // Probability check
                    Random rng = new Random(gateCellKey ^ worldSeed);
                    boolean place = edge.getTier() == RoadEdge.EdgeTier.TRUNK
                            || rng.nextFloat() < 0.80f;

                    if (place) {
                        BlockPos gatePos = cellCentre(gateCellKey);
                        TollGatePlan plan = new TollGatePlan(
                                edge.getEdgeId(),
                                gateCellKey,
                                gatePos,
                                kingdom,
                                edge.getNodeAId(),  // entry side (from nodeA)
                                edge.getNodeBId(),  // exit side (toward nodeB)
                                edge.getTier(),
                                gateCellKey ^ edge.getEdgeId().getLeastSignificantBits()
                        );
                        result.add(plan);
                        usedCells.add(gateCellKey);
                    }
                }
            }

            prevKingdom = kingdom;
        }

        return result;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Builds a map from atlas cell key to the UUID of the kingdom claiming
     * that cell. Only the first kingdom found for a cell is recorded
     * (overlapping claims are rare and treated as belonging to whichever
     * kingdom appears first in the list).
     */
    public static Map<Long, UUID> buildCellKingdomIndex(VillageSavedData villageData) {
        Map<Long, UUID> index = new HashMap<>();
        for (Kingdom kingdom : villageData.getAllKingdoms()) {
            kingdom.getTerritorialClaim().ifPresent(claim -> {
                for (long cellKey : claim.claimedCellKeys()) {
                    index.putIfAbsent(cellKey, kingdom.getId());
                }
            });
        }
        return index;
    }

    private static BlockPos cellCentre(long cellKey) {
        int cx = AtlasCell.unpackX(cellKey);
        int cz = AtlasCell.unpackZ(cellKey);
        int bx = (cx << AtlasCell.CELL_SHIFT) + AtlasCell.CELL_HALF;
        int bz = (cz << AtlasCell.CELL_SHIFT) + AtlasCell.CELL_HALF;
        return new BlockPos(bx, 64, bz); // Y is ground-snapped by TollGateBuilder
    }
}
