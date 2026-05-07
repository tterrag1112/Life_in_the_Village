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
import tterrag1112.life_in_the_village.Village.Planning.V2.Culture.Culture;
import tterrag1112.life_in_the_village.Village.Planning.V2.Culture.CultureRegistry;
import tterrag1112.life_in_the_village.Village.Planning.V2.Inclination;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer1.V2FeatureMap;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.SiteAnalyzer;

/**
 * {@code /litv site [<radius>]} — runs V2 Layer 1 + Layer 2 at the
 * player's position and prints the resulting {@code SiteContext}
 * with "chosen by" annotations on each decision.
 */
public final class SiteCommand {

    private static final int DEFAULT_RADIUS = 100;

    private SiteCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("litv")
                .then(Commands.literal("site")
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
        send(src, "[litv-site] analyzing radius=" + radius
                + " at " + centre.getX() + "," + centre.getZ() + " ...");

        V2FeatureMap fmap = V2FeatureMap.scan(level, centre, radius);
        Culture culture = CultureRegistry.INSTANCE.getDefault();
        SiteAnalyzer.Result result = SiteAnalyzer.analyzeWithDiagnostics(fmap, culture, seed);
        long t1 = System.currentTimeMillis();

        // Featuremap one-liner so the surveyor sees scan context.
        send(src, "featuremap: cells=" + (fmap.gridSize() * fmap.gridSize())
                + " scanTime=" + fmap.scanTimeMs() + "ms"
                + " culture=" + culture.id());

        // Tier
        SiteAnalyzer.TierDecision td = result.diagnostics().tier();
        send(src, "viability: " + td.tier().name()
                + " (flatBlocks=" + td.flatBlocks()
                + ", slopeFraction=" + String.format("%.3f", td.slopeFraction()) + ")");

        // Inclination scores
        SiteAnalyzer.InclinationDecision id = result.diagnostics().inclination();
        send(src, "inclination scores (terrain × culture):");
        for (Inclination inc : Inclination.values()) {
            int raw = id.rawScores().get(inc);
            double weighted = id.weightedScores().get(inc);
            double bias = culture.biasFor(inc);
            send(src, String.format("  %-13s %3d × %.2f = %.2f",
                    inc.name() + ":", raw, bias, weighted));
        }
        send(src, "inclination chosen: " + id.inclination().name()
                + " (" + id.reason() + ")");

        // Anchor (initial)
        SiteAnalyzer.AnchorDecision ad = result.diagnostics().anchor();
        BlockPos anchor = ad.anchor();
        send(src, "anchor: " + anchor.getX() + "," + anchor.getY() + "," + anchor.getZ()
                + " (chosen by: " + ad.reason() + ")");

        // Anchor adjustment
        SiteAnalyzer.AnchorAdjustment adj = result.diagnostics().adjustment();
        if (adj.adjusted() != null) {
            BlockPos a2 = adj.adjusted();
            send(src, "anchor adjusted: " + a2.getX() + "," + a2.getY() + "," + a2.getZ()
                    + " (chosen by: " + adj.reason() + ")");
        } else {
            send(src, "anchor adjusted: (none) (" + adj.reason() + ")");
        }

        // Primary axis
        SiteAnalyzer.AxisDecision axd = result.diagnostics().axis();
        send(src, "primary axis: " + axd.axis()
                + " (chosen by: " + axd.reason() + ")");

        // Spine path summary
        send(src, "spine path: " + result.context().spinePath().segments().size()
                + " segments, totalLength=" + result.context().spinePath().totalLength());

        send(src, "total time: " + (t1 - t0) + " ms");
        return 1;
    }

    private static void send(CommandSourceStack src, String msg) {
        src.sendSuccess(() -> Component.literal(msg), false);
    }
}
