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
import tterrag1112.life_in_the_village.Village.Planning.Primitives.RoadPrimitive;
import tterrag1112.life_in_the_village.Cultures.Culture;
import tterrag1112.life_in_the_village.Cultures.CultureRegistry;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer1.V2FeatureMap;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.Hub;
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
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer4.Junction;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer4.PhasedPlanner;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer4.RoadNetwork;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer4.Skeleton;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer4.SpineSegment;

import java.util.List;

/**
 * {@code /litv layout [<radius>]} — runs the full V2 stack
 * (L1+L2+L3+L4) and prints a phase-by-phase breakdown including
 * the cardinal-aligned spine path, hub designations, cross-street
 * insertion, reassessment trims, and the final road skeleton.
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
        send(src, "[litv-layout] running L1+L2+L3+L4 (cardinal) radius=" + radius
                + " at " + centre.getX() + "," + centre.getZ() + " ...");

        V2FeatureMap fmap = V2FeatureMap.scan(level, centre, radius);
        Culture culture = CultureRegistry.getOrDefault(CultureRegistry.DEFAULT_ID);
        SiteContext siteCtx = SiteAnalyzer.analyze(fmap, culture, seed, level);
        send(src, "site: tier=" + siteCtx.tier()
                + " inclination=" + siteCtx.inclination()
                + " primaryAxis=" + siteCtx.primaryAxis()
                + " culture=" + culture.id());
        if (!siteCtx.anchor().equals(siteCtx.originalAnchor())) {
            send(src, "anchor: original " + posStr(siteCtx.originalAnchor())
                    + " → adjusted " + posStr(siteCtx.anchor()));
        } else {
            send(src, "anchor: " + posStr(siteCtx.anchor()) + " (no adjustment)");
        }

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
        if (!recon.drops().isEmpty() || !recon.tradeFulfilled().isEmpty()) {
            send(src, "reconciliation: " + recon.drops().size() + " drops, "
                    + recon.tradeFulfilled().size() + " trade-fulfilled");
            for (ReconciliationEngine.DropDetail d : recon.drops()) {
                send(src, "  drop " + d.type().name() + ": " + d.reason());
            }
            for (ReconciliationEngine.TradeFulfillment t : recon.tradeFulfilled()) {
                send(src, "  trade " + t.requiringType().name() + " ← "
                        + t.category() + " ×" + t.amount());
            }
        } else {
            send(src, "reconciliation: stable (no drops, no trade)");
        }
        List<BuildingType> reconciled = recon.finalSelection();

        List<BuildingType> sorted = DependencyResolver.topoSort(reconciled, seed);
        PhasedPlanner.Result phased =
                PhasedPlanner.run(siteCtx, fmap, sorted, unavailable, level);
        long t1 = System.currentTimeMillis();
        PlacementResult placement = phased.placement();
        RoadNetwork network = phased.network();
        Skeleton skeleton = network.skeleton();

        // Phase 2 — routed network edges (A6: SpinePath removed).
        send(src, "phase 2: routed network "
                + skeleton.edges().size() + " edges, "
                + skeleton.primarySegments().size() + " chord segments");
        for (int i = 0; i < skeleton.edges().size(); i++) {
            var e = skeleton.edges().get(i);
            send(src, "  edge-" + (i + 1) + ": " + describePrimitive(e.primitive()));
        }

        // Phase 3 + 4 events.
        int placedFoundation = countByKind(phased.events(),
                PhasedPlanner.PhaseEvent.Kind.PLACED_FOUNDATION);
        int placedIterative = countByKind(phased.events(),
                PhasedPlanner.PhaseEvent.Kind.PLACED_ITERATIVE);
        send(src, "phase 3 foundation placed (" + placedFoundation + ")");

        send(src, "phase 4b placed (" + placedIterative + ")");

        for (PlacedBuilding pb : placement.placed()) {
            send(src, "  " + pb.type().name() + " at " + posStr(pb.centre())
                    + " variant=" + pb.variantId()
                    + " fp=" + pb.footprint().width() + "x" + pb.footprint().length()
                    + " frontage=" + (pb.frontage() != null
                            ? pb.frontage().length() + "x" + pb.frontage().width()
                            : "none")
                    + " facing " + segLabel(pb.facingRoad()));
        }

        send(src, "phase 5 reassess:");
        for (PhasedPlanner.PhaseEvent e : phased.events()) {
            switch (e.kind()) {
                case TRIM, ISOLATED ->
                        send(src, "  " + e.kind().name().toLowerCase() + ": " + e.detail());
                default -> {}
            }
        }

        if (!placement.dropped().isEmpty()) {
            send(src, "dropped (" + placement.dropped().size() + "):");
            for (DroppedBuilding db : placement.dropped()) {
                send(src, "  " + db.type().name() + ": " + db.reason()
                        + " (" + db.detail() + ")");
            }
        }

        send(src, "roads (" + skeleton.primarySegments().size() + " segments + "
                + skeleton.junctions().size() + " junctions):");
        for (int i = 0; i < skeleton.primarySegments().size(); i++) {
            SpineSegment seg = skeleton.primarySegments().get(i);
            send(src, "  seg-" + (i + 1) + ": width=" + seg.width()
                    + " from " + posStr(seg.start()) + " to " + posStr(seg.end()));
        }
        for (Junction j : skeleton.junctions()) {
            send(src, "  junction at " + posStr(j.pos())
                    + " (" + j.segments().size() + " segments)");
        }

        // Hubs.
        if (!siteCtx.hubs().isEmpty()) {
            send(src, "hubs (" + siteCtx.hubs().size() + "):");
            for (int i = 0; i < siteCtx.hubs().size(); i++) {
                Hub h = siteCtx.hubs().get(i);
                String label = i == 0 ? "main" : "secondary";
                send(src, String.format(
                        "  %s: at %s direction=(%.2f,%.2f) openness=%.2f",
                        label, posStr(h.position()),
                        h.direction().x, h.direction().z, h.openness()));
            }
        }

        send(src, "village viable: " + placement.villageViable());
        send(src, "total time: " + (t1 - t0) + " ms");
        return 1;
    }

    // =========================================================================

    private static String describePrimitive(RoadPrimitive prim) {
        if (prim instanceof RoadPrimitive.StraightRoad sr) {
            return "StraightRoad " + posStr(sr.from()) + " → " + posStr(sr.to())
                    + " drift=" + sr.driftAmplitude();
        }
        if (prim instanceof RoadPrimitive.CurvedRoad cr) {
            return "CurvedRoad " + posStr(cr.from()) + " → " + posStr(cr.to())
                    + " curvature=" + String.format("%.2f", cr.curvature())
                    + " drift=" + cr.driftAmplitude();
        }
        if (prim instanceof RoadPrimitive.Arc arc) {
            return "Arc centre=" + posStr(arc.centre()) + " radius=" + arc.radius()
                    + " span=" + String.format("%.2f", Math.toDegrees(arc.arcSpan())) + "°";
        }
        return prim.typeKey();
    }

    private static int countByKind(List<PhasedPlanner.PhaseEvent> events,
                                   PhasedPlanner.PhaseEvent.Kind kind) {
        int n = 0;
        for (PhasedPlanner.PhaseEvent e : events) if (e.kind() == kind) n++;
        return n;
    }

    private static int segLength(BlockPos a, BlockPos b) {
        long dx = a.getX() - b.getX();
        long dz = a.getZ() - b.getZ();
        return (int) Math.round(Math.sqrt(dx * dx + dz * dz));
    }

    private static String segLabel(Object segment) {
        if (segment == null) return "none";   // Stage 3c — facingRoad is nullable
        return segment instanceof SpineSegment ? "spine"
                : segment.getClass().getSimpleName();
    }

    private static String summariseUnavailable(List<UnavailableBuilding> unavailable) {
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
        java.util.Map<BuildingType, Integer> counts = new java.util.LinkedHashMap<>();
        for (BuildingType t : selected) counts.merge(t, 1, Integer::sum);
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (var e : counts.entrySet()) {
            if (!first) sb.append(' ');
            sb.append(e.getKey().name()).append('×').append(e.getValue());
            first = false;
        }
        return sb.toString();
    }

    private static String posStr(BlockPos p) {
        return "(" + p.getX() + "," + p.getY() + "," + p.getZ() + ")";
    }

    private static void send(CommandSourceStack src, String msg) {
        src.sendSuccess(() -> Component.literal(msg), false);
    }
}
