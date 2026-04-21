package tterrag1112.life_in_the_village.Commands;

import com.mojang.brigadier.CommandDispatcher;
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
import tterrag1112.life_in_the_village.Networking.WorldRoadSavedData;
import tterrag1112.life_in_the_village.Village.Roads.Debug.RoadDebugVisualizer;
import tterrag1112.life_in_the_village.Village.Roads.Debug.RoadDebugVisualizer.ParticleEmission;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadEdge;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadNode;
import tterrag1112.life_in_the_village.Village.Roads.Graph.WorldRoadGraph;
import tterrag1112.life_in_the_village.World.Atlas.AtlasCell;

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
