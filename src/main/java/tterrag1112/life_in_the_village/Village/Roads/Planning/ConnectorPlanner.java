package tterrag1112.life_in_the_village.Village.Roads.Planning;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Networking.VillageRoadsSavedData;
import tterrag1112.life_in_the_village.Networking.WorldRoadSavedData;
import tterrag1112.life_in_the_village.Village.Economy.Trade.AtlasRouteRouter;
import tterrag1112.life_in_the_village.Village.Economy.Trade.TradeRouteManager;
import tterrag1112.life_in_the_village.World.Atlas.AtlasCell;
import tterrag1112.life_in_the_village.Village.Roads.Docking.VillageDockingPoint;
import tterrag1112.life_in_the_village.Village.Roads.Graph.GraphInvariantValidator;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadEdge;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadNode;
import tterrag1112.life_in_the_village.Village.Roads.Graph.VillageRoadGraph;
import tterrag1112.life_in_the_village.Village.Roads.Graph.VillageRoadNode;
import tterrag1112.life_in_the_village.Village.Decoration.VillageSizeTier;
import tterrag1112.life_in_the_village.Village.Roads.Economy.TierPromotionRules;
import tterrag1112.life_in_the_village.Village.Roads.Graph.WorldRoadGraph;
import tterrag1112.life_in_the_village.Village.Village;
import tterrag1112.life_in_the_village.World.Atlas.WorldAtlas;

import java.util.*;

/**
 * Corridor-aware connector routing for new villages (Phase 3b).
 *
 * <p>Replaces {@link TradeRouteManager#establishRoutes} for villages
 * that have {@link Village#useGraphConnector()} == true. Both systems
 * coexist until Phase 5.
 *
 * <h3>Algorithm overview</h3>
 * <ol>
 *   <li>Query the graph for edges and VILLAGE_DOCK nodes within
 *       {@code searchRadiusBlocks} of the village anchor.</li>
 *   <li>Score candidates by distance to the village's docking anchor.</li>
 *   <li>Route the top-5 candidates with corridor-aware attractor injection.</li>
 *   <li>Pick the shortest resulting cell path.</li>
 *   <li>Commit: split edge if needed, create connector edge, persist.</li>
 * </ol>
 */
public final class ConnectorPlanner {

    private ConnectorPlanner() {}

    /** Default search radius for nearby graph infrastructure. */
    public static final int DEFAULT_SEARCH_RADIUS = 2000;

    /** Maximum candidates routed via A* in Step 3. */
    private static final int TOP_K = 5;

    /** Corridor padding fed to {@link CorridorAttractorBuilder}. */
    private static final int CORRIDOR_PADDING = 192;

    // =========================================================================
    // Result record
    // =========================================================================

    /**
     * The outcome of a {@link #planConnector} call. All UUID fields reference
     * nodes/edges that have already been committed to the graph.
     *
     * @param villageDockNodeId  the VILLAGE_DOCK node that was created or reused
     * @param connectorEdgeId    the new CONNECTOR edge, or empty for isolated villages
     * @param newJunctionNodeId  the TRUNK_JUNCTION created by an edge split, if any
     * @param splitEdgeIds       the two half-edges from the split, if any
     * @param obsoletedEdgeId    the original edge that was split, if any
     * @param planSummary        human-readable description for logs and progress entries
     */
    public record ConnectorPlanResult(
            UUID villageDockNodeId,
            Optional<UUID> connectorEdgeId,
            Optional<UUID> newJunctionNodeId,
            List<UUID> splitEdgeIds,
            Optional<UUID> obsoletedEdgeId,
            String planSummary) {}

    // =========================================================================
    // Candidate wrapper (package-private for testing)
    // =========================================================================

    /** Internal scoring wrapper for one attachment candidate. */
    private static final class Candidate {
        /** The edge being attached to, or null if this is a NODE candidate. */
        final RoadEdge edge;
        /** The node being attached to, or null if this is an EDGE candidate. */
        final RoadNode node;
        /** Cell key on the edge's path closest to the village, or 0 for node candidates. */
        final long attachCellKey;
        /** Block-center of the attachment point, surface-snapped. */
        final BlockPos targetPos;
        /** Squared distance from village anchor to targetPos (for initial sort). */
        final long distSq;

