package tterrag1112.life_in_the_village.Commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.RoadPrimitive;
import tterrag1112.life_in_the_village.Village.Planning.V2.Culture.Culture;
import tterrag1112.life_in_the_village.Village.Planning.V2.Culture.CultureRegistry;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer1.V2FeatureMap;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.SiteAnalyzer;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.SiteContext;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.ViabilityTier;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.BuildingSelector;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.DependencyResolver;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.InclinationProfile;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.PlacedBuilding;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.PlacementResult;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.PlacementSolver;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer4.Road;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer4.RoadGraphBuilder;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer4.RoadNetwork;

import java.util.List;
import java.util.Optional;

/**
 * {@code /litv layout [<radius>]} — runs V2 Layers 1+2+3+4 at the
 * player's position and prints the placement summary plus the road
 * network with any overlap warnings.
 */
public final class LayoutCommand {

    private static final int DEFAULT_RADIUS = 100;

    private LayoutCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("litv")
                .then(Commands.literal("layout")
                        .executes(ctx -> run(ctx, DEFAULT_RADIUS))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(8, 512))
                                .executes(ctx -> run(ctx,
                                        IntegerArgumentType.getInteger(ctx, "radius"))))));
    }

    private static int run(CommandContext<CommandSourceStack> ctx, int radius)
            throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer player = src.getPlayerOrException();
        ServerLevel level = src.getLevel();
        BlockPos centre = player.blockPosition();
        long seed = level.getSeed();

        long t0 = System.currentTimeMillis();
        send(src, "[litv-layout] running L1+L2+L3+L4 radius=" + radius
                + " at " + centre.getX() + "," + centre.getZ() + " ...");

        // Layer 1 + 2.
        V2FeatureMap fmap = V2FeatureMap.scan(level, centre, radius);
        Culture culture = CultureRegistry.INSTANCE.getDefault();
        SiteContext siteCtx = SiteAnalyzer.analyze(fmap, culture, seed);
        send(src, "site: tier=" + siteCtx.tier()
                + " inclination=" + siteCtx.inclination()
                + " anchor=" + posStr(siteCtx.anchor())
                + " spine=" + spineStr(siteCtx.primarySpineDirection())
                + " culture=" + culture.id());

        if (siteCtx.tier() == ViabilityTier.UNVIABLE) {
            send(src, "tier=UNVIABLE — skipping Layer 3 + 4");
            send(src, "total time: " + (System.currentTimeMillis() - t0) + " ms");
            return 1;
        }

        // Layer 3.
        InclinationProfile profile = InclinationProfile.forInclination(siteCtx.inclination());
        BuildingSelector.SelectionResult selResult =
                BuildingSelector.select(siteCtx, fmap, profile);
        List<BuildingType> sorted = DependencyResolver.topoSort(selResult.selected());
        PlacementResult placement = PlacementSolver.solve(siteCtx, fmap, sorted,
                selResult.unavailable(), level);
        send(src, "placement: placed=" + placement.placed().size()
                + " dropped=" + placement.dropped().size()
                + " unavailable=" + placement.unavailable().size()
                + " viable=" + placement.villageViable());

        // Layer 4.
        int villageRadius = PlacementSolver.villageRadiusFor(siteCtx.tier());
        RoadGraphBuilder.BuildResult buildResult =
                RoadGraphBuilder.build(siteCtx, placement, villageRadius);
        RoadNetwork network = buildResult.network();
        long t1 = System.currentTimeMillis();

        int spineCount = 1;
        int connectorCount = network.connectors().size();
        int total = spineCount + connectorCount + (network.ring().isPresent() ? 1 : 0);
        send(src, "roads (" + total + " total: 1 spine, " + connectorCount
                + " connectors, ring? " + (network.ring().isPresent() ? "yes" : "no") + "):");
        send(src, "  spine: " + describe(network.spine()));
        for (Road c : network.connectors()) {
            send(src, "  connector: " + describe(c));
        }
        network.ring().ifPresent(r -> send(src, "  ring: " + describe(r)));

        if (!buildResult.warnings().isEmpty()) {
            send(src, "overlap warnings:");
            for (String w : buildResult.warnings()) send(src, "  " + w);
        }

        send(src, "total time: " + (t1 - t0) + " ms");
        return 1;
    }

    // =========================================================================

    private static String describe(Road road) {
        StringBuilder sb = new StringBuilder();
        sb.append(road.tier().name()).append(' ');
        RoadPrimitive p = road.primitive();
        if (p instanceof RoadPrimitive.StraightRoad sr) {
            sb.append("StraightRoad ").append(posStr(sr.from()))
                    .append(" → ").append(posStr(sr.to()))
                    .append(" length=").append(distXZ(sr.from(), sr.to()));
        } else if (p instanceof RoadPrimitive.CurvedRoad cr) {
            sb.append("CurvedRoad ").append(posStr(cr.from()))
                    .append(" → ").append(posStr(cr.to()))
                    .append(" curvature=").append(String.format("%.2f", cr.curvature()))
                    .append(" length=").append(distXZ(cr.from(), cr.to()));
        } else if (p instanceof RoadPrimitive.Ring ring) {
            sb.append("Ring centre=").append(posStr(ring.centre()))
                    .append(" radius=").append(ring.radius());
        } else {
            sb.append(p.typeKey());
        }
        sb.append(" material=").append(road.material());
        return sb.toString();
    }

    private static int distXZ(BlockPos a, BlockPos b) {
        long dx = a.getX() - b.getX();
        long dz = a.getZ() - b.getZ();
        return (int) Math.round(Math.sqrt(dx * dx + dz * dz));
    }

    private static String posStr(BlockPos p) {
        return "(" + p.getX() + "," + p.getY() + "," + p.getZ() + ")";
    }

    private static String spineStr(Vec3 v) {
        return String.format("(%.2f, %.2f)", v.x, v.z);
    }

    private static void send(CommandSourceStack src, String msg) {
        src.sendSuccess(() -> Component.literal(msg), false);
    }
}
