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
import tterrag1112.life_in_the_village.Village.Planning.V2.Culture.Culture;
import tterrag1112.life_in_the_village.Village.Planning.V2.Culture.CultureRegistry;
import tterrag1112.life_in_the_village.Village.Planning.V2.Inclination;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer1.V2FeatureMap;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.SiteAnalyzer;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.SiteContext;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.ViabilityTier;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.BuildingSelector;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.DependencyResolver;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.DroppedBuilding;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.InclinationProfile;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.PlacedBuilding;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.PlacementResult;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.PlacementSolver;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code /litv place [<radius>]} — runs V2 Layers 1 + 2 + 3 at the
 * player's position and prints the resulting {@link PlacementResult}
 * with per-placement score breakdowns.
 */
public final class PlaceCommand {

    private static final int DEFAULT_RADIUS = 100;

    private PlaceCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("litv")
                .then(Commands.literal("place")
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
        send(src, "[litv-place] running L1+L2+L3 radius=" + radius
                + " at " + centre.getX() + "," + centre.getZ() + " ...");

        // Layer 1.
        V2FeatureMap fmap = V2FeatureMap.scan(level, centre, radius);
        send(src, "featuremap: cells=" + (fmap.gridSize() * fmap.gridSize())
                + " scanTime=" + fmap.scanTimeMs() + "ms");

        // Layer 2.
        Culture culture = CultureRegistry.INSTANCE.getDefault();
        SiteContext siteCtx = SiteAnalyzer.analyze(fmap, culture, seed);
        send(src, "site: tier=" + siteCtx.tier()
                + " inclination=" + siteCtx.inclination()
                + " anchor=" + posStr(siteCtx.anchor())
                + " spine=" + spineStr(siteCtx.primarySpineDirection())
                + " culture=" + culture.id());

        if (siteCtx.tier() == ViabilityTier.UNVIABLE) {
            send(src, "tier=UNVIABLE — skipping Layer 3");
            send(src, "total time: " + (System.currentTimeMillis() - t0) + " ms");
            return 1;
        }

        // Layer 3.
        InclinationProfile profile = InclinationProfile.forInclination(siteCtx.inclination());
        List<BuildingType> selected = BuildingSelector.select(siteCtx, fmap, profile);
        send(src, "selected: " + summariseCounts(selected));

        List<BuildingType> sorted = DependencyResolver.topoSort(selected);
        send(src, "topological order: " + summariseDistinctOrder(sorted));

        PlacementSolver.SolveResult solveResult =
                PlacementSolver.solveWithDiagnostics(siteCtx, fmap, sorted, level);
        PlacementResult result = solveResult.result();
        long t1 = System.currentTimeMillis();

        send(src, "placed (" + result.placed().size() + "):");
        for (int i = 0; i < result.placed().size(); i++) {
            PlacedBuilding pb = result.placed().get(i);
            PlacementSolver.PlacedDiagnostic d = solveResult.diagnostics().get(i);
            send(src, String.format("  %s at %s variant=%s fp=%dx%d score=%.2f"
                            + " (terrain=%.2f adj=%.2f cent=%.2f)",
                    pb.type().name(), posStr(pb.centre()), pb.variantId(),
                    pb.footprint().width(), pb.footprint().length(),
                    d.score().total(),
                    d.score().terrain(), d.score().adjacency(), d.score().centrality()));
        }

        send(src, "dropped (" + result.dropped().size() + "):");
        for (DroppedBuilding db : result.dropped()) {
            send(src, "  " + db.type().name() + ": " + db.reason()
                    + " (" + db.detail() + ")");
        }

        send(src, "village viable: " + result.villageViable());
        send(src, "total time: " + (t1 - t0) + " ms");
        return 1;
    }

    // =========================================================================

    private static String summariseCounts(List<BuildingType> selected) {
        if (selected.isEmpty()) return "(none)";
        Map<BuildingType, Integer> counts = new LinkedHashMap<>();
        for (BuildingType t : selected) counts.merge(t, 1, Integer::sum);
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<BuildingType, Integer> e : counts.entrySet()) {
            if (!first) sb.append(' ');
            sb.append(e.getKey().name()).append('×').append(e.getValue());
            first = false;
        }
        return sb.toString();
    }

    /** Single-pass walk that emits each distinct type the first time
     *  it appears in the topo-sorted list. Reflects the
     *  resolver's distinct-type order; multiplicity is in the
     *  earlier "selected" line. */
    private static String summariseDistinctOrder(List<BuildingType> sorted) {
        if (sorted.isEmpty()) return "(none)";
        java.util.LinkedHashSet<BuildingType> seen = new java.util.LinkedHashSet<>(sorted);
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (BuildingType t : seen) {
            if (!first) sb.append(" → ");
            sb.append(t.name());
            first = false;
        }
        return sb.toString();
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