        Candidate(RoadEdge edge, long attachCellKey, BlockPos targetPos, BlockPos villageAnchor) {
            this.edge = edge;
            this.node = null;
            this.attachCellKey = attachCellKey;
            this.targetPos = targetPos;
            long dx = targetPos.getX() - villageAnchor.getX();
            long dz = targetPos.getZ() - villageAnchor.getZ();
            this.distSq = dx * dx + dz * dz;
        }

        Candidate(RoadNode node, BlockPos villageAnchor) {
            this.edge = null;
            this.node = node;
            this.attachCellKey = 0L;
            this.targetPos = node.position();
            long dx = targetPos.getX() - villageAnchor.getX();
            long dz = targetPos.getZ() - villageAnchor.getZ();
            this.distSq = dx * dx + dz * dz;
        }

        boolean isEdge() { return edge != null; }
        String describe() {
            if (isEdge()) {
                return "edge " + edge.getEdgeId().toString().substring(0, 8)
                        + " [" + edge.getTier() + "] cell "
                        + Long.toHexString(attachCellKey).substring(0, Math.min(8, Long.toHexString(attachCellKey).length()));
            }
            return "node " + node.nodeId().toString().substring(0, 8)
                    + " [" + node.type() + "]";
        }
    }

    // =========================================================================
    // Gateway selection
    // =========================================================================

    /**
     * Selects the best gateway of the given village for a connector coming from
     * {@code externalEndpoint}.
     *
     * <p>Selection criteria (in priority order):
     * <ol>
     *   <li>Alignment: gateways whose outward direction points toward the external
     *       endpoint score higher (reduces snaking).</li>
     *   <li>Among acceptable gateways, prefer the closest (small bonus).</li>
     *   <li>Tiebreaker: PRIMARY over SIDE over REAR.</li>
     * </ol>
     * Gateways with alignment score below {@code -0.3} (more than ~107° off) are
     * rejected.  If all gateways fail the alignment filter, the PRIMARY gateway is
     * returned as a fallback.
     *
     * @return the selected gateway, or empty if the village has no gateways
     */
    public static Optional<VillageRoadNode> selectGateway(
            VillageRoadGraph villageGraph,
            BlockPos externalEndpoint) {

        List<VillageRoadNode> gateways = villageGraph.gateways();
        if (gateways.isEmpty()) return Optional.empty();

        VillageRoadNode best = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (VillageRoadNode gateway : gateways) {
            VillageRoadNode.GatewayInfo info = gateway.gatewayInfo().orElse(null);
            if (info == null) continue;

            double dx = externalEndpoint.getX() - gateway.position().getX();
            double dz = externalEndpoint.getZ() - gateway.position().getZ();
            double len = Math.sqrt(dx * dx + dz * dz);

            double alignment;
            if (len < 0.001) {
                // Degenerate: external point is at the gateway — prefer PRIMARY
                alignment = info.role() == VillageRoadNode.GatewayRole.PRIMARY ? 1.0 : 0.0;
            } else {
                double nx = dx / len, nz = dz / len;
                double rad = info.outwardDirection().toRadians();
                double ux = Math.cos(rad), uz = Math.sin(rad);
                alignment = nx * ux + nz * uz;
            }

            if (alignment < -0.3) continue; // more than ~107° off — reject

            // Small closeness bonus + role priority
            double distBonus = 100.0 / (len + 1.0) * 0.01;
            double rolePriority = switch (info.role()) {
                case PRIMARY -> 0.02;
                case SIDE    -> 0.01;
                case REAR    -> 0.00;
            };
            double score = alignment + distBonus + rolePriority;

            if (score > bestScore) {
                bestScore = score;
                best = gateway;
            }
        }

        if (best == null) {
            // All gateways failed alignment — fall back to PRIMARY
            best = gateways.stream()
                    .filter(g -> g.gatewayInfo()
                            .map(i -> i.role() == VillageRoadNode.GatewayRole.PRIMARY)
                            .orElse(false))
                    .findFirst()
                    .orElse(gateways.get(0));
        }

        return Optional.of(best);
    }

    // =========================================================================
    // Main entry point
    // =========================================================================

