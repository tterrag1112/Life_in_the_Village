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
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Cultures.Culture;
import tterrag1112.life_in_the_village.Cultures.CultureRegistry;
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
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.ReconciliationEngine;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.StructureAvailabilityRegistry;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.UnavailableBuilding;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer4.PhasedPlanner;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code /litv place [<radius>]} — runs V2 Layers 1+2 then the phased
 * planner (replacing the old PlacementSolver), printing the placement
 * summary only. {@code /litv layout} prints the full L1-L4 phase
 * breakdown.
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
        send(src, "[litv-place] running L1+L2+L3 (phased) radius=" + radius
                + " at " + centre.getX() + "," + centre.getZ() + " ...");

        V2FeatureMap fmap = V2FeatureMap.scan(level, centre, radius);
        Culture culture = CultureRegistry.getOrDefault(CultureRegistry.DEFAULT_ID);
        SiteContext siteCtx = SiteAnalyzer.analyze(fmap, culture, seed);
        send(src, "site: tier=" + siteCtx.tier()
                + " inclination=" + siteCtx.inclination()
                + " anchor=(" + siteCtx.anchor().getX() + ","
                + siteCtx.anchor().getY() + "," + siteCtx.anchor().getZ() + ")");

        if (siteCtx.tier() == ViabilityTier.UNVIABLE) {
            send(src, "tier=UNVIABLE — skipping phased planner");
            send(src, "total time: " + (System.currentTimeMillis() - t0) + " ms");
            return 1;
        }

        InclinationProfile profile = InclinationProfile.forInclination(siteCtx.inclination());
        BuildingSelector.SelectionResult sel =
                BuildingSelector.select(siteCtx, fmap, profile);
        List<UnavailableBuilding> unavailable = sel.unavailable();
        if (!unavailable.isEmpty()) {
            send(src, "unavailable (" + unavailable.size() + "): "
                    + summariseUnavailable(unavailable));
        }
        send(src, "selected: " + summariseCounts(sel.selected()));

        ReconciliationEngine.ReconciliationResult recon = ReconciliationEngine.reconcile(
                sel.selected(), siteCtx.tier(), culture.id(),
                StructureAvailabilityRegistry.INSTANCE);
        if (!recon.drops().isEmpty()) {
            send(src, "reconciliation dropped " + recon.drops().size() + ":");
            for (ReconciliationEngine.DropDetail d : recon.drops()) {
                send(src, "  " + d.type().name() + ": " + d.reason());
            }
        }
        List<BuildingType> sorted = DependencyResolver.topoSort(recon.finalSelection(), seed);
        PhasedPlanner.Result phased =
                PhasedPlanner.run(siteCtx, fmap, sorted, unavailable, level);
        PlacementResult result = phased.placement();
        long t1 = System.currentTimeMillis();

        send(src, "placed (" + result.placed().size() + "):");
        for (PlacedBuilding pb : result.placed()) {
            send(src, "  " + pb.type().name() + " at (" + pb.centre().getX()
                    + "," + pb.centre().getY() + "," + pb.centre().getZ() + ")"
                    + " variant=" + pb.variantId()
                    + " fp=" + pb.footprint().width() + "x" + pb.footprint().length()
                    + " facing=" + facingDir(pb)
                    + " on " + segLabel(pb));
        }
        if (!result.dropped().isEmpty()) {
            send(src, "dropped (" + result.dropped().size() + "):");
            for (DroppedBuilding db : result.dropped()) {
                send(src, "  " + db.type().name() + ": " + db.reason()
                        + " (" + db.detail() + ")");
            }
        }
        send(src, "village viable: " + result.villageViable());
        send(src, "total time: " + (t1 - t0) + " ms");
        return 1;
    }

    private static String facingDir(PlacedBuilding pb) {
        var d = pb.frontage().frontDirection();
        if (Math.abs(d.x) > Math.abs(d.z)) return d.x > 0 ? "E" : "W";
        return d.z > 0 ? "S" : "N";
    }

    private static String segLabel(PlacedBuilding pb) {
        return pb.facingRoad() instanceof
                tterrag1112.life_in_the_village.Village.Planning.V2.Layer4.SpineSegment
                ? "spine" : "cross-street";
    }

    private static String summariseUnavailable(List<UnavailableBuilding> unavailable) {
        if (unavailable.isEmpty()) return "(none)";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < unavailable.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(unavailable.get(i).type().name());
        }
        sb.append(" (").append(unavailable.get(0).reason()).append(')');
        return sb.toString();
    }

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

    private static void send(CommandSourceStack src, String msg) {
        src.sendSuccess(() -> Component.literal(msg), false);
    }
}
