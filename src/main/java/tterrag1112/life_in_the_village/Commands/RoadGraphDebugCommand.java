package tterrag1112.life_in_the_village.Commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Networking.WorldRoadSavedData;
import tterrag1112.life_in_the_village.Village.Economy.Trade.AtlasRouteRouter;
import tterrag1112.life_in_the_village.Village.Economy.Trade.Caravan;
import tterrag1112.life_in_the_village.Village.Economy.Trade.CaravanSavedData;
import tterrag1112.life_in_the_village.Village.Roads.Debug.RoadDebugVisualizer;
import tterrag1112.life_in_the_village.Village.Roads.Debug.RoadDebugVisualizer.ParticleEmission;
import tterrag1112.life_in_the_village.Village.Roads.Docking.VillageDockingPoint;
import tterrag1112.life_in_the_village.Village.Roads.Graph.GraphInvariantValidator;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadEdge;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadNode;
import tterrag1112.life_in_the_village.Village.Roads.Graph.WorldRoadGraph;
import tterrag1112.life_in_the_village.Village.Roads.Realization.EdgeRealizer;
import tterrag1112.life_in_the_village.Village.Village;
import tterrag1112.life_in_the_village.World.Atlas.AtlasCell;
import tterrag1112.life_in_the_village.World.Atlas.WorldAtlas;

import java.util.*;

/**
 * Debug visualization commands for the world road graph.
 *
 * <p>Registered under {@code /liv road debug} (singular "road", distinct from the
 * legacy {@code /liv roads} block-manipulation commands). Requires OP level 2
 * and must be run by a player — not from console.
 *
 * <p>All visualization creates timed {@link RoadDebugVisualizer.VisualizationSession}s
 * that emit player-local particles for {@link RoadDebugVisualizer#DURATION_TICKS} ticks
 * (30 seconds). Multiple subcommands can run simultaneously per player; each adds its
 * own session.
 *
 * <h3>Particle convention (matches ROADS_PLAN.md Phase 1.5)</h3>
 * <ul>
 *   <li>GREAT_ROAD edges: {@code END_ROD} (white)</li>
 *   <li>TRUNK edges: {@code FLAME} (orange)</li>
 *   <li>CONNECTOR edges: {@code COMPOSTER} (green)</li>
 *   <li>LOCAL edges: {@code SMOKE} (grey)</li>
 *   <li>Anchor/junction nodes: {@code END_ROD}</li>
 *   <li>Village dock nodes: {@code HAPPY_VILLAGER}</li>
 *   <li>Toll gate nodes: {@code SOUL_FIRE_FLAME}</li>
 *   <li>Terminus/POI/waystation nodes: {@code SMOKE}</li>
 * </ul>
 */
public class RoadGraphDebugCommand {