    /**
     * Plans a connector from {@code village} into the existing road graph.
     *
     * @param level             the server level (for surface-height queries and atlas)
     * @param graph             the world road graph
     * @param data              village saved data (gate positions, paths)
     * @param village           the village to connect
     * @param searchRadiusBlocks radius to search for attachment candidates
     * @return the committed plan result (never null)
     */
    public static ConnectorPlanResult planConnector(
            ServerLevel level,
            WorldRoadGraph graph,
            VillageSavedData data,
            Village village,
            int searchRadiusBlocks) {

        BlockPos anchor = village.getAnchorPos();
        if (anchor == null) {
            return isolatedResult(graph, data, village, level,
                    "Village '" + village.getName() + "' has no anchor pos; registered as isolated dock.");
        }

        // ── Step 1: Find candidates ───────────────────────────────────────────
        List<RoadEdge> nearbyEdges = graph.edgesNear(
                anchor.getX(), anchor.getZ(), searchRadiusBlocks);
        List<RoadNode> nearbyDocks = graph.nodesNear(
                anchor.getX(), anchor.getZ(), searchRadiusBlocks)
                .stream()
                .filter(n -> n.type() == RoadNode.NodeType.VILLAGE_DOCK
                          || n.type() == RoadNode.NodeType.TRUNK_JUNCTION
                          || n.type() == RoadNode.NodeType.GREAT_ROAD_ANCHOR)
                .toList();

        if (nearbyEdges.isEmpty() && nearbyDocks.isEmpty()) {
            return isolatedResult(graph, data, village, level,
                    "No nearby graph infrastructure within " + searchRadiusBlocks
                            + " blocks of '" + village.getName() + "'; registered as isolated dock.");
        }

        // ── Step 2: Build candidate list and sort ─────────────────────────────
        List<Candidate> candidates = new ArrayList<>();
        long rSqL = (long) searchRadiusBlocks * searchRadiusBlocks;

        for (RoadEdge edge : nearbyEdges) {
            if (edge.getCellPath().isEmpty()) continue;
            // Find cell on edge closest to village anchor
            long bestCellKey = 0L;
            long bestDistSq = Long.MAX_VALUE;
            for (long cellKey : edge.getCellPath()) {
                BlockPos center = AtlasRouteRouter.cellKeyToBlockCenter(cellKey);
                long dx = center.getX() - anchor.getX();
                long dz = center.getZ() - anchor.getZ();
                long dSq = dx * dx + dz * dz;
                if (dSq < bestDistSq) { bestDistSq = dSq; bestCellKey = cellKey; }
            }
            if (bestDistSq > rSqL) continue; // attachment point is outside radius
            BlockPos cellCenter = AtlasRouteRouter.cellKeyToBlockCenter(bestCellKey);
            BlockPos targetPos = surfaceSnap(level, cellCenter);
            candidates.add(new Candidate(edge, bestCellKey, targetPos, anchor));
        }

        for (RoadNode node : nearbyDocks) {
            long dx = node.position().getX() - anchor.getX();
            long dz = node.position().getZ() - anchor.getZ();
            if (dx * dx + dz * dz > rSqL) continue;
            candidates.add(new Candidate(node, anchor));
        }

        if (candidates.isEmpty()) {
            return isolatedResult(graph, data, village, level,
                    "No reachable candidates within " + searchRadiusBlocks
                            + " blocks of '" + village.getName() + "'; registered as isolated dock.");
        }

        // Sort by distance ascending, take top K
        candidates.sort(Comparator.comparingLong(c -> c.distSq));
        List<Candidate> topK = candidates.subList(0, Math.min(TOP_K, candidates.size()));

        // ── Step 3: Route each top candidate ─────────────────────────────────
        WorldAtlas atlas = WorldAtlas.get(level);

        // Compute anchor-zone penalty cells for all other villages (3×3 cells around each anchor).
        // Connector routing should avoid routing THROUGH unrelated villages.
        Map<Long, Float> interVillagePenalties = buildInterVillagePenalties(data, village);

        // Look up this village's gateway graph once (used for all candidates)
        VillageRoadGraph villageGraph =
                VillageRoadsSavedData.get(level).getOrCreate(village.getId());

        record RouteCandidate(Candidate candidate, VillageDockingPoint dock, List<Long> cellPath) {}
        List<RouteCandidate> routed = new ArrayList<>();

        for (Candidate c : topK) {
            // Gateway-aware docking: if the village has gateways, route from the
            // best gateway's armEndpoint instead of VillageDockingPoint's anchor.
            // This prevents the connector from approaching the "wrong" side of the village.
            Optional<VillageRoadNode> selectedGateway = selectGateway(villageGraph, c.targetPos);

            VillageDockingPoint dock;
            if (selectedGateway.isPresent()) {
                VillageRoadNode.GatewayInfo gwInfo = selectedGateway.get().gatewayInfo().get();
                BlockPos armEndpoint = gwInfo.armEndpoint();
                double dirRad = gwInfo.outwardDirection().toRadians();
                // armEndpoint IS the external docking anchor; approach length is 0
                // because the gateway arm already covers the gap to the village boundary.
                dock = new VillageDockingPoint(
                        selectedGateway.get().position(),
                        armEndpoint,
                        dirRad,
                        village.getId(),
                        0);
            } else {
                dock = VillageDockingPoint.compute(village, c.targetPos, level, data);
            }
            BlockPos dockingAnchor = dock.dockingAnchor();

            TradeRouteManager.prefillAtlasCorridor(level, atlas, dockingAnchor, c.targetPos);

            CorridorAttractorBuilder.CorridorAttractors attractors =
                    CorridorAttractorBuilder.buildAttractors(
                            graph, dockingAnchor, c.targetPos, CORRIDOR_PADDING);

            // Culture-specific routing cost modifier for this village
            AtlasRouteRouter.CellCostModifier costModifier =
                    AtlasRouteRouter.modifierForCulture(village.getVillageType());

            // Merge corridor discounts with inter-village penalties.
            // putIfAbsent: existing-road corridor discounts take priority; penalties apply
            // only to non-road cells in other villages' anchor zones.
            Map<Long, Float> mergedDiscounts = new HashMap<>(attractors.discounts());
            interVillagePenalties.forEach(mergedDiscounts::putIfAbsent);

            List<Long> cellPath = AtlasRouteRouter.findRoute(
                    atlas, dockingAnchor, c.targetPos,
                    attractors.cellKeys(), mergedDiscounts, costModifier);

            if (!cellPath.isEmpty()) {
                routed.add(new RouteCandidate(c, dock, cellPath));
            }
        }

        if (routed.isEmpty()) {
            // All routes failed — create isolated dock anyway
            String msg = "No viable route from '" + village.getName()
                    + "' to any of " + topK.size() + " candidates within "
                    + searchRadiusBlocks + " blocks. Nearest attempted: "
                    + topK.get(0).describe() + ". Registered as isolated dock.";
            return isolatedResult(graph, data, village, level, msg);
        }

        // ── Step 4: Pick the shortest route ──────────────────────────────────
        RouteCandidate best = routed.stream()
                .min(Comparator.comparingInt(r -> r.cellPath().size()))
                .orElseThrow();

        // ── Step 5: Commit ───────────────────────────────────────────────────
        return commitPlan(graph, data, village, best.candidate(),
                best.dock(), best.cellPath(), level);
    }

