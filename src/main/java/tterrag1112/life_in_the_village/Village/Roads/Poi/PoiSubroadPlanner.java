package tterrag1112.life_in_the_village.Village.Roads.Poi;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Networking.WorldRoadSavedData;
import tterrag1112.life_in_the_village.Village.Economy.Trade.AtlasRouteRouter;
import tterrag1112.life_in_the_village.Village.Economy.Trade.TradeRouteManager;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadEdge;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadNode;
import tterrag1112.life_in_the_village.Village.Roads.Graph.WorldRoadGraph;
import tterrag1112.life_in_the_village.Village.Roads.Planning.CorridorAttractorBuilder;
import tterrag1112.life_in_the_village.World.Atlas.WorldAtlas;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Track C3.2 (Phase 12) — plans a footpath-tier subroad from a
 * {@link DiscoveredPoi} to the nearest reasonable point on the world
 * road graph.
 *
 * <h3>Pipeline</h3>
 * Mirrors {@code ConnectorPlanner} but with a smaller search radius
 * ({@link PoiDiscovery#PLANNER_RADIUS_BLOCKS}), a {@code LOCAL} edge
 * tier, and no docking-anchor concept (the POI position itself is the
 * source).
 * <ol>
 *   <li>Find candidate edges + nodes within
 *       {@link PoiDiscovery#PLANNER_RADIUS_BLOCKS}. Skip any candidate
 *       node typed {@code POI_STUB} — POIs don't subroad to other POIs.</li>
 *   <li>Sort candidates by distance ascending; route the top
 *       {@link #TOP_K} with corridor-aware A* (same atlas pipeline used
 *       by worldgen).</li>
 *   <li>Pick the shortest successful route. If all candidates fail,
 *       leave the POI in {@code ISOLATED} state.</li>
 *   <li>Create a {@code POI_STUB} node at the POI position; attach the
 *       new {@code LOCAL}-tier edge between the stub and the chosen
 *       candidate (splitting the target edge if mid-edge attach).</li>
 * </ol>
 *
 * <h3>Maintenance</h3>
 * Subroad edges are <strong>not</strong> added to any village's
 * {@code maintainerVillageIds} — no village pays for their upkeep, so
 * they decay faster than connectors. {@code RoadUpkeepSystem} detects
 * the POI_STUB endpoint and applies a stiffer decay rate.
 */
public final class PoiSubroadPlanner {

    private static final Logger LOGGER = LogUtils.getLogger();

    private PoiSubroadPlanner() {}

    /** Top-K candidates routed in detail. Mirrors ConnectorPlanner.TOP_K. */
    public static final int TOP_K = 5;

    /** Corridor padding (cell radius) — narrower than ConnectorPlanner's. */
    public static final int CORRIDOR_PADDING = 3;

    /** Drives one round of subroad planning for all PENDING POIs. */
    public static void planAllPending(ServerLevel level) {
        WorldRoadSavedData saved = WorldRoadSavedData.get(level);
        VillageSavedData vdata = VillageSavedData.get(level);
        WorldRoadGraph graph = saved.getGraph();

        // Snapshot — the planner mutates the saved-data map.
        List<DiscoveredPoi> pending = saved.getAllPois().values().stream()
                .filter(p -> p.status() == DiscoveredPoi.Status.PENDING)
                .toList();

        for (DiscoveredPoi poi : pending) {
            try {
                planOne(level, saved, vdata, graph, poi);
            } catch (RuntimeException ex) {
                LOGGER.warn("[PoiSubroadPlanner] failed for {} at {}: {}",
                        poi.structureType(), poi.position().toShortString(),
                        ex.getMessage());
            }
        }
    }

    private static void planOne(ServerLevel level,
                                 WorldRoadSavedData saved,
                                 VillageSavedData vdata,
                                 WorldRoadGraph graph,
                                 DiscoveredPoi poi) {
        BlockPos poiPos = poi.position();

        // Always create the POI_STUB node, even for ISOLATED records — it's
        // the discovery breadcrumb. The validator exempts unreferenced
        // POI_STUBs (RoadNode.NodeType:35; GraphInvariantValidator:81).
        RoadNode stub = new RoadNode(UUID.randomUUID(), poiPos,
                RoadNode.NodeType.POI_STUB, Optional.empty());
        graph.addNode(stub);

        if (!PoiDiscovery.hasNearbyInfrastructure(poiPos, graph)) {
            saved.putPoi(poi.withNodeId(stub.nodeId()));
            saved.markDirty();
            LOGGER.info("[PoiSubroadPlanner] {} at {} — ISOLATED (no graph within {})",
                    poi.structureType(), poiPos.toShortString(),
                    PoiDiscovery.PLANNER_RADIUS_BLOCKS);
            return;
        }

        // Candidate enumeration: nearby nodes (excluding other POI_STUBs)
        // + nearby edges, mirroring ConnectorPlanner's two-arm approach.
        List<Candidate> candidates = enumerateCandidates(graph, poiPos);
        if (candidates.isEmpty()) {
            saved.putPoi(poi.withNodeId(stub.nodeId()));
            saved.markDirty();
            return;
        }
        candidates.sort(Comparator.comparingLong(c -> c.distSq));
        List<Candidate> topK = candidates.subList(0, Math.min(TOP_K, candidates.size()));

        // Route each top-K via atlas + corridor attractors.
        WorldAtlas atlas = WorldAtlas.get(level);
        Routed best = null;
        for (Candidate c : topK) {
            TradeRouteManager.prefillAtlasCorridor(level, atlas, poiPos, c.targetPos);
            CorridorAttractorBuilder.CorridorAttractors attractors =
                    CorridorAttractorBuilder.buildAttractors(
                            graph, poiPos, c.targetPos, CORRIDOR_PADDING);
            Map<Long, Float> discounts = new HashMap<>(attractors.discounts());
            List<Long> cellPath = AtlasRouteRouter.findRoute(
                    atlas, poiPos, c.targetPos,
                    attractors.cellKeys(), discounts);
            if (cellPath.isEmpty()) continue;
            if (best == null || cellPath.size() < best.cellPath.size()) {
                best = new Routed(c, cellPath);
            }
        }

        if (best == null) {
            saved.putPoi(poi.withNodeId(stub.nodeId()));
            saved.markDirty();
            return;
        }

        UUID edgeId = commitEdge(graph, stub.nodeId(), best);
        saved.putPoi(poi.withNodeId(stub.nodeId()).withEdgeId(edgeId));
        saved.markDirty();
        LOGGER.info("[PoiSubroadPlanner] {} at {} → {} (LOCAL edge {})",
                poi.structureType(), poiPos.toShortString(),
                best.candidate.targetPos.toShortString(),
                edgeId.toString().substring(0, 8));
    }

    private static UUID commitEdge(WorldRoadGraph graph, UUID stubNodeId,
                                    Routed best) {
        UUID junctionNodeId;
        if (best.candidate.edge != null) {
            UUID targetEdgeId = best.candidate.edge.getEdgeId();
            BlockPos cellCenter = AtlasRouteRouter.cellKeyToBlockCenter(best.candidate.attachCellKey);
            Optional<WorldRoadGraph.SplitResult> splitOpt =
                    graph.splitEdgeAtCell(targetEdgeId, best.candidate.attachCellKey, cellCenter);
            if (splitOpt.isEmpty()) {
                junctionNodeId = best.candidate.edge.getNodeAId();
            } else {
                junctionNodeId = splitOpt.get().junctionNodeId();
            }
        } else {
            junctionNodeId = best.candidate.node.nodeId();
        }

        // Subroad edge: LOCAL tier, no maintainer village. Decay accelerates
        // because RoadUpkeepSystem detects the POI_STUB endpoint.
        long meanderSeed = (long) stubNodeId.getMostSignificantBits()
                ^ (long) stubNodeId.getLeastSignificantBits();
        RoadEdge subroad = RoadEdge.create(
                stubNodeId, junctionNodeId,
                best.cellPath, RoadEdge.EdgeTier.LOCAL,
                new RoadEdge.MeanderProfile(2.0f, 0.05f, meanderSeed));
        subroad.setMaintenance(80);
        // No maintainerVillageIds — POI subroads are unmaintained.
        graph.addEdge(subroad);
        return subroad.getEdgeId();
    }

    // =========================================================================
    // Candidate enumeration
    // =========================================================================

    private static final class Candidate {
        final RoadEdge edge;
        final RoadNode node;
        final long attachCellKey;
        final BlockPos targetPos;
        final long distSq;

        Candidate(RoadEdge edge, long attachCellKey, BlockPos targetPos, BlockPos source) {
            this.edge = edge;
            this.node = null;
            this.attachCellKey = attachCellKey;
            this.targetPos = targetPos;
            long dx = targetPos.getX() - source.getX();
            long dz = targetPos.getZ() - source.getZ();
            this.distSq = dx * dx + dz * dz;
        }

        Candidate(RoadNode node, BlockPos source) {
            this.edge = null;
            this.node = node;
            this.attachCellKey = 0L;
            this.targetPos = node.position();
            long dx = targetPos.getX() - source.getX();
            long dz = targetPos.getZ() - source.getZ();
            this.distSq = dx * dx + dz * dz;
        }
    }

    private record Routed(Candidate candidate, List<Long> cellPath) {}

    private static List<Candidate> enumerateCandidates(WorldRoadGraph graph, BlockPos poiPos) {
        long radSq = (long) PoiDiscovery.PLANNER_RADIUS_BLOCKS
                * PoiDiscovery.PLANNER_RADIUS_BLOCKS;
        List<Candidate> out = new ArrayList<>();

        // Node candidates — skip POI_STUBs (no POI-to-POI paths).
        for (RoadNode n : graph.allNodes()) {
            if (n.type() == RoadNode.NodeType.POI_STUB) continue;
            long dx = n.position().getX() - poiPos.getX();
            long dz = n.position().getZ() - poiPos.getZ();
            long dSq = dx * dx + dz * dz;
            if (dSq > radSq) continue;
            out.add(new Candidate(n, poiPos));
        }

        // Edge candidates — find the cell on each nearby edge closest to the POI.
        for (RoadEdge edge : graph.allEdges()) {
            if (edge.getCellPath().isEmpty()) continue;
            // Skip edges that already touch a POI_STUB (don't chain subroads).
            if (touchesPoiStub(graph, edge)) continue;
            long bestCellKey = 0L;
            long bestDistSq = Long.MAX_VALUE;
            for (long cellKey : edge.getCellPath()) {
                BlockPos centre = AtlasRouteRouter.cellKeyToBlockCenter(cellKey);
                long dx = centre.getX() - poiPos.getX();
                long dz = centre.getZ() - poiPos.getZ();
                long dSq = dx * dx + dz * dz;
                if (dSq < bestDistSq) { bestDistSq = dSq; bestCellKey = cellKey; }
            }
            if (bestDistSq > radSq) continue;
            BlockPos target = AtlasRouteRouter.cellKeyToBlockCenter(bestCellKey);
            out.add(new Candidate(edge, bestCellKey, target, poiPos));
        }
        return out;
    }

    private static boolean touchesPoiStub(WorldRoadGraph graph, RoadEdge edge) {
        RoadNode a = graph.getNode(edge.getNodeAId());
        RoadNode b = graph.getNode(edge.getNodeBId());
        return (a != null && a.type() == RoadNode.NodeType.POI_STUB)
                || (b != null && b.type() == RoadNode.NodeType.POI_STUB);
    }
}
