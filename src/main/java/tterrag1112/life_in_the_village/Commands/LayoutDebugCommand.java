package tterrag1112.life_in_the_village.Commands;

import com.mojang.brigadier.CommandDispatcher;
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
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Planning.Debug.LayoutDebugVisualizer;
import tterrag1112.life_in_the_village.Village.Planning.Debug.LayoutDebugVisualizer.ParticleEmission;
import tterrag1112.life_in_the_village.Village.Planning.Graph.EdgeRole;
import tterrag1112.life_in_the_village.Village.Planning.Graph.NodeKind;
import tterrag1112.life_in_the_village.Village.Planning.Graph.RoadGraph;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.ArrayList;
import java.util.List;

public class LayoutDebugCommand {

    private static final int SHOW_GRAPH_RADIUS = 512;

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("liv")
                .requires(s -> s.hasPermission(2))
                .then(Commands.literal("layout")
                        .then(Commands.literal("debug")
                                .then(Commands.literal("show_graph")
                                        .executes(LayoutDebugCommand::showGraph))
                        )
                )
        );
    }

    private static int showGraph(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel  level  = ctx.getSource().getLevel();
        BlockPos     playerPos = player.blockPosition();

        VillageSavedData data = VillageSavedData.get(level);
        List<Village> nearby = new ArrayList<>();
        for (Village v : data.getAllVillages()) {
            BlockPos centre = v.getVillageCentre();
            if (centre == null) continue;
            double dx = centre.getX() - playerPos.getX();
            double dz = centre.getZ() - playerPos.getZ();
            if (dx * dx + dz * dz <= (double) SHOW_GRAPH_RADIUS * SHOW_GRAPH_RADIUS) {
                nearby.add(v);
            }
        }

        List<ParticleEmission> emissions = new ArrayList<>();
        int totalEdges = 0, totalNodes = 0;

        for (Village v : nearby) {
            RoadGraph graph = v.getDebugRoadGraph();
            if (graph == null) continue;

            for (RoadGraph.Edge edge : graph.allEdges()) {
                emissions.addAll(buildEdgeEmissions(edge,
                        particleForRole(edge.role()),
                        LayoutDebugVisualizer.DEFAULT_EMIT_INTERVAL));
                totalEdges++;
            }
            for (RoadGraph.Node node : graph.allNodes()) {
                int height = beamHeightForKind(node.kind());
                emissions.addAll(buildNodeBeam(node, height, particleForKind(node.kind())));
                totalNodes++;
            }
        }

        LayoutDebugVisualizer.INSTANCE.addSession(player.getUUID(), level.getGameTime(), emissions);

        int vc = nearby.size(), ec = totalEdges, nc = totalNodes;
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Showing " + ec + " edges and " + nc + " nodes across " + vc
                        + " villages within " + SHOW_GRAPH_RADIUS + " blocks for 30 seconds."),
                false);
        return ec + nc;
    }

    private static List<ParticleEmission> buildEdgeEmissions(
            RoadGraph.Edge edge, ParticleOptions particle, int emitEveryNTicks) {
        List<ParticleEmission> out = new ArrayList<>();
        List<BlockPos> cl = edge.centerline();
        for (int i = 0; i < cl.size(); i += 3) {
            out.add(new ParticleEmission(cl.get(i), particle, emitEveryNTicks));
        }
        return out;
    }

    private static List<ParticleEmission> buildNodeBeam(
            RoadGraph.Node node, int height, ParticleOptions particle) {
        List<ParticleEmission> out = new ArrayList<>(height);
        BlockPos base = node.pos();
        for (int dy = 0; dy < height; dy++) {
            out.add(new ParticleEmission(
                    base.above(dy), particle, LayoutDebugVisualizer.DEFAULT_EMIT_INTERVAL));
        }
        return out;
    }

    private static ParticleOptions particleForRole(EdgeRole role) {
        return switch (role) {
            case SPINE -> ParticleTypes.END_ROD;
            case SPUR  -> ParticleTypes.FLAME;
            case RING  -> ParticleTypes.COMPOSTER;
        };
    }

    private static ParticleOptions particleForKind(NodeKind kind) {
        return switch (kind) {
            case JUNCTION, CASTLE_ANCHOR, MANOR_ANCHOR -> ParticleTypes.END_ROD;
            case GATE        -> ParticleTypes.SOUL_FIRE_FLAME;
            case FOCAL       -> ParticleTypes.HAPPY_VILLAGER;
            case TERMINUS    -> ParticleTypes.SMOKE;
            case BRIDGE_HEAD -> ParticleTypes.FALLING_WATER;
            case SHORE_HEAD  -> ParticleTypes.DRIPPING_WATER;
        };
    }

    private static int beamHeightForKind(NodeKind kind) {
        return switch (kind) {
            case CASTLE_ANCHOR, MANOR_ANCHOR -> 12;
            case TERMINUS                    -> 6;
            case BRIDGE_HEAD, SHORE_HEAD     -> 8;
            default                          -> 10;
        };
    }
}