    // =========================================================================
    // Commit step
    // =========================================================================

    private static ConnectorPlanResult commitPlan(
            WorldRoadGraph graph,
            VillageSavedData data,
            Village village,
            Candidate candidate,
            VillageDockingPoint dock,
            List<Long> connectorCellPath,
            ServerLevel level) {

        // Create or reuse the VILLAGE_DOCK node
        RoadNode dockNode = getOrCreateDockNode(graph, data, village, dock.dockingAnchor());

        UUID junctionNodeId = null;
        List<UUID> splitEdgeIds = Collections.emptyList();
        UUID obsoletedEdgeId = null;

        if (candidate.isEdge()) {
            // Mid-edge attachment: split the target edge
            UUID targetEdgeId = candidate.edge.getEdgeId();
            long splitCellKey = candidate.attachCellKey;
            // If the edge is already realized, snap the junction to the nearest actual
            // block in its blockPath rather than the cell center — this eliminates the
            // ~10–20 block gap that the cell-center approximation produces.
            BlockPos cellCenter = surfaceSnap(level, AtlasRouteRouter.cellKeyToBlockCenter(splitCellKey));
            BlockPos junctionPos = junctionPosFor(candidate.edge, cellCenter);

            Optional<WorldRoadGraph.SplitResult> splitOpt =
                    graph.splitEdgeAtCell(targetEdgeId, splitCellKey, junctionPos);

            if (splitOpt.isEmpty()) {
                // Split failed (e.g., cell not in path) — fall back to attaching to the edge's nodeA
                System.out.println("[ConnectorPlanner] WARNING: split of edge "
                        + targetEdgeId.toString().substring(0, 8)
                        + " at cell " + Long.toHexString(splitCellKey)
                        + " failed; attaching to edge nodeA instead.");
                junctionNodeId = candidate.edge.getNodeAId();
            } else {
                WorldRoadGraph.SplitResult split = splitOpt.get();
                junctionNodeId = split.junctionNodeId();
                splitEdgeIds = List.of(split.newEdgeAId(), split.newEdgeBId());
                obsoletedEdgeId = targetEdgeId;
            }
        } else {
            // Node candidate — attach directly
            junctionNodeId = candidate.node.nodeId();
        }

        // Create the CONNECTOR edge
        long meanderSeed = (long) village.getId().getMostSignificantBits()
                ^ (long) village.getId().getLeastSignificantBits();
        // D5 Phase 6d: initial tier derives from village size (invariant 6)
        RoadEdge.EdgeTier initialTier = TierPromotionRules.naturalTierForVillage(
                VillageSizeTier.fromBuildingCount(village.getBuildingIds().size()));
        RoadEdge connectorEdge = RoadEdge.create(
                dockNode.nodeId(), junctionNodeId,
                connectorCellPath,
                initialTier,
                new RoadEdge.MeanderProfile(3.0f, 0.1f, meanderSeed));
        connectorEdge.setMaintenance(100);
        connectorEdge.getMaintainerVillageIds().add(village.getId());
        graph.addEdge(connectorEdge);

        WorldRoadSavedData.get(level).markDirty();

        // Validate. B2.8 — most warnings are pre-existing graph state
        // (duplicate edges, degenerate cellPaths from old connectors)
        // surfaced again on every validation. They're informational,
        // not actionable per-spawn. Log a single summary line at INFO
        // and detail at DEBUG so the spawn log isn't flooded.
        List<String> warnings = GraphInvariantValidator.validate(graph);
        if (warnings.isEmpty()) {
            org.slf4j.LoggerFactory.getLogger(ConnectorPlanner.class).info(
                    "[ConnectorPlanner] Graph OK — {} nodes, {} edges",
                    graph.allNodes().size(), graph.allEdges().size());
        } else {
            org.slf4j.LoggerFactory.getLogger(ConnectorPlanner.class).info(
                    "[ConnectorPlanner] Graph has {} validator warnings "
                            + "(graph state has {} nodes, {} edges; details at DEBUG)",
                    warnings.size(), graph.allNodes().size(), graph.allEdges().size());
            org.slf4j.Logger debugLog = org.slf4j.LoggerFactory.getLogger(ConnectorPlanner.class);
            if (debugLog.isDebugEnabled()) {
                warnings.forEach(w -> debugLog.debug("[ConnectorPlanner] WARN: {}", w));
            }
        }

        String summary = buildSummary(village, candidate, connectorCellPath,
                junctionNodeId, splitEdgeIds, obsoletedEdgeId);

        return new ConnectorPlanResult(
                dockNode.nodeId(),
                Optional.of(connectorEdge.getEdgeId()),
                Optional.ofNullable(junctionNodeId),
                splitEdgeIds,
                Optional.ofNullable(obsoletedEdgeId),
                summary);
    }

