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
import tterrag1112.life_in_the_village.Village.Economy.Trade.GraphTradeRouteEstablisher;
import tterrag1112.life_in_the_village.Village.Economy.Trade.TradeRoute;
import tterrag1112.life_in_the_village.Village.Economy.Trade.TradeRouteManager;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.PathMaterial;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.RoadShape;
import tterrag1112.life_in_the_village.Village.Decoration.VillageBiomeStyle;
import tterrag1112.life_in_the_village.Village.Roads.Debug.RoadDebugVisualizer;
import tterrag1112.life_in_the_village.Village.Roads.Debug.RoadDebugVisualizer.ParticleEmission;
import tterrag1112.life_in_the_village.Village.Roads.Docking.VillageDockingPoint;
import tterrag1112.life_in_the_village.Village.Roads.Graph.GraphInvariantValidator;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadEdge;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadNode;
import tterrag1112.life_in_the_village.Village.Roads.Graph.WorldRoadGraph;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.RoadPrimitive;
import tterrag1112.life_in_the_village.Village.Roads.Planning.ConnectorPlanner;
import tterrag1112.life_in_the_village.Village.Roads.Planning.ParallelismDetector;
import tterrag1112.life_in_the_village.Village.Roads.Planning.ParallelismResolver;
import tterrag1112.life_in_the_village.Events.RoadTerrainChangeListener;
import tterrag1112.life_in_the_village.Village.Roads.Decoration.JunctionDecorator;
import tterrag1112.life_in_the_village.Village.Roads.Decoration.MilestoneDecorator;
import tterrag1112.life_in_the_village.Village.Roads.Decoration.RoadOvergrowthDecorator;
import tterrag1112.life_in_the_village.Village.Roads.Realization.EdgeRealizer;
import tterrag1112.life_in_the_village.Village.Village;
import tterrag1112.life_in_the_village.World.Atlas.AtlasCell;
import tterrag1112.life_in_the_village.World.Atlas.WorldAtlas;
import tterrag1112.life_in_the_village.World.SeasonTracker;

