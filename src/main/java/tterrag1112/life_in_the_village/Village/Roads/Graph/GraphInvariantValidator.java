package tterrag1112.life_in_the_village.Village.Roads.Graph;

import tterrag1112.life_in_the_village.World.Atlas.AtlasCell;

import java.util.*;

/**
 * Load-time sanity checker for the world road graph. Logs warnings but never
 * throws or crashes — a corrupt graph is better than a world that won't load.
 *
 * <h3>When this runs</h3>
 * Called from {@link tterrag1112.life_in_the_village.Networking.WorldRoadSavedData}
 * immediately after deserialization, before the graph is handed to any
 * other system. Also called after migration so the migrated state is verified
 * on the first load.
 *
 * <h3>Orphan node exception</h3>
 * {@link RoadNode.NodeType#TERMINUS} and {@link RoadNode.NodeType#POI_STUB}
 * nodes are allowed to exist without incident edges. TERMINUS nodes arise
 * when a village is lost (its VILLAGE_DOCK becomes a TERMINUS while its
 * connector edge weathers out). POI_STUB nodes are pre-seeded before their
 * sub-road is built. All other node types must be referenced by at least
 * one edge.
 *
 * <h3>Cell-path length rule</h3>
 * An edge with zero cells (empty cellPath) is unconditionally invalid —
 * there is no geometric meaning for an edge with no cells. An edge with
 * exactly one cell is technically degenerate (represents a very short
 * connection within a single 64-block cell) but is accepted with a warning
 * rather than being treated as an error, since it can occur during migration
 * of very close villages and will resolve when connectors are re-planned.
 */
public final class GraphInvariantValidator {

    private GraphInvariantValidator() {}

    /**
     * Validates the given graph and returns a list of human-readable warning
     * strings. Returns an empty list if the graph is fully valid.
     */
    public static List<String> validate(WorldRoadGraph graph) {
        List<String> warnings = new ArrayList<>();

        Map<UUID, RoadNode> nodes  = graph.getNodes();
        Collection<RoadEdge> edges = graph.allEdges();

        // 1. Every edge's nodeAId and nodeBId must reference real nodes.
        for (RoadEdge edge : edges) {
            if (!nodes.containsKey(edge.getNodeAId())) {
                warnings.add("Edge " + edge.getEdgeId()
                        + " references unknown nodeA: " + edge.getNodeAId());
            }
            if (!nodes.containsKey(edge.getNodeBId())) {
                warnings.add("Edge " + edge.getEdgeId()
                        + " references unknown nodeB: " + edge.getNodeBId());
            }
        }

        // 2. No two edges may share the same unordered {nodeA, nodeB} pair.
        Set<String> seenPairs = new HashSet<>();
        for (RoadEdge edge : edges) {
            UUID a = edge.getNodeAId(), b = edge.getNodeBId();
            // canonical order: lexicographically smaller UUID first
            String pairKey = a.compareTo(b) <= 0 ? a + ":" + b : b + ":" + a;
            if (!seenPairs.add(pairKey)) {
                warnings.add("Duplicate edge pair between nodes " + a + " and " + b
                        + " (one edge: " + edge.getEdgeId() + ")");
            }
        }

        // 3. No orphan nodes — except TERMINUS and POI_STUB (see class-level javadoc).
        Set<UUID> referenced = new HashSet<>();
        for (RoadEdge edge : edges) {
            referenced.add(edge.getNodeAId());
            referenced.add(edge.getNodeBId());
        }
        for (RoadNode node : graph.allNodes()) {
            if (!referenced.contains(node.nodeId())) {
                // TERMINUS and POI_STUB may be unreferenced during certain lifecycle states.
                if (node.type() != RoadNode.NodeType.TERMINUS
                        && node.type() != RoadNode.NodeType.POI_STUB) {
                    warnings.add("Orphan node " + node.nodeId()
                            + " (type=" + node.type() + ", pos=" + node.position() + ")");
                }
            }
        }

        // 4. Cell-path length check.
        //    size == 0 → always invalid.
        //    size == 1 → degenerate but accepted with a warning.
        for (RoadEdge edge : edges) {
            int sz = edge.getCellPath().size();
            if (sz == 0) {
                warnings.add("Edge " + edge.getEdgeId() + " has empty cellPath");
            } else if (sz == 1) {
                warnings.add("Edge " + edge.getEdgeId()
                        + " has degenerate cellPath (single cell); will resolve on re-plan");
            }
        }

        // 5. Spatial index covers every edge with a non-empty cell path.
        //    Queries at the block-centre of the first cell; expects the edge ID in the result.
        //    This is a sanity check only — not an exhaustive coverage test.
        EdgeGridIndex idx = graph.getSpatialIndex();
        for (RoadEdge edge : edges) {
            if (edge.getCellPath().isEmpty()) continue;
            long firstKey = edge.getCellPath().get(0);
            int cellX  = AtlasCell.unpackX(firstKey);
            int cellZ  = AtlasCell.unpackZ(firstKey);
            int blockX = (cellX << AtlasCell.CELL_SHIFT) + AtlasCell.CELL_HALF;
            int blockZ = (cellZ << AtlasCell.CELL_SHIFT) + AtlasCell.CELL_HALF;
            // Query with radius slightly larger than one bucket (256 blocks) to
            // account for cells near bucket boundaries.
            Set<UUID> nearby = idx.edgesNear(blockX, blockZ, 300);
            if (!nearby.contains(edge.getEdgeId())) {
                warnings.add("Spatial index missing edge " + edge.getEdgeId()
                        + " at its first cell (" + cellX + "," + cellZ + ")");
            }
        }

        // 6. cellToEdges reverse map covers every edge's cell path.
        //    For each cell in each edge's cellPath, edgesInCell must return that edge.
        //    Warn on mismatch — reverse map corruption causes silent staleness misses.
        Map<Long, Set<UUID>> cellToEdges = graph.getCellToEdges();
        for (RoadEdge edge : edges) {
            for (long cellKey : edge.getCellPath()) {
                Set<UUID> mappedEdges = cellToEdges.get(cellKey);
                if (mappedEdges == null || !mappedEdges.contains(edge.getEdgeId())) {
                    int cx = AtlasCell.unpackX(cellKey);
                    int cz = AtlasCell.unpackZ(cellKey);
                    warnings.add("cellToEdges missing edge " + edge.getEdgeId()
                            + " at cell (" + cx + "," + cz + ")");
                }
            }
        }

        return warnings;
    }
}