    // =========================================================================
    // Isolated-dock fallback
    // =========================================================================

    private static ConnectorPlanResult isolatedResult(
            WorldRoadGraph graph,
            VillageSavedData data,
            Village village,
            ServerLevel level,
            String reason) {
        RoadNode dockNode = getOrCreateDockNode(graph, data, village,
                village.getAnchorPos() != null ? village.getAnchorPos() : BlockPos.ZERO);
        WorldRoadSavedData.get(level).markDirty();
        return new ConnectorPlanResult(
                dockNode.nodeId(),
                Optional.empty(),
                Optional.empty(),
                Collections.emptyList(),
                Optional.empty(),
                reason);
    }

    // =========================================================================
    // Dock-node management
    // =========================================================================

    private static RoadNode getOrCreateDockNode(
            WorldRoadGraph graph,
            VillageSavedData data,
            Village village,
            BlockPos dockingAnchor) {

        Optional<UUID> existingId = village.getDockNodeId();
        if (existingId.isPresent()) {
            RoadNode existing = graph.getNode(existingId.get());
            if (existing != null) {
                System.out.println("[ConnectorPlanner] WARNING: village '" + village.getName()
                        + "' already has dock node "
                        + existingId.get().toString().substring(0, 8)
                        + " — reusing existing node (position not updated).");
                return existing;
            }
            // Stale reference — fall through and create a new one
        }

        RoadNode dockNode = new RoadNode(UUID.randomUUID(), dockingAnchor,
                RoadNode.NodeType.VILLAGE_DOCK, Optional.empty());
        graph.addNode(dockNode);
        village.setDockNodeId(dockNode.nodeId());
        data.setDirty();
        return dockNode;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Determines the junction node position for a mid-edge split.
     *
     * <p>If the target edge has already been realized (non-empty blockPath), the
     * junction is placed at the block in the blockPath geometrically closest to
     * {@code cellBlockCenter}. This keeps incident-edge block paths topologically
     * consistent: the connector's blockPath will terminate at an exact block that
     * is already part of the existing road, so {@code inspect_junction} distance ≈ 0.
     *
     * <p>If the edge is not yet realized, the cell block center is used — it will be
     * corrected when the edge realizes and the blockPath split re-anchors to this pos.
     */
    private static BlockPos junctionPosFor(RoadEdge edge, BlockPos cellBlockCenter) {
        if (!edge.isRealized() || edge.getBlockPath().isEmpty()) {
            return cellBlockCenter;
        }
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos p : edge.getBlockPath()) {
            double d = p.distSqr(cellBlockCenter);
            if (d < bestDist) { bestDist = d; best = p; }
        }
        return best != null ? best : cellBlockCenter;
    }