    private static final int RADIUS_STANDARD = 512;
    private static final int RADIUS_PARALLEL  = 1024;

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("liv")
                .then(Commands.literal("road")
                        .then(Commands.literal("debug")
                                .requires(src -> src.hasPermission(2))
                                .then(Commands.literal("show_graph")
                                        .executes(RoadGraphDebugCommand::showGraph))
                                .then(Commands.literal("highlight_edge")
                                        .then(Commands.argument("edgeId", StringArgumentType.word())
                                                .executes(RoadGraphDebugCommand::highlightEdge)))
                                .then(Commands.literal("show_parallel_pairs")
                                        .executes(RoadGraphDebugCommand::showParallelPairs))
                                .then(Commands.literal("show_junctions")
                                        .executes(RoadGraphDebugCommand::showJunctions))
                                .then(Commands.literal("show_staleness")
                                        .executes(RoadGraphDebugCommand::showStaleness))
                                .then(Commands.literal("show_traffic")
                                        .executes(RoadGraphDebugCommand::showTraffic))
                                .then(Commands.literal("show_maintenance")
                                        .executes(RoadGraphDebugCommand::showMaintenance))
                                .then(Commands.literal("show_overgrowth")
                                        .executes(RoadGraphDebugCommand::showOvergrowth))
                                .then(Commands.literal("seed_great_road")
                                        .then(Commands.argument("x1", IntegerArgumentType.integer())
                                        .then(Commands.argument("z1", IntegerArgumentType.integer())
                                        .then(Commands.argument("x2", IntegerArgumentType.integer())
                                        .then(Commands.argument("z2", IntegerArgumentType.integer())
                                                .executes(RoadGraphDebugCommand::seedGreatRoad))))))
                                .then(Commands.literal("connect_village")
                                        .then(Commands.argument("villageName", StringArgumentType.word())
                                        .then(Commands.argument("targetEdgeId", StringArgumentType.word())
                                                .executes(RoadGraphDebugCommand::connectVillage))))
                                .then(Commands.literal("dispatch_caravan")
                                        .then(Commands.argument("villageName", StringArgumentType.word())
                                                .executes(RoadGraphDebugCommand::dispatchCaravan)))
                                .then(Commands.literal("caravan_status")
                                        .executes(RoadGraphDebugCommand::caravanStatus))
                        )
                )
        );
    }

    // =========================================================================
    // show_graph
    // =========================================================================

    private static int showGraph(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel level   = ctx.getSource().getLevel();
        WorldRoadGraph graph = WorldRoadSavedData.get(level).getGraph();

        List<ParticleEmission> emissions = new ArrayList<>();

        // Edge trails
        List<RoadEdge> edges = graph.edgesNear(player.blockX(), player.blockZ(), RADIUS_STANDARD);
        for (RoadEdge edge : edges) {
            emissions.addAll(buildEdgeEmissions(edge, particleForTier(edge.getTier()), level,
                    RoadDebugVisualizer.DEFAULT_EMIT_INTERVAL));
        }

        // Node beams — iterate all nodes and filter by distance
        int nodeCount = 0;
        for (RoadNode node : graph.allNodes()) {
            if (node.position().distSqr(player.blockPosition()) > (double) RADIUS_STANDARD * RADIUS_STANDARD) continue;
            emissions.addAll(buildNodeBeam(node, 10, particleForNodeType(node.type())));
            nodeCount++;
        }

        RoadDebugVisualizer.INSTANCE.addSession(player.getUUID(), level.getGameTime(), emissions);

        int ec = edges.size(), nc = nodeCount;
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Showing " + ec + " edges and " + nc + " nodes within "
                        + RADIUS_STANDARD + " blocks for 30 seconds."), false);
        return ec + nc;
    }

    // =========================================================================
    // highlight_edge
    // =========================================================================

    private static int highlightEdge(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel level   = ctx.getSource().getLevel();
        WorldRoadGraph graph = WorldRoadSavedData.get(level).getGraph();

        String input = StringArgumentType.getString(ctx, "edgeId").toLowerCase(Locale.ROOT);

        List<RoadEdge> matches = new ArrayList<>();
        for (RoadEdge edge : graph.allEdges()) {
            if (edge.getEdgeId().toString().startsWith(input)) matches.add(edge);
        }

        if (matches.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal(
                    "No edge matches prefix '" + input + "'."));
            return 0;
        }
        if (matches.size() > 1) {
            String list = matches.stream()
                    .limit(5)
                    .map(e -> e.getEdgeId().toString().substring(0, Math.min(8, e.getEdgeId().toString().length())))
                    .reduce((a, b) -> a + ", " + b).orElse("");
            String suffix = matches.size() > 5 ? " (+" + (matches.size() - 5) + " more)" : "";
            ctx.getSource().sendFailure(Component.literal(
                    "Ambiguous edge prefix '" + input + "'. Matches: " + list + suffix + "."));
            return 0;
        }

        RoadEdge edge = matches.get(0);
        List<ParticleEmission> emissions = new ArrayList<>(
                buildEdgeEmissions(edge, ParticleTypes.ENCHANT, level,
                        RoadDebugVisualizer.DEFAULT_EMIT_INTERVAL));

        for (UUID nodeId : List.of(edge.getNodeAId(), edge.getNodeBId())) {
            RoadNode node = graph.getNode(nodeId);
            if (node != null) emissions.addAll(buildNodeBeam(node, 12, ParticleTypes.ENCHANT));
        }

        RoadDebugVisualizer.INSTANCE.addSession(player.getUUID(), level.getGameTime(), emissions);

        String shortId = edge.getEdgeId().toString().substring(0, 8);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Highlighting edge " + shortId + ": tier=" + edge.getTier()
                        + ", maintenance=" + edge.getMaintenance()
                        + ", length=" + edge.getCellPath().size() + " cells."), false);
        return 1;
    }

    // =========================================================================
    // show_parallel_pairs
    // =========================================================================

    private static int showParallelPairs(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel level   = ctx.getSource().getLevel();
        WorldRoadGraph graph = WorldRoadSavedData.get(level).getGraph();

        List<RoadEdge> edges = graph.edgesNear(player.blockX(), player.blockZ(), RADIUS_PARALLEL);

        // Pre-compute sample point lists once per edge
        List<List<BlockPos>> samples = new ArrayList<>(edges.size());
        for (RoadEdge edge : edges) samples.add(sampleEdgeForParallel(edge, level));

        List<ParticleEmission> emissions = new ArrayList<>();
        int parallelCount = 0;

        for (int i = 0; i < edges.size(); i++) {
            for (int j = i + 1; j < edges.size(); j++) {
                List<BlockPos> sa = samples.get(i);
                List<BlockPos> sb = samples.get(j);
                if (sa.isEmpty() || sb.isEmpty()) continue;

                int closeCount = 0;
                outer:
                for (BlockPos a : sa) {
                    for (BlockPos b : sb) {
                        double dx = a.getX() - b.getX();
                        double dz = a.getZ() - b.getZ();
                        if (dx * dx + dz * dz < 120.0 * 120.0 && ++closeCount > 8) break outer;
                    }
                }

                if (closeCount > 8) {
                    BlockPos midA = sa.get(sa.size() / 2);
                    BlockPos midB = sb.get(sb.size() / 2);
                    emissions.addAll(buildLine(midA, midB, ParticleTypes.WITCH));
                    parallelCount++;
                }
            }
        }

        if (!emissions.isEmpty()) {
            RoadDebugVisualizer.INSTANCE.addSession(player.getUUID(), level.getGameTime(), emissions);
        }

        int pc = parallelCount;
        if (pc == 0) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "No parallel pairs detected."), false);
        } else {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "Found " + pc + " parallel pairs within " + RADIUS_PARALLEL + " blocks."), false);
        }
        return pc;
    }

    // =========================================================================
    // show_junctions
    // =========================================================================

    private static int showJunctions(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel level   = ctx.getSource().getLevel();
        WorldRoadGraph graph = WorldRoadSavedData.get(level).getGraph();

        // Build edge-count-per-node across all edges in the graph
        Map<UUID, Integer> edgeCountByNode = new HashMap<>();
        for (RoadEdge edge : graph.allEdges()) {
            edgeCountByNode.merge(edge.getNodeAId(), 1, Integer::sum);
            edgeCountByNode.merge(edge.getNodeBId(), 1, Integer::sum);
        }

        List<ParticleEmission> emissions = new ArrayList<>();
        int totalNodes = 0, junctions = 0, docks = 0, termini = 0;

        for (RoadNode node : graph.allNodes()) {
            if (node.position().distSqr(player.blockPosition()) > (double) RADIUS_STANDARD * RADIUS_STANDARD) continue;

            int ec     = edgeCountByNode.getOrDefault(node.nodeId(), 0);
            // isolated/1-edge = 5 blocks tall; +5 per additional edge (2→10, 3→15, 4+→20)
            int height = 5 + 5 * Math.min(3, Math.max(0, ec - 1));

            emissions.addAll(buildNodeBeam(node, height, particleForNodeType(node.type())));
            totalNodes++;

            if (ec >= 3) junctions++;
            if (node.type() == RoadNode.NodeType.VILLAGE_DOCK)  docks++;
            if (node.type() == RoadNode.NodeType.TERMINUS)       termini++;
        }

        if (!emissions.isEmpty()) {
            RoadDebugVisualizer.INSTANCE.addSession(player.getUUID(), level.getGameTime(), emissions);
        }

        int tn = totalNodes, jn = junctions, dn = docks, tm = termini;
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Showing " + tn + " nodes within " + RADIUS_STANDARD + " blocks. "
                        + jn + " junctions (3+ edges), " + dn + " docks, " + tm + " termini."), false);
        return totalNodes;
    }

    // =========================================================================
    // show_staleness
    // =========================================================================

    private static int showStaleness(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel level   = ctx.getSource().getLevel();
        WorldRoadGraph graph = WorldRoadSavedData.get(level).getGraph();

        List<ParticleEmission> emissions = new ArrayList<>();
        int staleCount = 0, edgesWithStale = 0;

        for (RoadEdge edge : graph.edgesNear(player.blockX(), player.blockZ(), RADIUS_STANDARD)) {
            if (edge.getStaleCells().isEmpty()) continue;
            edgesWithStale++;
            for (long cellKey : edge.getStaleCells()) {
                int cx = AtlasCell.unpackX(cellKey);
                int cz = AtlasCell.unpackZ(cellKey);
                int bx = (cx << AtlasCell.CELL_SHIFT) + AtlasCell.CELL_HALF;
                int bz = (cz << AtlasCell.CELL_SHIFT) + AtlasCell.CELL_HALF;
                int y  = safeGroundHeight(level, bx, bz);
                emissions.add(new ParticleEmission(
                        new BlockPos(bx, y + 1, bz), ParticleTypes.ANGRY_VILLAGER,
                        RoadDebugVisualizer.DEFAULT_EMIT_INTERVAL));
                staleCount++;
            }
        }

        if (!emissions.isEmpty()) {
            RoadDebugVisualizer.INSTANCE.addSession(player.getUUID(), level.getGameTime(), emissions);
        }

        int sc = staleCount, ec = edgesWithStale;
        if (sc == 0) {
            ctx.getSource().sendSuccess(() -> Component.literal("No stale cells."), false);
        } else {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    sc + " stale cells across " + ec + " edges."), false);
        }
        return sc;
    }

    // =========================================================================
    // show_traffic
    // =========================================================================

    private static int showTraffic(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel level   = ctx.getSource().getLevel();
        WorldRoadGraph graph = WorldRoadSavedData.get(level).getGraph();

        List<RoadEdge> edges = graph.edgesNear(player.blockX(), player.blockZ(), RADIUS_STANDARD);
        List<ParticleEmission> emissions = new ArrayList<>();
        for (RoadEdge edge : edges) {
            emissions.addAll(buildEdgeEmissions(edge, trafficParticle(edge.getTrafficCounter()),
                    level, RoadDebugVisualizer.DEFAULT_EMIT_INTERVAL));
        }

        if (!emissions.isEmpty()) {
            RoadDebugVisualizer.INSTANCE.addSession(player.getUUID(), level.getGameTime(), emissions);
        }

        int ec = edges.size();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Showing traffic heatmap for " + ec + " edges."), false);
        return ec;
    }

    // =========================================================================
    // show_maintenance
    // =========================================================================

    private static int showMaintenance(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel level   = ctx.getSource().getLevel();
        WorldRoadGraph graph = WorldRoadSavedData.get(level).getGraph();

        List<RoadEdge> edges = graph.edgesNear(player.blockX(), player.blockZ(), RADIUS_STANDARD);
        List<ParticleEmission> emissions = new ArrayList<>();
        int green = 0, orange = 0, red = 0;

        for (RoadEdge edge : edges) {
            int m = edge.getMaintenance();
            ParticleOptions p;
            if      (m >= 80) { p = ParticleTypes.COMPOSTER;       green++;  }
            else if (m >= 40) { p = ParticleTypes.FLAME;            orange++; }
            else              { p = ParticleTypes.ANGRY_VILLAGER;   red++;    }
            emissions.addAll(buildEdgeEmissions(edge, p, level,
                    RoadDebugVisualizer.DEFAULT_EMIT_INTERVAL));
        }

        if (!emissions.isEmpty()) {
            RoadDebugVisualizer.INSTANCE.addSession(player.getUUID(), level.getGameTime(), emissions);
        }

        int g = green, o = orange, r = red;
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Maintenance: " + g + " green, " + o + " orange, " + r + " red edges."), false);
        return edges.size();
    }

    // =========================================================================
    // show_overgrowth
    // =========================================================================

    private static int showOvergrowth(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel level   = ctx.getSource().getLevel();
        WorldRoadGraph graph = WorldRoadSavedData.get(level).getGraph();

        List<RoadEdge> edges = graph.edgesNear(player.blockX(), player.blockZ(), RADIUS_STANDARD);
        List<ParticleEmission> emissions = new ArrayList<>();

        for (RoadEdge edge : edges) {
            int m = edge.getMaintenance();
            // High maintenance = very sparse particles (barely any overgrowth).
            // Low maintenance = dense particles clumped alongside the edge.
            int emitRate = m >= 80 ? 20 : m >= 40 ? 8 : 2;
            emissions.addAll(buildEdgeEmissions(edge, ParticleTypes.COMPOSTER, level, emitRate));
        }

        if (!emissions.isEmpty()) {
            RoadDebugVisualizer.INSTANCE.addSession(player.getUUID(), level.getGameTime(), emissions);
        }

        int ec = edges.size();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Showing overgrowth preview for " + ec + " edges."), false);
        return ec;
    }

    // =========================================================================
    // seed_great_road
    // =========================================================================

    private static int seedGreatRoad(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerLevel level = ctx.getSource().getLevel();
        int x1 = IntegerArgumentType.getInteger(ctx, "x1");
        int z1 = IntegerArgumentType.getInteger(ctx, "z1");
        int x2 = IntegerArgumentType.getInteger(ctx, "x2");
        int z2 = IntegerArgumentType.getInteger(ctx, "z2");

        int y1 = level.isLoaded(new BlockPos(x1, 0, z1))
                ? level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x1, z1) : 64;
        int y2 = level.isLoaded(new BlockPos(x2, 0, z2))
                ? level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x2, z2) : 64;

        BlockPos from = new BlockPos(x1, y1, z1);
        BlockPos to   = new BlockPos(x2, y2, z2);

        WorldAtlas atlas = WorldAtlas.get(level);
        prefillAtlasCorridor(level, atlas, from, to);

        List<Long> cellPath = AtlasRouteRouter.findRoute(atlas, from, to);
        if (cellPath.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal(
                    "No cell path found between (" + x1 + "," + z1 + ") and (" + x2 + "," + z2 + ")."));
            return 0;
        }

        WorldRoadSavedData roadData = WorldRoadSavedData.get(level);
        WorldRoadGraph     graph    = roadData.getGraph();

        RoadNode nodeA = new RoadNode(UUID.randomUUID(), from,
                RoadNode.NodeType.GREAT_ROAD_ANCHOR, Optional.empty());
        RoadNode nodeB = new RoadNode(UUID.randomUUID(), to,
                RoadNode.NodeType.GREAT_ROAD_ANCHOR, Optional.empty());
        graph.addNode(nodeA);
        graph.addNode(nodeB);

        RoadEdge edge = RoadEdge.create(nodeA.nodeId(), nodeB.nodeId(), cellPath,
                RoadEdge.EdgeTier.GREAT_ROAD,
                new RoadEdge.MeanderProfile(6.0f, 0.02f, from.asLong()));
        graph.addEdge(edge);

        List<String> warnings = GraphInvariantValidator.validate(graph);
        warnings.forEach(w -> System.out.println("[RoadGraph Validator] " + w));
        if (warnings.isEmpty()) {
            System.out.println("[RoadGraph Validator] Graph OK — "
                    + graph.allNodes().size() + " nodes, " + graph.allEdges().size() + " edges.");
        }
        roadData.markDirty();

        String na = nodeA.nodeId().toString().substring(0, 8);
        String nb = nodeB.nodeId().toString().substring(0, 8);
        String eid = edge.getEdgeId().toString().substring(0, 8);
        int cells = cellPath.size();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Seeded great road: nodes " + na + "… ↔ " + nb + "… edge=" + eid
                + "… (" + cells + " cells)"), false);
        return 1;
    }

    // =========================================================================
    // connect_village
    // =========================================================================

    private static int connectVillage(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerLevel level   = ctx.getSource().getLevel();
        VillageSavedData villageData = VillageSavedData.get(level);
        WorldRoadSavedData roadData  = WorldRoadSavedData.get(level);
        WorldRoadGraph     graph     = roadData.getGraph();
        WorldAtlas         atlas     = WorldAtlas.get(level);

        String villageName = StringArgumentType.getString(ctx, "villageName");
        String edgePrefix  = StringArgumentType.getString(ctx, "targetEdgeId").toLowerCase(Locale.ROOT);

        Village village = villageData.getAllVillages().stream()
                .filter(v -> v.getName().toLowerCase(Locale.ROOT).contains(villageName.toLowerCase(Locale.ROOT)))
                .findFirst().orElse(null);
        if (village == null) {
            ctx.getSource().sendFailure(Component.literal("No village matching '" + villageName + "'."));
            return 0;
        }

        List<RoadEdge> edgeMatches = new ArrayList<>();
        for (RoadEdge e : graph.allEdges()) {
            if (e.getEdgeId().toString().startsWith(edgePrefix)) edgeMatches.add(e);
        }
        if (edgeMatches.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("No edge matching prefix '" + edgePrefix + "'."));
            return 0;
        }
        if (edgeMatches.size() > 1) {
            ctx.getSource().sendFailure(Component.literal(
                    "Ambiguous edge prefix '" + edgePrefix + "' (" + edgeMatches.size() + " matches)."));
            return 0;
        }
        RoadEdge targetEdge = edgeMatches.get(0);

        BlockPos anchor = village.getAnchorPos();
        if (anchor == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "Village '" + village.getName() + "' has no anchor position."));
            return 0;
        }

        List<Long> cellPath = targetEdge.getCellPath();
        if (cellPath.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("Target edge has no cell path."));
            return 0;
        }

        // Find nearest cell on edge to village anchor
        int bestIdx = 0;
        double bestDist = Double.MAX_VALUE;
        for (int i = 0; i < cellPath.size(); i++) {
            BlockPos center = AtlasRouteRouter.cellKeyToBlockCenter(cellPath.get(i));
            double dx = center.getX() - anchor.getX();
            double dz = center.getZ() - anchor.getZ();
            double d  = dx * dx + dz * dz;
            if (d < bestDist) { bestDist = d; bestIdx = i; }
        }

        BlockPos junctionRaw = AtlasRouteRouter.cellKeyToBlockCenter(cellPath.get(bestIdx));
        int jy = level.isLoaded(junctionRaw)
                ? level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, junctionRaw.getX(), junctionRaw.getZ())
                : 64;
        BlockPos junctionPos = new BlockPos(junctionRaw.getX(), jy, junctionRaw.getZ());

        // Split edge: remove old edge, create TRUNK_JUNCTION + two half-edges
        graph.removeEdge(targetEdge.getEdgeId());

        RoadNode junctionNode = new RoadNode(UUID.randomUUID(), junctionPos,
                RoadNode.NodeType.TRUNK_JUNCTION, Optional.empty());
        graph.addNode(junctionNode);

        List<Long> halfPathA = new ArrayList<>(cellPath.subList(0, bestIdx + 1));
        List<Long> halfPathB = new ArrayList<>(cellPath.subList(bestIdx, cellPath.size()));

        RoadEdge halfA = RoadEdge.create(targetEdge.getNodeAId(), junctionNode.nodeId(),
                halfPathA, targetEdge.getTier(), targetEdge.getMeanderProfile());
        RoadEdge halfB = RoadEdge.create(junctionNode.nodeId(), targetEdge.getNodeBId(),
                halfPathB, targetEdge.getTier(), targetEdge.getMeanderProfile());
        graph.addEdge(halfA);
        graph.addEdge(halfB);

        // Compute docking geometry for village
        VillageDockingPoint dock = VillageDockingPoint.compute(village, junctionPos, level, villageData);

        // Pre-fill atlas and route the connector
        prefillAtlasCorridor(level, atlas, anchor, junctionPos);
        List<Long> connectorPath = AtlasRouteRouter.findRoute(atlas, anchor, junctionPos);
        if (connectorPath.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal(
                    "Could not find connector route from '" + village.getName() + "' to junction."));
            return 0;
        }

        RoadNode dockNode = new RoadNode(UUID.randomUUID(), dock.dockingAnchor(),
                RoadNode.NodeType.VILLAGE_DOCK, Optional.empty());
        graph.addNode(dockNode);

        RoadEdge connectorEdge = RoadEdge.create(dockNode.nodeId(), junctionNode.nodeId(),
                connectorPath, RoadEdge.EdgeTier.CONNECTOR,
                new RoadEdge.MeanderProfile(4.0f, 0.02f, dock.dockingAnchor().asLong()));
        graph.addEdge(connectorEdge);

        village.setDockNodeId(dockNode.nodeId());
        villageData.setDirty();

        List<String> warnings = GraphInvariantValidator.validate(graph);
        warnings.forEach(w -> System.out.println("[RoadGraph Validator] " + w));
        if (warnings.isEmpty()) {
            System.out.println("[RoadGraph Validator] Graph OK — "
                    + graph.allNodes().size() + " nodes, " + graph.allEdges().size() + " edges.");
        }
        roadData.markDirty();

        String vn  = village.getName();
        String eid = targetEdge.getEdgeId().toString().substring(0, 8);
        String jid = junctionNode.nodeId().toString().substring(0, 8);
        String did = dockNode.nodeId().toString().substring(0, 8);
        String cid = connectorEdge.getEdgeId().toString().substring(0, 8);
        int    cc  = connectorPath.size();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Connected '" + vn + "' to edge " + eid + "…. Junction=" + jid
                + "… dock=" + did + "… connector=" + cid + "… (" + cc + " cells)"), false);
        return 1;
    }

    // =========================================================================
    // dispatch_caravan
    // =========================================================================

    private static int dispatchCaravan(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerLevel level   = ctx.getSource().getLevel();
        VillageSavedData villageData = VillageSavedData.get(level);
        WorldRoadSavedData roadData  = WorldRoadSavedData.get(level);
        WorldRoadGraph     graph     = roadData.getGraph();

        String villageName = StringArgumentType.getString(ctx, "villageName");

        Village village = villageData.getAllVillages().stream()
                .filter(v -> v.getName().toLowerCase(Locale.ROOT).contains(villageName.toLowerCase(Locale.ROOT)))
                .findFirst().orElse(null);
        if (village == null) {
            ctx.getSource().sendFailure(Component.literal("No village matching '" + villageName + "'."));
            return 0;
        }

        Optional<UUID> dockIdOpt = village.getDockNodeId();
        if (dockIdOpt.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal(
                    "Village '" + village.getName() + "' has no dock node. Run connect_village first."));
            return 0;
        }

        System.out.println("[CaravanDispatch] Found village '" + village.getName()
                + "' id=" + village.getId().toString().substring(0, 8)
                + " dockNode=" + dockIdOpt.get().toString().substring(0, 8));

        RoadNode dockNode = graph.getNode(dockIdOpt.get());
        if (dockNode == null) {
            ctx.getSource().sendFailure(Component.literal("Dock node not found in graph."));
            return 0;
        }

        // Find CONNECTOR edge incident to dock node
        RoadEdge connectorEdge = null;
        List<String> incidentInfo = new ArrayList<>();
        for (RoadEdge e : graph.allEdges()) {
            boolean incident = e.getNodeAId().equals(dockNode.nodeId())
                    || e.getNodeBId().equals(dockNode.nodeId());
            if (incident) {
                incidentInfo.add(e.getEdgeId().toString().substring(0, 8) + "[" + e.getTier() + "]");
            }
            if (incident && e.getTier() == RoadEdge.EdgeTier.CONNECTOR) {
                connectorEdge = e;
            }
        }
        System.out.println("[CaravanDispatch] Found incident edges: " + incidentInfo);

        if (connectorEdge == null) {
            ctx.getSource().sendFailure(Component.literal("No CONNECTOR edge found for dock node."));
            return 0;
        }

        UUID junctionNodeId = connectorEdge.getNodeAId().equals(dockNode.nodeId())
                ? connectorEdge.getNodeBId() : connectorEdge.getNodeAId();

        // Find any edge incident to the junction (not the connector itself)
        RoadEdge greatRoadEdge = null;
        for (RoadEdge e : graph.allEdges()) {
            if (e.getEdgeId().equals(connectorEdge.getEdgeId())) continue;
            if (e.getNodeAId().equals(junctionNodeId) || e.getNodeBId().equals(junctionNodeId)) {
                greatRoadEdge = e;
                break;
            }
        }
        if (greatRoadEdge == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "No road edge found at junction. Ensure the great road was seeded."));
            return 0;
        }

        System.out.println("[CaravanDispatch] Selected path: edgeA="
                + connectorEdge.getEdgeId().toString().substring(0, 8)
                + " tier=" + connectorEdge.getTier()
                + " realized=" + connectorEdge.isRealized()
                + " blockPath=" + connectorEdge.getBlockPath().size()
                + " + edgeB=" + greatRoadEdge.getEdgeId().toString().substring(0, 8)
                + " tier=" + greatRoadEdge.getTier()
                + " realized=" + greatRoadEdge.isRealized()
                + " blockPath=" + greatRoadEdge.getBlockPath().size());

        // Force-realize both edges
        if (!connectorEdge.isRealized()) {
            EdgeRealizer.realizeEdge(level, connectorEdge, graph, villageData);
            System.out.println("[CaravanDispatch] Forced realization of edgeA (connector): "
                    + connectorEdge.getBlockPath().size() + " blocks placed");
        }
        if (!greatRoadEdge.isRealized()) {
            EdgeRealizer.realizeEdge(level, greatRoadEdge, graph, villageData);
            System.out.println("[CaravanDispatch] Forced realization of edgeB (road): "
                    + greatRoadEdge.getBlockPath().size() + " blocks placed");
        }

        System.out.println("[CaravanDispatch] Realization status: edgeA.realized="
                + connectorEdge.isRealized() + " blocks=" + connectorEdge.getBlockPath().size()
                + "  edgeB.realized=" + greatRoadEdge.isRealized()
                + " blocks=" + greatRoadEdge.getBlockPath().size());

        if (connectorEdge.getBlockPath().isEmpty() || greatRoadEdge.getBlockPath().isEmpty()) {
            ctx.getSource().sendFailure(Component.literal(
                    "Edge realization produced empty block paths — check logs for detail."));
            return 0;
        }
        roadData.markDirty();

        // Build combined block path: connector + overlap-trim + great-road segment
        List<BlockPos> fullPath = new ArrayList<>(connectorEdge.getBlockPath());
        List<BlockPos> roadPath = greatRoadEdge.getBlockPath();
        int skip = Math.min(3, roadPath.size());
        fullPath.addAll(roadPath.subList(skip, roadPath.size()));

        System.out.println("[CaravanDispatch] Concatenated path: " + fullPath.size()
                + " total blocks after junction overlap trimming (skipped " + skip + " from road start)");

        UUID syntheticRouteId = UUID.randomUUID();
        UUID syntheticDestId  = UUID.randomUUID();
        UUID syntheticPrincipalId = UUID.randomUUID();

        System.out.println("[CaravanDispatch] Creating synthetic caravan:"
                + " routeId=" + syntheticRouteId.toString().substring(0, 8)
                + " villageA=" + village.getId().toString().substring(0, 8)
                + " villageB(synthetic)=" + syntheticDestId.toString().substring(0, 8)
                + " principalId(synthetic)=" + syntheticPrincipalId.toString().substring(0, 8));
        System.out.println("[CaravanDispatch] Calling Caravan.create...");

        Caravan testCaravan = Caravan.create(
                syntheticRouteId,
                village.getId(),
                syntheticDestId,
                syntheticPrincipalId,
                UUID.randomUUID(),   // originMarketId
                List.of(),
                0,
                level.getGameTime());

        System.out.println("[CaravanDispatch] Caravan created: caravanId="
                + testCaravan.getCaravanId().toString().substring(0, 8)
                + " state=" + testCaravan.getState()
                + " principalId=" + syntheticPrincipalId.toString().substring(0, 8));

        System.out.println("[CaravanDispatch] Setting overridePath: " + fullPath.size() + " blocks");
        testCaravan.setOverridePath(fullPath);

        System.out.println("[CaravanDispatch] Adding caravan to CaravanSavedData...");
        CaravanSavedData.get(level).addCaravan(testCaravan);
        System.out.println("[CaravanDispatch] Added caravan to CaravanSavedData. Total caravans now: "
                + CaravanSavedData.get(level).getAllCaravans().size());

        String vn   = village.getName();
        String ceid = connectorEdge.getEdgeId().toString().substring(0, 8);
        String geid = greatRoadEdge.getEdgeId().toString().substring(0, 8);
        int    plen = fullPath.size();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Dispatched test caravan from '" + vn + "': connector=" + ceid
                + "… + road=" + geid + "… → " + plen + " blocks total."), false);
        return 1;
    }

    // =========================================================================
    // caravan_status
    // =========================================================================

    private static int caravanStatus(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ctx.getSource().getPlayerOrException(); // player-only
        ServerLevel level = ctx.getSource().getLevel();
        VillageSavedData villageData = VillageSavedData.get(level);
        List<Caravan> caravans = CaravanSavedData.get(level).getAllCaravans();

        if (caravans.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("No active caravans."), false);
            return 0;
        }

        ctx.getSource().sendSuccess(() -> Component.literal(
                "--- " + caravans.size() + " active caravan(s) ---"), false);

        for (Caravan c : caravans) {
            String shortId = c.getCaravanId().toString().substring(0, 8);
            String origin  = villageData.getVillageById(c.getOriginVillageId())
                    .map(Village::getName)
                    .orElse(c.getOriginVillageId().toString().substring(0, 8) + "…");
            String dest    = villageData.getVillageById(c.getDestVillageId())
                    .map(Village::getName)
                    .orElse(c.getDestVillageId().toString().substring(0, 8) + "…");
            int    pct     = (int)(c.getProgress() * 100.0);
            List<BlockPos> op = c.getOverridePath();
            String opStr;
            String pathEnds = "";
            if (op == null) {
                opStr = "null";
            } else {
                opStr = op.size() + " blks";
                if (!op.isEmpty()) {
                    BlockPos first = op.get(0);
                    BlockPos last  = op.get(op.size() - 1);
                    pathEnds = " first=(" + first.getX() + "," + first.getY() + "," + first.getZ() + ")"
                             + " last=("  + last.getX()  + "," + last.getY()  + "," + last.getZ()  + ")";
                }
            }
            final String line = "[" + shortId + "] " + origin + " → " + dest
                    + "  state=" + c.getState()
                    + " progress=" + pct + "%"
                    + " spawned=" + c.isSpawned()
                    + " overridePath=" + opStr + pathEnds;
            ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        return caravans.size();
    }

    // =========================================================================
    // Atlas pre-fill helper (mirrors TradeRouteManager.prefillAtlasCorridor)
    // =========================================================================

    private static void prefillAtlasCorridor(ServerLevel level, WorldAtlas atlas,
                                              BlockPos from, BlockPos to) {
        int    dx       = to.getX() - from.getX();
        int    dz       = to.getZ() - from.getZ();
        double dist     = Math.sqrt((double) dx * dx + (double) dz * dz);
        int    hopSize  = 800;
        int    hops     = Math.max(1, (int) Math.ceil(dist / hopSize));
        long   perHop   = 50_000_000L / hops;  // 50 ms total budget
        for (int i = 0; i <= hops; i++) {
            float t = (float) i / hops;
            int x = (int)(from.getX() + dx * t);
            int z = (int)(from.getZ() + dz * t);
            atlas.ensureRegionFilled(level, x, z, 256, perHop);
        }
    }

    // =========================================================================
    // Emission builders
    // =========================================================================

    /**
     * Builds a list of particle positions sampling the edge's block path (if
     * realized, every 3rd block) or cell path (if unrealized, every cell center
     * snapped to the surface). Sampling is done once at command time, not per tick.
     */
    private static List<ParticleEmission> buildEdgeEmissions(
            RoadEdge edge, ParticleOptions particle, ServerLevel level, int emitRate) {
        List<ParticleEmission> out = new ArrayList<>();
        if (!edge.getBlockPath().isEmpty()) {
            List<BlockPos> path = edge.getBlockPath();
            for (int i = 0; i < path.size(); i += 3) {
                out.add(new ParticleEmission(path.get(i), particle, emitRate));
            }
        } else {
            for (long cellKey : edge.getCellPath()) {
                int cx = AtlasCell.unpackX(cellKey);
                int cz = AtlasCell.unpackZ(cellKey);
                int bx = (cx << AtlasCell.CELL_SHIFT) + AtlasCell.CELL_HALF;
                int bz = (cz << AtlasCell.CELL_SHIFT) + AtlasCell.CELL_HALF;
                int y  = safeGroundHeight(level, bx, bz);
                out.add(new ParticleEmission(new BlockPos(bx, y + 1, bz), particle, emitRate));
            }
        }
        return out;
    }

    /**
     * Builds a vertical column of particles above a node's stored position.
     * {@code height} determines how many blocks tall the beam is.
     */
    private static List<ParticleEmission> buildNodeBeam(
            RoadNode node, int height, ParticleOptions particle) {
        List<ParticleEmission> out = new ArrayList<>(height);
        BlockPos base = node.position();
        for (int dy = 0; dy < height; dy++) {
            out.add(new ParticleEmission(
                    base.above(dy), particle, RoadDebugVisualizer.DEFAULT_EMIT_INTERVAL));
        }
        return out;
    }

    /**
     * Builds a straight line of particles between two points, spaced ~5 blocks apart.
     * Used to connect midpoints of detected parallel edge pairs.
     */
    private static List<ParticleEmission> buildLine(
            BlockPos a, BlockPos b, ParticleOptions particle) {
        List<ParticleEmission> out = new ArrayList<>();
        double dist  = Math.sqrt(a.distSqr(b));
        int    steps = Math.min(200, Math.max(1, (int)(dist / 5.0)));
        for (int s = 0; s <= steps; s++) {
            double t = (double) s / steps;
            int x = (int)(a.getX() + t * (b.getX() - a.getX()));
            int y = (int)(a.getY() + t * (b.getY() - a.getY()));
            int z = (int)(a.getZ() + t * (b.getZ() - a.getZ()));
            out.add(new ParticleEmission(
                    new BlockPos(x, y, z), particle, RoadDebugVisualizer.DEFAULT_EMIT_INTERVAL));
        }
        return out;
    }

    // =========================================================================
    // Edge sampling for parallel-pair detection
    // =========================================================================

    /**
     * Samples representative points along an edge for parallel-pair detection.
     * Realized edges: every 50th block in the block path.
     * Unrealized edges: every cell-path center (~64-block spacing).
     */
    private static List<BlockPos> sampleEdgeForParallel(RoadEdge edge, ServerLevel level) {
        List<BlockPos> samples = new ArrayList<>();
        if (!edge.getBlockPath().isEmpty()) {
            List<BlockPos> path = edge.getBlockPath();
            for (int i = 0; i < path.size(); i += 50) samples.add(path.get(i));
        } else {
            for (long cellKey : edge.getCellPath()) {
                int cx = AtlasCell.unpackX(cellKey);
                int cz = AtlasCell.unpackZ(cellKey);
                int bx = (cx << AtlasCell.CELL_SHIFT) + AtlasCell.CELL_HALF;
                int bz = (cz << AtlasCell.CELL_SHIFT) + AtlasCell.CELL_HALF;
                samples.add(new BlockPos(bx, safeGroundHeight(level, bx, bz), bz));
            }
        }
        return samples;
    }

    // =========================================================================
    // Particle type selectors
    // =========================================================================

    private static ParticleOptions particleForTier(RoadEdge.EdgeTier tier) {
        return switch (tier) {
            case GREAT_ROAD -> ParticleTypes.END_ROD;
            case TRUNK      -> ParticleTypes.FLAME;
            case CONNECTOR  -> ParticleTypes.COMPOSTER;
            case LOCAL      -> ParticleTypes.SMOKE;
        };
    }

    private static ParticleOptions particleForNodeType(RoadNode.NodeType type) {
        return switch (type) {
            case GREAT_ROAD_ANCHOR, TRUNK_JUNCTION -> ParticleTypes.END_ROD;
            case VILLAGE_DOCK                      -> ParticleTypes.HAPPY_VILLAGER;
            case TOLL_GATE                         -> ParticleTypes.SOUL_FIRE_FLAME;
            case TERMINUS, POI_STUB, WAYSTATION    -> ParticleTypes.SMOKE;
        };
    }

    private static ParticleOptions trafficParticle(long traffic) {
        if (traffic == 0)  return ParticleTypes.SMOKE;
        if (traffic <= 10) return ParticleTypes.COMPOSTER;
        if (traffic <= 50) return ParticleTypes.FLAME;
        return ParticleTypes.LAVA;
    }

    // =========================================================================
    // Ground-height helper
    // =========================================================================

    /**
     * Returns the surface Y at (x, z), or 64 if the chunk is not loaded.
     * The unloaded-chunk guard prevents accidental chunk loading during the
     * emission-list build that happens at command time.
     */
    private static int safeGroundHeight(ServerLevel level, int x, int z) {
        if (!level.isLoaded(new BlockPos(x, 0, z))) return 64;
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
    }
}
