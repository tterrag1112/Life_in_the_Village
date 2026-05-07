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
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.UnavailableBuilding;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer4.CrossStreet;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer4.Junction;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer4.PhasedPlanner;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer4.RoadNetwork;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer4.Skeleton;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer4.Spine;

import java.util.List;

/**
 * {@code /litv layout [<radius>]} — runs the full V2 stack
 * (L1+L2+L3+L4) and prints a phase-by-phase breakdown of placement,
 * cross-street insertion, reassessment trims, and the final road
 * skeleton with junction marks.
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
        send(src, "[litv-layout] running L1+L2+L3+L4 (phased) radius=" + radius
                + " at " + centre.getX() + "," + centre.getZ() + " ...");

        V2FeatureMap fmap = V2FeatureMap.scan(level, centre, radius);
        Culture culture = CultureRegistry.INSTANCE.getDefault();
        SiteContext siteCtx = SiteAnalyzer.analyze(fmap, culture, seed);
        send(src, "site: tier=" + siteCtx.tier()
                + " inclination=" + siteCtx.inclination()
                + " anchor=" + posStr(siteCtx.anchor())
                + " spineDir=" + spineDirStr(siteCtx.primarySpineDirection())
                + " culture=" + culture.id());

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

        List<BuildingType> sorted = DependencyResolver.topoSort(sel.selected());
        PhasedPlanner.Result phased =
                PhasedPlanner.run(siteCtx, fmap, sorted, unavailable, level);
        long t1 = System.currentTimeMillis();
        PlacementResult placement = phased.placement();
        RoadNetwork network = phased.network();
        Skeleton skeleton = network.skeleton();

        // Phase 2 — spine.
        Spine spine = skeleton.spine();
        send(src, "phase 2: spine length=" + segLength(spine.start(), spine.end())
                + " from " + posStr(spine.start()) + " to " + posStr(spine.end()));

        // Phase 3 + 4 events from the planner's diagnostics.
        int placedFoundation = countByKind(phased.events(),
                PhasedPlanner.PhaseEvent.Kind.PLACED_FOUNDATION);
        int placedIterative = countByKind(phased.events(),
                PhasedPlanner.PhaseEvent.Kind.PLACED_ITERATIVE);
        send(src, "phase 3 foundation placed (" + placedFoundation + ")");

        // Phase 4a — capacity-driven cross-street planning.
        // The CAPACITY_PLAN event carries the multi-line math summary;
        // PROACTIVE_CROSS_STREET / PROACTIVE_SKIPPED follow.
        int proactiveInserted = countByKind(phased.events(),
                PhasedPlanner.PhaseEvent.Kind.PROACTIVE_CROSS_STREET);
        int proactiveSkipped = countByKind(phased.events(),
                PhasedPlanner.PhaseEvent.Kind.PROACTIVE_SKIPPED);
        boolean hasCapacityPlan = phased.events().stream().anyMatch(
                e -> e.kind() == PhasedPlanner.PhaseEvent.Kind.CAPACITY_PLAN);
        if (hasCapacityPlan || proactiveInserted + proactiveSkipped > 0) {
            send(src, "phase 4a cross-street planning:");
            for (PhasedPlanner.PhaseEvent e : phased.events()) {
                switch (e.kind()) {
                    case CAPACITY_PLAN -> {
                        for (String line : e.detail().split("\n")) {
                            send(src, "  " + line);
                        }
                    }
                    case PROACTIVE_CROSS_STREET -> send(src, "  inserted: " + e.detail());
                    case PROACTIVE_SKIPPED -> send(src, "  skipped: " + e.detail());
                    default -> {}
                }
            }
        }

        send(src, "phase 4b placed (" + placedIterative + ")");

        // Per-placement detail.
        for (PlacedBuilding pb : placement.placed()) {
            send(src, "  " + pb.type().name() + " at " + posStr(pb.centre())
                    + " variant=" + pb.variantId()
                    + " fp=" + pb.footprint().width() + "x" + pb.footprint().length()
                    + " frontage=" + pb.frontage().length() + "x" + pb.frontage().width()
                    + " facing " + segLabel(pb.facingRoad()));
        }

        // Phase 5 events.
        send(src, "phase 5 reassess:");
        for (PhasedPlanner.PhaseEvent e : phased.events()) {
            switch (e.kind()) {
                case TRIM, REMOVED_CROSS_STREET, ISOLATED ->
                        send(src, "  " + e.kind().name().toLowerCase() + ": " + e.detail());
                default -> {}
            }
        }

        // Drops.
        if (!placement.dropped().isEmpty()) {
            send(src, "dropped (" + placement.dropped().size() + "):");
            for (DroppedBuilding db : placement.dropped()) {
                send(src, "  " + db.type().name() + ": " + db.reason()
                        + " (" + db.detail() + ")");
            }
        }

        // Final skeleton.
        int totalSegments = 1 + skeleton.crossStreets().size();
        send(src, "roads (" + totalSegments + " segments + "
                + skeleton.junctions().size() + " junctions):");
        send(src, "  spine: width=" + spine.width() + " length="
                + segLength(spine.start(), spine.end())
                + " from " + posStr(spine.start()) + " to " + posStr(spine.end()));
        for (int i = 0; i < skeleton.crossStreets().size(); i++) {
            CrossStreet cs = skeleton.crossStreets().get(i);
            send(src, "  cross-street-" + (i + 1) + ": width=" + cs.width()
                    + " length=" + segLength(cs.start(), cs.end())
                    + " junction=" + posStr(cs.spineJunction()));
        }
        for (Junction j : skeleton.junctions()) {
            send(src, "  junction at " + posStr(j.pos())
                    + " (" + j.segments().size() + " segments)");
        }

        send(src, "village viable: " + placement.villageViable());
        send(src, "total time: " + (t1 - t0) + " ms");
        return 1;
    }

    // =========================================================================

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
        return segment instanceof Spine ? "spine"
                : segment instanceof CrossStreet ? "cross-street"
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

    private static String spineDirStr(Vec3 v) {
        return String.format("(%.2f, %.2f)", v.x, v.z);
    }

    private static void send(CommandSourceStack src, String msg) {
        src.sendSuccess(() -> Component.literal(msg), false);
    }
}