    private static BlockPos surfaceSnap(ServerLevel level, BlockPos raw) {
        if (!level.isLoaded(raw)) return new BlockPos(raw.getX(), 64, raw.getZ());
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                raw.getX(), raw.getZ());
        return new BlockPos(raw.getX(), y, raw.getZ());
    }

    /**
     * Builds a map of cell-key → cost multiplier (3.0) for the 3×3 anchor-zone cells
     * of every village other than {@code excludeVillage}. Applied during routing so
     * connectors avoid passing through other villages' building zones.
     */
    private static Map<Long, Float> buildInterVillagePenalties(VillageSavedData data,
                                                                Village excludeVillage) {
        Map<Long, Float> penalties = new HashMap<>();
        for (Village v : data.getAllVillages()) {
            if (v.getId().equals(excludeVillage.getId())) continue;
            BlockPos anchor = v.getAnchorPos();
            if (anchor == null) continue;
            int cx = WorldAtlas.blockToCell(anchor.getX());
            int cz = WorldAtlas.blockToCell(anchor.getZ());
            for (int ddx = -1; ddx <= 1; ddx++) {
                for (int ddz = -1; ddz <= 1; ddz++) {
                    penalties.put(AtlasCell.packKey(cx + ddx, cz + ddz), 3.0f);
                }
            }
        }
        return penalties;
    }

    private static String buildSummary(
            Village village,
            Candidate candidate,
            List<Long> connectorCellPath,
            UUID junctionNodeId,
            List<UUID> splitEdgeIds,
            UUID obsoletedEdgeId) {
        StringBuilder sb = new StringBuilder("Village '")
                .append(village.getName()).append("' → ");
        if (candidate.isEdge()) {
            sb.append("mid-edge split of ").append(candidate.describe());
            sb.append(" → junction ").append(junctionNodeId.toString().substring(0, 8));
            if (!splitEdgeIds.isEmpty()) {
                sb.append(" (split into ")
                        .append(splitEdgeIds.get(0).toString().substring(0, 8))
                        .append(" + ")
                        .append(splitEdgeIds.get(1).toString().substring(0, 8))
                        .append(")");
            }
        } else {
            sb.append("node ").append(candidate.describe());
        }
        sb.append(". Connector path: ").append(connectorCellPath.size()).append(" cells.");
        return sb.toString();
    }
}