import javax.annotation.Nullable;
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
                                .then(Commands.literal("dispatch_test_caravan_between")
                                        .then(Commands.argument("villageA", StringArgumentType.word())
                                        .then(Commands.argument("villageB", StringArgumentType.word())
                                                .executes(RoadGraphDebugCommand::dispatchTestCaravanBetween))))
                                .then(Commands.literal("caravan_status")
                                        .executes(RoadGraphDebugCommand::caravanStatus))
                                .then(Commands.literal("replan_connector")
                                        .then(Commands.argument("villageName", StringArgumentType.word())
                                                .executes(RoadGraphDebugCommand::replanConnector)))
                                .then(Commands.literal("inspect_junction")
                                        .then(Commands.argument("nodeId", StringArgumentType.word())
                                                .executes(RoadGraphDebugCommand::inspectJunction)))
                                .then(Commands.literal("cleanup_scan")
                                        .executes(RoadGraphDebugCommand::cleanupScan)
                                        .then(Commands.argument("radius", IntegerArgumentType.integer(64, 4096))
                                                .executes(RoadGraphDebugCommand::cleanupScanWithRadius)))
                                .then(Commands.literal("cleanup_merge")
                                        .then(Commands.argument("edgeAId", StringArgumentType.word())
                                        .then(Commands.argument("edgeBId", StringArgumentType.word())
                                                .executes(RoadGraphDebugCommand::cleanupMerge))))
                                .then(Commands.literal("invalidate_cell")
                                        .then(Commands.argument("x", IntegerArgumentType.integer())
                                        .then(Commands.argument("z", IntegerArgumentType.integer())
                                                .executes(RoadGraphDebugCommand::invalidateCell))))
                                .then(Commands.literal("material_preview")
                                        .then(Commands.argument("culture", StringArgumentType.word())
                                        .then(Commands.argument("tier", StringArgumentType.word())
                                        .then(Commands.argument("maintenance", IntegerArgumentType.integer(0, 100))
                                                .executes(RoadGraphDebugCommand::materialPreview)
                                                .then(Commands.argument("season", StringArgumentType.word())
                                                        .executes(RoadGraphDebugCommand::materialPreviewWithSeason))))))
                                .then(Commands.literal("force_maintenance")
                                        .then(Commands.argument("edgeId", StringArgumentType.word())
                                        .then(Commands.argument("value", IntegerArgumentType.integer(0, 100))
                                                .executes(RoadGraphDebugCommand::forceMaintenance))))
                                .then(Commands.literal("redecorate_edge")
                                        .then(Commands.argument("edgeId", StringArgumentType.word())
                                                .executes(RoadGraphDebugCommand::redecoratEdge)))
                                .then(Commands.literal("redecorate_node")
                                        .then(Commands.argument("nodeId", StringArgumentType.word())
                                                .executes(RoadGraphDebugCommand::redecoratNode)))
                                .then(Commands.literal("show_decorations")
                                        .executes(RoadGraphDebugCommand::showDecorations))
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
        List<RoadEdge> edges = graph.edgesNear(player.getBlockX(), player.getBlockZ(), RADIUS_STANDARD);
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
        List<Long> hCellPath  = edge.getCellPath();
        List<BlockPos> hBlockPath = edge.getBlockPath();

        String cellEnds = "";
        if (!hCellPath.isEmpty()) {
            BlockPos fc = AtlasRouteRouter.cellKeyToBlockCenter(hCellPath.get(0));
            BlockPos lc = AtlasRouteRouter.cellKeyToBlockCenter(hCellPath.get(hCellPath.size() - 1));
            cellEnds = " firstCell=(" + fc.getX() + ",0," + fc.getZ() + ")"
                     + " lastCell=(" + lc.getX() + ",0," + lc.getZ() + ")";
        }

        String blockInfo = "  blockPath=" + hBlockPath.size() + " blocks";
        if (!hBlockPath.isEmpty()) {
            blockInfo += " first=" + hBlockPath.get(0).toShortString()
                       + " last="  + hBlockPath.get(hBlockPath.size() - 1).toShortString();
        }

        RoadNode nA = graph.getNode(edge.getNodeAId());
        RoadNode nB = graph.getNode(edge.getNodeBId());
        String nodeAStr = "  nodeA: " + edge.getNodeAId().toString().substring(0, 8) + "/"
                + (nA != null ? nA.type() + " @" + nA.position().toShortString() : "MISSING");
        String nodeBStr = "  nodeB: " + edge.getNodeBId().toString().substring(0, 8) + "/"
                + (nB != null ? nB.type() + " @" + nB.position().toShortString() : "MISSING");

        String primitiveInfo = "  primitives=" + edge.getPrimitives().size();
        if (edge.hasPrimitives()) {
            primitiveInfo += " [" + edge.getPrimitives().stream()
                    .map(p -> p.typeKey())
                    .reduce((a, b) -> a + ", " + b).orElse("") + "]";
        } else {
            primitiveInfo += " (not yet derived)";
        }

        final String hLine0 = "Edge " + shortId + ": tier=" + edge.getTier()
                + " realized=" + edge.isRealized()
                + " maintenance=" + edge.getMaintenance()
                + " cells=" + hCellPath.size() + cellEnds;
        final String hLine1 = blockInfo;
        final String hLine2 = nodeAStr;
        final String hLine3 = nodeBStr;
        final String hLine4 = primitiveInfo;
        ctx.getSource().sendSuccess(() -> Component.literal(hLine0), false);
        ctx.getSource().sendSuccess(() -> Component.literal(hLine1), false);
        ctx.getSource().sendSuccess(() -> Component.literal(hLine2), false);
        ctx.getSource().sendSuccess(() -> Component.literal(hLine3), false);
        ctx.getSource().sendSuccess(() -> Component.literal(hLine4), false);
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

        List<RoadEdge> edges = graph.edgesNear(player.getBlockX(), player.getBlockZ(), RADIUS_PARALLEL);

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

        for (RoadEdge edge : graph.edgesNear(player.getBlockX(), player.getBlockZ(), RADIUS_STANDARD)) {
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

        List<RoadEdge> edges = graph.edgesNear(player.getBlockX(), player.getBlockZ(), RADIUS_STANDARD);
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

        List<RoadEdge> edges = graph.edgesNear(player.getBlockX(), player.getBlockZ(), RADIUS_STANDARD);
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

        List<RoadEdge> edges = graph.edgesNear(player.getBlockX(), player.getBlockZ(), RADIUS_STANDARD);
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

        // Split edge using the graph's factored split helper
        WorldRoadGraph.SplitResult splitResult =
                graph.splitEdgeAtCell(targetEdge.getEdgeId(), cellPath.get(bestIdx), junctionPos)
                        .orElse(null);
        if (splitResult == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "Edge split failed (cell not found in path)."));
            return 0;
        }
        RoadNode junctionNode = graph.getNode(splitResult.junctionNodeId());

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
    // dispatch_test_caravan_between
    // =========================================================================

    private static int dispatchTestCaravanBetween(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ctx.getSource().getPlayerOrException();
        ServerLevel level        = ctx.getSource().getLevel();
        VillageSavedData vdata   = VillageSavedData.get(level);
        WorldRoadSavedData rdata = WorldRoadSavedData.get(level);
        WorldRoadGraph graph     = rdata.getGraph();

        String nameA = StringArgumentType.getString(ctx, "villageA");
        String nameB = StringArgumentType.getString(ctx, "villageB");

        Village villageA = vdata.getAllVillages().stream()
                .filter(v -> v.getName().toLowerCase(Locale.ROOT).contains(nameA.toLowerCase(Locale.ROOT)))
                .findFirst().orElse(null);
        Village villageB = vdata.getAllVillages().stream()
                .filter(v -> v.getName().toLowerCase(Locale.ROOT).contains(nameB.toLowerCase(Locale.ROOT)))
                .findFirst().orElse(null);

        if (villageA == null) {
            ctx.getSource().sendFailure(Component.literal("No village matching '" + nameA + "'."));
            return 0;
        }
        if (villageB == null) {
            ctx.getSource().sendFailure(Component.literal("No village matching '" + nameB + "'."));
            return 0;
        }
        if (villageA.getId().equals(villageB.getId())) {
            ctx.getSource().sendFailure(Component.literal("Villages must be different."));
            return 0;
        }

        UUID dockA = villageA.getDockNodeId().orElse(null);
        UUID dockB = villageB.getDockNodeId().orElse(null);
        if (dockA == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "'" + villageA.getName() + "' has no dock node — run connect_village first."));
            return 0;
        }
        if (dockB == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "'" + villageB.getName() + "' has no dock node — run connect_village first."));
            return 0;
        }

        System.out.println("[CaravanDispatch] Dijkstra from " + villageA.getName()
                + " (dock=" + dockA.toString().substring(0, 8) + ") to "
                + villageB.getName() + " (dock=" + dockB.toString().substring(0, 8) + ")");

        Optional<List<UUID>> edgePathOpt = GraphTradeRouteEstablisher.findEdgePath(graph, dockA, dockB);
        if (edgePathOpt.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal(
                    "No graph path found between '" + villageA.getName()
                    + "' and '" + villageB.getName() + "'. Check that their dock nodes are connected."));
            return 0;
        }
        List<UUID> edgeIds = edgePathOpt.get();

        System.out.println("[CaravanDispatch] Found " + edgeIds.size() + " edge path:");
        for (UUID eid : edgeIds) {
            RoadEdge e = graph.getEdge(eid);
            System.out.println("  edge=" + eid.toString().substring(0, 8)
                    + (e != null ? " tier=" + e.getTier() + " realized=" + e.isRealized()
                                         + " blocks=" + e.getBlockPath().size() : " (null)"));
        }

        // Force-realize any unrealized edges on the path
        int realizedNow = 0;
        for (UUID eid : edgeIds) {
            RoadEdge e = graph.getEdge(eid);
            if (e != null && !e.isRealized()) {
                EdgeRealizer.realizeEdge(level, e, graph, vdata);
                realizedNow++;
                System.out.println("[CaravanDispatch] Realized edge " + eid.toString().substring(0, 8)
                        + " → " + e.getBlockPath().size() + " blocks");
            }
        }
        if (realizedNow > 0) rdata.markDirty();

        List<BlockPos> resolvedPath = GraphTradeRouteEstablisher.resolveGraphBlocks(graph, edgeIds, dockA);
        if (resolvedPath.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal(
                    "Path resolved to 0 blocks — edges may still be unrealized after forced realization. Check logs."));
            return 0;
        }

        // Create a real TradeRoute (stored in villageData so TravellingGroupEngine can find it)
        TradeRoute route = TradeRoute.createGraph(
                villageA.getId(), villageB.getId(),
                TradeRoute.RouteType.NEUTRAL, level.getGameTime(),
                edgeIds, dockA);
        vdata.addTradeRoute(route);
        vdata.setDirty();

        // Create caravan pointing to that real route
        UUID syntheticPrincipalId = UUID.randomUUID();
        Caravan testCaravan = Caravan.create(
                route.getRouteId(),
                villageA.getId(),
                villageB.getId(),
                syntheticPrincipalId,
                UUID.randomUUID(),
                List.of(),
                0,
                level.getGameTime());

        CaravanSavedData.get(level).addCaravan(testCaravan);

        System.out.println("[CaravanDispatch] Created route " + route.getRouteId().toString().substring(0, 8)
                + " with " + edgeIds.size() + " edges → " + resolvedPath.size() + " blocks resolved");

        final String va = villageA.getName();
        final String vb = villageB.getName();
        final int edgeCnt = edgeIds.size();
        final int blkCnt  = resolvedPath.size();
        int finalRealizedNow = realizedNow;
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Dispatched test caravan from '" + va + "' to '" + vb + "': "
                + edgeCnt + " edges, " + blkCnt + " blocks resolved."
                + (finalRealizedNow > 0 ? " (realized " + finalRealizedNow + " edges)" : "")), false);
        return 1;
    }

    // =========================================================================
    // replan_connector
    // =========================================================================

    private static int replanConnector(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ctx.getSource().getPlayerOrException(); // player-only
        ServerLevel level        = ctx.getSource().getLevel();
        VillageSavedData vdata   = VillageSavedData.get(level);
        WorldRoadSavedData rdata = WorldRoadSavedData.get(level);
        WorldRoadGraph graph     = rdata.getGraph();

        String name = StringArgumentType.getString(ctx, "villageName");
        Village village = vdata.getAllVillages().stream()
                .filter(v -> v.getName().toLowerCase(Locale.ROOT).contains(name.toLowerCase(Locale.ROOT)))
                .findFirst().orElse(null);
        if (village == null) {
            ctx.getSource().sendFailure(Component.literal("No village matching '" + name + "'."));
            return 0;
        }

        // ── Clean up existing dock + connector ────────────────────────────────
        Optional<UUID> dockOpt = village.getDockNodeId();
        if (dockOpt.isPresent()) {
            UUID dockId = dockOpt.get();
            RoadNode dockNode = graph.getNode(dockId);
            if (dockNode != null) {
                RoadEdge connector = null;
                for (RoadEdge e : graph.allEdges()) {
                    if ((e.getNodeAId().equals(dockId) || e.getNodeBId().equals(dockId))
                            && e.getTier() == RoadEdge.EdgeTier.CONNECTOR) {
                        connector = e;
                        break;
                    }
                }
                UUID junctionSide = null;
                if (connector != null) {
                    junctionSide = connector.getNodeAId().equals(dockId)
                            ? connector.getNodeBId() : connector.getNodeAId();
                    graph.removeEdge(connector.getEdgeId());
                }
                graph.removeNode(dockId);
                if (junctionSide != null) mergeIfStranded(graph, junctionSide);
            }
            village.setDockNodeId(null);
            vdata.setDirty();
        }

        // ── Re-plan ───────────────────────────────────────────────────────────
        ConnectorPlanner.ConnectorPlanResult result = ConnectorPlanner.planConnector(
                level, graph, vdata, village, ConnectorPlanner.DEFAULT_SEARCH_RADIUS);
        System.out.println("[ConnectorPlanner] replan: " + result.planSummary());
        rdata.markDirty();

        final String summary = result.planSummary();
        ctx.getSource().sendSuccess(() -> Component.literal(summary), false);
        return 1;
    }

    /**
     * If the given junction node now has exactly two incident edges of the same tier,
     * merges them into one edge and removes the junction. This keeps the graph
     * clean after a connector is removed.
     */
    private static void mergeIfStranded(WorldRoadGraph graph, UUID nodeId) {
        RoadNode node = graph.getNode(nodeId);
        if (node == null || node.type() != RoadNode.NodeType.TRUNK_JUNCTION) return;

        List<RoadEdge> incident = new ArrayList<>();
        for (RoadEdge e : graph.allEdges()) {
            if (e.getNodeAId().equals(nodeId) || e.getNodeBId().equals(nodeId)) {
                incident.add(e);
            }
        }
        if (incident.size() != 2) return;
        RoadEdge eA = incident.get(0);
        RoadEdge eB = incident.get(1);
        if (eA.getTier() != eB.getTier()) return;

        // Orient: aOther → junction → bOther
        UUID aOther = eA.getNodeAId().equals(nodeId) ? eA.getNodeBId() : eA.getNodeAId();
        UUID bOther = eB.getNodeAId().equals(nodeId) ? eB.getNodeBId() : eB.getNodeAId();

        // Build merged cell path: aOther→junction direction, then junction→bOther direction
        List<Long> pathA = new ArrayList<>(eA.getCellPath());
        if (eA.getNodeAId().equals(nodeId)) Collections.reverse(pathA); // now goes aOther→junction
        List<Long> pathB = new ArrayList<>(eB.getCellPath());
        if (eB.getNodeBId().equals(nodeId)) Collections.reverse(pathB); // now goes junction→bOther

        // Concatenate, deduplicating the shared junction cell
        List<Long> merged = new ArrayList<>(pathA);
        if (!merged.isEmpty() && !pathB.isEmpty()
                && merged.get(merged.size() - 1).equals(pathB.get(0))) {
            merged.addAll(pathB.subList(1, pathB.size()));
        } else {
            merged.addAll(pathB);
        }

        int avgMaint = (eA.getMaintenance() + eB.getMaintenance()) / 2;
        List<UUID> maintainers = new ArrayList<>(eA.getMaintainerVillageIds());
        for (UUID uid : eB.getMaintainerVillageIds()) {
            if (!maintainers.contains(uid)) maintainers.add(uid);
        }

        RoadEdge mergedEdge = RoadEdge.create(aOther, bOther,
                merged, eA.getTier(), eA.getMeanderProfile());
        mergedEdge.setMaintenance(avgMaint);
        mergedEdge.getMaintainerVillageIds().addAll(maintainers);

        graph.removeEdge(eA.getEdgeId());
        graph.removeEdge(eB.getEdgeId());
        graph.removeNode(nodeId);
        graph.addEdge(mergedEdge);
        System.out.println("[replanConnector] Merged stranded junction "
                + nodeId.toString().substring(0, 8));
    }

    // =========================================================================
    // inspect_junction
    // =========================================================================

    private static int inspectJunction(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ctx.getSource().getPlayerOrException();
        ServerLevel level = ctx.getSource().getLevel();
        WorldRoadGraph graph = WorldRoadSavedData.get(level).getGraph();

        String input = StringArgumentType.getString(ctx, "nodeId").toLowerCase(Locale.ROOT);

        List<RoadNode> matches = new ArrayList<>();
        for (RoadNode n : graph.allNodes()) {
            if (n.nodeId().toString().startsWith(input)) matches.add(n);
        }

        if (matches.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal(
                    "No node matches prefix '" + input + "'."));
            return 0;
        }
        if (matches.size() > 1) {
            ctx.getSource().sendFailure(Component.literal(
                    "Ambiguous node prefix '" + input + "' (" + matches.size() + " matches)."));
            return 0;
        }

        RoadNode node = matches.get(0);
        String nodeShortId = node.nodeId().toString().substring(0, 8);
        BlockPos nodePos = node.position();

        ctx.getSource().sendSuccess(() -> Component.literal(
                "--- inspect_junction " + nodeShortId + " [" + node.type() + "] ---"), false);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "  pos=" + nodePos.toShortString()), false);

        // Collect incident edges
        List<RoadEdge> incident = new ArrayList<>();
        for (RoadEdge e : graph.allEdges()) {
            if (e.getNodeAId().equals(node.nodeId()) || e.getNodeBId().equals(node.nodeId())) {
                incident.add(e);
            }
        }

        final String incHeader = "  Incident edges: " + incident.size();
        ctx.getSource().sendSuccess(() -> Component.literal(incHeader), false);

        for (RoadEdge e : incident) {
            String eid = e.getEdgeId().toString().substring(0, 8);
            boolean isNodeA = e.getNodeAId().equals(node.nodeId());
            String role = isNodeA ? "nodeA" : "nodeB";
            List<BlockPos> bp = e.getBlockPath();

            String bpSummary;
            if (bp.isEmpty()) {
                bpSummary = "blocks=0";
            } else {
                bpSummary = "blocks=" + bp.size()
                        + " first=" + bp.get(0).toShortString()
                        + " last="  + bp.get(bp.size() - 1).toShortString();
            }

            final String edgeLine = "  [" + eid + "] tier=" + e.getTier()
                    + " endpoint=" + role
                    + " realized=" + e.isRealized()
                    + " cells=" + e.getCellPath().size()
                    + " " + bpSummary;
            ctx.getSource().sendSuccess(() -> Component.literal(edgeLine), false);

            // Connectivity check: nearest block to this node
            if (!bp.isEmpty()) {
                double minDist = Double.MAX_VALUE;
                BlockPos nearestBlock = bp.get(0);

                // Sample densely enough to catch the true nearest
                int step = Math.max(1, bp.size() / 500);
                for (int i = 0; i < bp.size(); i += step) {
                    double d = Math.sqrt(bp.get(i).distSqr(nodePos));
                    if (d < minDist) { minDist = d; nearestBlock = bp.get(i); }
                }
                // Always check endpoints regardless of step size
                for (BlockPos check : List.of(bp.get(0), bp.get(bp.size() - 1))) {
                    double d = Math.sqrt(check.distSqr(nodePos));
                    if (d < minDist) { minDist = d; nearestBlock = check; }
                }

                final double finalDist = minDist;
                final BlockPos finalNearest = nearestBlock;
                final String distLine = "    nearestBlock=" + finalNearest.toShortString()
                        + " dist=" + String.format("%.1f", finalDist);
                ctx.getSource().sendSuccess(() -> Component.literal(distLine), false);

                if (minDist > 8.0) {
                    final String warn = "    DISCONNECTED: edge " + eid
                            + " does not reach node " + nodeShortId
                            + ". Nearest block at distance "
                            + String.format("%.1f", finalDist) + ".";
                    ctx.getSource().sendSuccess(() -> Component.literal(warn), false);
                }
            }
        }

        return incident.size();
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
            TradeRoute tr = VillageSavedData.get(level).getRouteById(c.getRouteId()).orElse(null);
            String routeInfo = tr == null ? "route=missing"
                    : tr.hasGraphPath() ? "graph(" + tr.getEdgeIds().size() + " edges)"
                    : "legacy(road=" + (tr.getConnectionId() != null ? tr.getConnectionId().toString().substring(0, 8) : "null") + ")";
            final String line = "[" + shortId + "] " + origin + " → " + dest
                    + "  state=" + c.getState()
                    + " progress=" + pct + "%"
                    + " spawned=" + c.isSpawned()
                    + " " + routeInfo;
            ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        return caravans.size();
    }

    // =========================================================================
    // Atlas pre-fill helper — delegates to the now-public TradeRouteManager method
    // =========================================================================

    private static void prefillAtlasCorridor(ServerLevel level, WorldAtlas atlas,
                                              BlockPos from, BlockPos to) {
        TradeRouteManager.prefillAtlasCorridor(level, atlas, from, to);
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
    // cleanup_scan
    // =========================================================================

    private static final int DEFAULT_CLEANUP_RADIUS = 1024;

    private static int cleanupScan(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        return runCleanupScan(ctx, DEFAULT_CLEANUP_RADIUS);
    }

    private static int cleanupScanWithRadius(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        return runCleanupScan(ctx, IntegerArgumentType.getInteger(ctx, "radius"));
    }

    private static int runCleanupScan(CommandContext<CommandSourceStack> ctx, int radius)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel level   = ctx.getSource().getLevel();
        WorldRoadGraph graph = WorldRoadSavedData.get(level).getGraph();

        List<ParallelismDetector.ParallelPair> pairs =
                ParallelismDetector.findParallelPairs(graph, radius, player.blockPosition());

        if (pairs.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "[cleanup_scan] No parallel pairs within " + radius + " blocks."), false);
            return 0;
        }

        ctx.getSource().sendSuccess(() -> Component.literal(
                "[cleanup_scan] Found " + pairs.size() + " parallel pair(s) within "
                        + radius + " blocks (longest overlap first):"), false);

        List<ParticleEmission> emissions = new ArrayList<>();

        for (int i = 0; i < Math.min(pairs.size(), 10); i++) {
            ParallelismDetector.ParallelPair pair = pairs.get(i);
            RoadEdge ea = graph.getEdge(pair.edgeAId());
            RoadEdge eb = graph.getEdge(pair.edgeBId());
            final String line = "  #" + (i + 1) + ": A=" + pair.edgeAId().toString().substring(0, 8)
                    + " (" + (ea != null ? ea.getTier() : "?") + ")"
                    + " B=" + pair.edgeBId().toString().substring(0, 8)
                    + " (" + (eb != null ? eb.getTier() : "?") + ")"
                    + " overlap=" + pair.sustainedLengthBlocks() + " blocks"
                    + " [" + pair.overlapStartIndexA() + ".." + pair.overlapEndIndexA() + "]";
            ctx.getSource().sendSuccess(() -> Component.literal(line), false);

            // Highlight both edges and draw a line between midpoints
            if (ea != null) emissions.addAll(buildEdgeEmissions(ea, ParticleTypes.WITCH, level,
                    RoadDebugVisualizer.DEFAULT_EMIT_INTERVAL * 2));
            if (eb != null) emissions.addAll(buildEdgeEmissions(eb, ParticleTypes.COMPOSTER, level,
                    RoadDebugVisualizer.DEFAULT_EMIT_INTERVAL * 2));
        }

        if (!emissions.isEmpty()) {
            RoadDebugVisualizer.INSTANCE.addSession(player.getUUID(), level.getGameTime(), emissions);
        }
        return pairs.size();
    }

    // =========================================================================
    // cleanup_merge
    // =========================================================================

    private static int cleanupMerge(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerLevel level = ctx.getSource().getLevel();
        WorldRoadSavedData roadData = WorldRoadSavedData.get(level);
        WorldRoadGraph graph = roadData.getGraph();

        String prefixA = StringArgumentType.getString(ctx, "edgeAId").toLowerCase(Locale.ROOT);
        String prefixB = StringArgumentType.getString(ctx, "edgeBId").toLowerCase(Locale.ROOT);

        RoadEdge edgeA = resolveEdgeByPrefix(ctx, graph, prefixA, "edgeAId");
        if (edgeA == null) return 0;
        RoadEdge edgeB = resolveEdgeByPrefix(ctx, graph, prefixB, "edgeBId");
        if (edgeB == null) return 0;

        if (edgeA.getEdgeId().equals(edgeB.getEdgeId())) {
            ctx.getSource().sendFailure(Component.literal("edgeAId and edgeBId must be different edges."));
            return 0;
        }

        Optional<ParallelismDetector.ParallelPair> pairOpt =
                ParallelismDetector.findPairBetween(graph, edgeA.getEdgeId(), edgeB.getEdgeId());

        if (pairOpt.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal(
                    "[cleanup_merge] Edges " + edgeA.getEdgeId().toString().substring(0, 8)
                            + " and " + edgeB.getEdgeId().toString().substring(0, 8)
                            + " are not detected as parallel — no merge performed."));
            return 0;
        }

        ParallelismResolver.ResolveResult result =
                ParallelismResolver.resolvePair(level, graph, pairOpt.get());

        if (!result.success()) {
            ctx.getSource().sendFailure(Component.literal(
                    "[cleanup_merge] Merge failed: " + result.failureReason()));
            return 0;
        }

        roadData.markDirty();

        final String survivorStr = result.survivorEdgeId().toString().substring(0, 8);
        final String removedStr  = result.removedEdgeId().toString().substring(0, 8);
        final int    stubCount   = result.leftoverEdgeIds().size();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[cleanup_merge] Merged: removed=" + removedStr
                        + " survivor=" + survivorStr
                        + " stubs=" + stubCount), false);
        return 1;
    }

    private static RoadEdge resolveEdgeByPrefix(CommandContext<CommandSourceStack> ctx,
                                                WorldRoadGraph graph,
                                                String prefix,
                                                String argName) {
        List<RoadEdge> matches = new ArrayList<>();
        for (RoadEdge e : graph.allEdges()) {
            if (e.getEdgeId().toString().startsWith(prefix)) matches.add(e);
        }
        if (matches.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal(
                    "No edge matches prefix '" + prefix + "' for " + argName + "."));
            return null;
        }
        if (matches.size() > 1) {
            String list = matches.stream().limit(5)
                    .map(e -> e.getEdgeId().toString().substring(0, 8))
                    .reduce((a, b) -> a + ", " + b).orElse("");
            ctx.getSource().sendFailure(Component.literal(
                    "Ambiguous prefix '" + prefix + "' for " + argName
                            + ": " + list + (matches.size() > 5 ? "…" : "")));
            return null;
        }
        return matches.get(0);
    }

    // =========================================================================
    // invalidate_cell
    // =========================================================================

    private static int invalidateCell(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerLevel level = ctx.getSource().getLevel();
        int blockX = IntegerArgumentType.getInteger(ctx, "x");
        int blockZ = IntegerArgumentType.getInteger(ctx, "z");

        int cellX = blockX >> AtlasCell.CELL_SHIFT;
        int cellZ = blockZ >> AtlasCell.CELL_SHIFT;
        long cellKey = AtlasCell.packKey(cellX, cellZ);

        WorldRoadSavedData roadData = WorldRoadSavedData.get(level);
        WorldRoadGraph graph = roadData.getGraph();

        Set<UUID> edgeIds = graph.edgesInCell(cellKey);
        if (edgeIds.isEmpty()) {
            ctx.getSource().sendSuccess(
                    () -> Component.literal("[invalidate_cell] No edges cover cell ("
                            + cellX + "," + cellZ + ")"), false);
            return 0;
        }

        boolean anyDirty = RoadTerrainChangeListener.invalidateCells(
                graph, Set.of(cellKey));
        if (anyDirty) roadData.markDirty();

        int count = (int) edgeIds.stream()
                .map(graph::getEdge)
                .filter(e -> e != null && e.getStaleCells().contains(cellKey))
                .count();

        ctx.getSource().sendSuccess(
                () -> Component.literal("[invalidate_cell] Marked cell ("
                        + cellX + "," + cellZ + ") stale on " + count + " edge(s)"), false);
        return count;
    }

    // =========================================================================
    // material_preview
    // =========================================================================

    private static int materialPreview(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        return runMaterialPreview(ctx, null);
    }

    private static int materialPreviewWithSeason(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        String seasonStr = StringArgumentType.getString(ctx, "season").toUpperCase(Locale.ROOT);
        SeasonTracker.Season season;
        try {
            season = SeasonTracker.Season.valueOf(seasonStr);
        } catch (IllegalArgumentException e) {
            ctx.getSource().sendFailure(Component.literal(
                    "Unknown season '" + seasonStr + "'. Valid: SPRING, SUMMER, AUTUMN, WINTER."));
            return 0;
        }
        return runMaterialPreview(ctx, season);
    }

    private static int runMaterialPreview(CommandContext<CommandSourceStack> ctx,
                                          @Nullable SeasonTracker.Season season)
            throws CommandSyntaxException {
        ctx.getSource().getPlayerOrException();

        String culture     = StringArgumentType.getString(ctx, "culture");
        String tierStr     = StringArgumentType.getString(ctx, "tier").toUpperCase(Locale.ROOT);
        int    maintenance = IntegerArgumentType.getInteger(ctx, "maintenance");

        RoadShape.RoadTier tier;
        try {
            tier = RoadShape.RoadTier.valueOf(tierStr);
        } catch (IllegalArgumentException e) {
            ctx.getSource().sendFailure(Component.literal(
                    "Unknown tier '" + tierStr + "'. Valid: FOOTPATH, VILLAGE_PATH, VILLAGE_ROAD, TOWN_ROAD, CAPITAL_ROAD."));
            return 0;
        }

        PathMaterial mat = PathMaterial.resolve(
                VillageBiomeStyle.PLAINS, culture, maintenance, tier, season);

        String header = "--- material_preview ---"
                + " culture=" + culture
                + " tier=" + tier
                + " maintenance=" + maintenance
                + " season=" + (season != null ? season : "none")
                + " → name=" + mat.getName();
        ctx.getSource().sendSuccess(() -> Component.literal(header), false);

        ctx.getSource().sendSuccess(() -> Component.literal("  Core blocks:"), false);
        for (PathMaterial.WeightedBlock wb : mat.getCoreBlocks()) {
            String key = wb.block().getDescriptionId();
            String line = "    " + key + " weight=" + String.format("%.2f", wb.weight());
            ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        }

        ctx.getSource().sendSuccess(() -> Component.literal("  Edge blocks:"), false);
        for (PathMaterial.WeightedBlock wb : mat.getEdgeBlocks()) {
            String key = wb.block().getDescriptionId();
            String line = "    " + key + " weight=" + String.format("%.2f", wb.weight());
            ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        }

        return 1;
    }

    // =========================================================================
    // force_maintenance
    // =========================================================================

    private static int forceMaintenance(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ctx.getSource().getPlayerOrException();
        ServerLevel level   = ctx.getSource().getLevel();
        WorldRoadSavedData roadData  = WorldRoadSavedData.get(level);
        WorldRoadGraph     graph     = roadData.getGraph();
        VillageSavedData   villageData = VillageSavedData.get(level);

        String edgePrefix = StringArgumentType.getString(ctx, "edgeId").toLowerCase(Locale.ROOT);
        int    value      = IntegerArgumentType.getInteger(ctx, "value");

        RoadEdge edge = resolveEdgeByPrefix(ctx, graph, edgePrefix, "edgeId");
        if (edge == null) return 0;

        int oldMaintenance = edge.getMaintenance();
        edge.setMaintenance(value);

        // Force re-realization so material changes take effect immediately
        edge.unrealize();
        edge.clearStaleness();
        EdgeRealizer.realizeEdge(level, edge, graph, villageData);
        roadData.markDirty();

        String shortId = edge.getEdgeId().toString().substring(0, 8);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[force_maintenance] Edge " + shortId + ": maintenance " + oldMaintenance
                        + " → " + value + ". Re-realized: " + edge.isRealized()
                        + " (" + edge.getBlockPath().size() + " blocks)."), false);
        return 1;
    }

    // =========================================================================
    // redecorate_edge
    // =========================================================================

    private static int redecoratEdge(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ctx.getSource().getPlayerOrException();
        ServerLevel level      = ctx.getSource().getLevel();
        WorldRoadSavedData rData = WorldRoadSavedData.get(level);
        WorldRoadGraph graph   = rData.getGraph();
        VillageSavedData vData = VillageSavedData.get(level);

        String prefix = StringArgumentType.getString(ctx, "edgeId").toLowerCase(Locale.ROOT);
        RoadEdge edge = resolveEdgeByPrefix(ctx, graph, prefix, "edgeId");
        if (edge == null) return 0;

        // Remove previously placed decoration blocks from the world
        int removed = 0;
        for (BlockPos pos : edge.getDecorationPositions()) {
            if (level.isLoaded(pos)) {
                level.removeBlock(pos, false);
                removed++;
            }
        }
        edge.clearDecorationPositions();

        // Re-run decoration passes
        MilestoneDecorator.decorate(level, edge, graph);
        RoadOvergrowthDecorator.decorate(level, edge, graph);
        rData.markDirty();

        String shortId = edge.getEdgeId().toString().substring(0, 8);
        int newCount = edge.getDecorationPositions().size();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[redecorate_edge] Edge " + shortId + ": removed " + removed
                        + " old blocks, placed " + newCount + " new decoration blocks."), false);
        return 1;
    }

    // =========================================================================
    // redecorate_node
    // =========================================================================

    private static int redecoratNode(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ctx.getSource().getPlayerOrException();
        ServerLevel level      = ctx.getSource().getLevel();
        WorldRoadSavedData rData = WorldRoadSavedData.get(level);
        WorldRoadGraph graph   = rData.getGraph();
        VillageSavedData vData = VillageSavedData.get(level);

        String input = StringArgumentType.getString(ctx, "nodeId").toLowerCase(Locale.ROOT);

        List<RoadNode> matches = new ArrayList<>();
        for (RoadNode n : graph.allNodes()) {
            if (n.nodeId().toString().startsWith(input)) matches.add(n);
        }
        if (matches.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("No node matches prefix '" + input + "'."));
            return 0;
        }
        if (matches.size() > 1) {
            ctx.getSource().sendFailure(Component.literal(
                    "Ambiguous node prefix '" + input + "' (" + matches.size() + " matches)."));
            return 0;
        }
        RoadNode node = matches.get(0);

        // Remove previously placed decoration blocks
        int removed = 0;
        for (BlockPos pos : node.getDecorationPositions()) {
            if (level.isLoaded(pos)) {
                level.removeBlock(pos, false);
                removed++;
            }
        }
        node.clearDecorationPositions();

        // Re-decorate
        JunctionDecorator.decorate(level, node, graph, vData);
        rData.markDirty();

        String shortId = node.nodeId().toString().substring(0, 8);
        int newCount = node.getDecorationPositions().size();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[redecorate_node] Node " + shortId + " [" + node.type() + "]: removed "
                        + removed + " old blocks, placed " + newCount + " new blocks."), false);
        return 1;
    }

    // =========================================================================
    // show_decorations
    // =========================================================================

    private static int showDecorations(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel level   = ctx.getSource().getLevel();
        WorldRoadGraph graph = WorldRoadSavedData.get(level).getGraph();

        List<ParticleEmission> emissions = new ArrayList<>();
        int edgeDecorCount = 0, nodeDecorCount = 0;

        for (RoadEdge edge : graph.edgesNear(player.getBlockX(), player.getBlockZ(), RADIUS_STANDARD)) {
            for (BlockPos pos : edge.getDecorationPositions()) {
                if (pos.distSqr(player.blockPosition()) > (long) RADIUS_STANDARD * RADIUS_STANDARD) continue;
                emissions.add(new ParticleEmission(pos.above(), ParticleTypes.HAPPY_VILLAGER,
                        RoadDebugVisualizer.DEFAULT_EMIT_INTERVAL));
                edgeDecorCount++;
            }
        }

        for (RoadNode node : graph.allNodes()) {
            if (node.position().distSqr(player.blockPosition()) > (long) RADIUS_STANDARD * RADIUS_STANDARD) continue;
            for (BlockPos pos : node.getDecorationPositions()) {
                emissions.add(new ParticleEmission(pos.above(), ParticleTypes.END_ROD,
                        RoadDebugVisualizer.DEFAULT_EMIT_INTERVAL));
                nodeDecorCount++;
            }
        }

        if (!emissions.isEmpty()) {
            RoadDebugVisualizer.INSTANCE.addSession(player.getUUID(), level.getGameTime(), emissions);
        }

        int ec = edgeDecorCount, nc = nodeDecorCount;
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[show_decorations] " + ec + " edge decoration blocks, "
                        + nc + " node decoration blocks within " + RADIUS_STANDARD + " blocks."), false);
        return ec + nc;
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
