package tterrag1112.life_in_the_village.Commands;

import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Cultures.Culture;
import tterrag1112.life_in_the_village.Cultures.CultureRegistry;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Planning.V2.Debug.AutoDumpConfig;
import tterrag1112.life_in_the_village.Village.Planning.V2.Debug.LayoutDumpSerializer;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer1.V2FeatureMap;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.Anchor;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.SiteAnalyzer;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.SiteContext;
import tterrag1112.life_in_the_village.Village.Village;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Track E1 — on-demand V2 layout dump command. Track E1 follow-up
 * added the {@code autodump} runtime toggle. Track E1 anchor
 * detection (this prompt) adds the {@code anchors} subcommand
 * + an INFO-level anchor summary on every dump.
 *
 * <p>Subcommands under {@code /litv layout debug}:
 * <ul>
 *   <li>{@code dump <villageName>} — re-runs V2 Layers 1-4
 *       against the named village's anchor; writes JSON.</li>
 *   <li>{@code dump_at [radius]} — runs V2 Layers 1-4 at the
 *       calling player's position; writes JSON.</li>
 *   <li>{@code autodump <on|off|status>} — runtime toggle for
 *       {@link AutoDumpConfig}.</li>
 *   <li>{@code anchors <villageName>} — runs the V2 site analyzer
 *       at the named village's anchor and prints the anchor list
 *       to chat. No JSON output.</li>
 * </ul>
 *
 * <p>Heavy lifting lives in {@link LayoutDumpSerializer}; this
 * class only parses args + invokes the serializer + replies.
 */
public final class LayoutDumpCommand {

    private static final Logger LOGGER = LogUtils.getLogger();

    public LayoutDumpCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("litv")
                .then(Commands.literal("layout")
                        .then(Commands.literal("debug")
                                .then(Commands.literal("dump")
                                        .then(Commands.argument("villageName",
                                                        StringArgumentType.string())
                                                .executes(LayoutDumpCommand::dumpVillage)))
                                .then(Commands.literal("dump_at")
                                        .executes(ctx -> dumpAt(ctx,
                                                LayoutDumpSerializer.DEFAULT_DUMP_AT_RADIUS))
                                        .then(Commands.argument("radius",
                                                        IntegerArgumentType.integer(8, 512))
                                                .executes(ctx -> dumpAt(ctx,
                                                        IntegerArgumentType.getInteger(ctx, "radius")))))
                                .then(Commands.literal("autodump")
                                        .then(Commands.argument("mode",
                                                        StringArgumentType.word())
                                                .executes(LayoutDumpCommand::autodumpToggle)))
                                .then(Commands.literal("anchors")
                                        .then(Commands.argument("villageName",
                                                        StringArgumentType.string())
                                                .executes(LayoutDumpCommand::anchorsCommand))))));
    }

    // =========================================================================
    // Handlers
    // =========================================================================

    private static int dumpVillage(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        String villageName = StringArgumentType.getString(ctx, "villageName");

        VillageSavedData data = VillageSavedData.get(level);
        Village village = data.getVillageByName(villageName).orElse(null);
        if (village == null) {
            src.sendFailure(Component.literal(
                    "No village named '" + villageName + "'."));
            return 0;
        }

        BlockPos origin = village.getAnchorPos();
        long tick = level.getGameTime();
        JsonObject root = LayoutDumpSerializer.runPlanAndSerialize(
                level, origin, LayoutDumpSerializer.FEATURE_MAP_RADIUS,
                "dump", villageName,
                village.getId() != null ? village.getId().toString() : null,
                tick);

        return writeAndReply(src, villageName, tick, root);
    }

    private static int dumpAt(CommandContext<CommandSourceStack> ctx, int radius)
            throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer player = src.getPlayerOrException();
        ServerLevel level = src.getLevel();
        BlockPos origin = player.blockPosition();
        long tick = level.getGameTime();

        String slug = "dump_at_" + origin.getX() + "_" + origin.getZ();
        JsonObject root = LayoutDumpSerializer.runPlanAndSerialize(
                level, origin, radius, "dump_at", null, null, tick);

        return writeAndReply(src, slug, tick, root);
    }

    private static int autodumpToggle(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        String mode = StringArgumentType.getString(ctx, "mode").toLowerCase(java.util.Locale.ROOT);
        switch (mode) {
            case "on", "true", "enable" -> {
                AutoDumpConfig.setEnabled(true);
                src.sendSuccess(() -> Component.literal(
                        "Auto-dump on spawn: ENABLED"), false);
            }
            case "off", "false", "disable" -> {
                AutoDumpConfig.setEnabled(false);
                src.sendSuccess(() -> Component.literal(
                        "Auto-dump on spawn: DISABLED"), false);
            }
            case "status" -> src.sendSuccess(() -> Component.literal(
                    "Auto-dump on spawn: "
                            + (AutoDumpConfig.isEnabled() ? "ENABLED" : "DISABLED")
                            + " (JVM property "
                            + AutoDumpConfig.SYSTEM_PROPERTY + ")"), false);
            default -> {
                src.sendFailure(Component.literal(
                        "Unknown mode '" + mode + "'. Use on / off / status."));
                return 0;
            }
        }
        return 1;
    }

    /**
     * Track E1 anchor detection — chat-only anchor inspector.
     * Re-runs V2 Layers 1-2 at the village's anchor and prints
     * a concise list. No JSON output.
     */
    private static int anchorsCommand(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        String villageName = StringArgumentType.getString(ctx, "villageName");
        VillageSavedData data = VillageSavedData.get(level);
        Village village = data.getVillageByName(villageName).orElse(null);
        if (village == null) {
            src.sendFailure(Component.literal(
                    "No village named '" + villageName + "'."));
            return 0;
        }
        BlockPos origin = village.getAnchorPos();
        V2FeatureMap fmap = V2FeatureMap.scan(level, origin,
                LayoutDumpSerializer.FEATURE_MAP_RADIUS);
        Culture culture = CultureRegistry.getOrDefault(CultureRegistry.DEFAULT_ID);
        long seed = level.getSeed() ^ ((long) origin.hashCode() * 31L
                + villageName.hashCode());
        SiteContext siteCtx = SiteAnalyzer.analyze(fmap, culture, seed, level);
        List<Anchor> anchors = siteCtx.anchors();
        String summary = LayoutDumpSerializer.anchorSummary(anchors);
        src.sendSuccess(() -> Component.literal(summary), false);
        for (Anchor a : anchors) {
            String line = String.format("  %s %s q=%.2f @(%d,%d,%d) %dx%d",
                    a.id(), a.type().name(), a.quality(),
                    a.centre().getX(), a.centre().getY(), a.centre().getZ(),
                    a.extent().width(), a.extent().length());
            if (a.hasOrientation()) {
                line += String.format(" dir=(%.2f,%.2f)", a.dirX(), a.dirZ());
            }
            final String l = line;
            src.sendSuccess(() -> Component.literal(l), false);
        }
        return anchors.size();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static int writeAndReply(CommandSourceStack src, String slug, long tick,
                                     JsonObject root) {
        Optional<Path> outFileOpt = LayoutDumpSerializer.writeDump(src.getLevel(),
                slug, tick, root);
        if (outFileOpt.isEmpty()) {
            src.sendFailure(Component.literal("Failed to write dump (see log)."));
            return 0;
        }
        Path outFile = outFileOpt.get();
        // Track E1 anchor detection — INFO-level anchor summary on dump.
        // Read type counts from the JSON we just built (avoids re-running
        // the analyzer).
        String summary = summariseAnchorsFromJson(root);
        if (summary != null) {
            LOGGER.info("V2 dump {} — {}", outFile.toAbsolutePath(), summary);
            src.sendSuccess(() -> Component.literal(summary), false);
        }
        src.sendSuccess(() -> Component.literal(
                "Layout dumped to " + outFile.toAbsolutePath()), false);
        return 1;
    }

    /**
     * Reads {@code siteContext.anchors[]} from the JSON we just built
     * and formats a one-line type-count summary. Returns null when the
     * JSON has no anchors section (e.g. proximity-abort).
     */
    private static String summariseAnchorsFromJson(JsonObject root) {
        if (!root.has("siteContext")) return null;
        JsonObject site = root.getAsJsonObject("siteContext");
        if (!site.has("anchors")) return null;
        var arr = site.getAsJsonArray("anchors");
        if (arr == null || arr.size() == 0) {
            return "anchors detected: none";
        }
        java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (var el : arr) {
            JsonObject a = el.getAsJsonObject();
            if (!a.has("type")) continue;
            String t = a.get("type").getAsString();
            counts.merge(t, 1, Integer::sum);
        }
        StringBuilder sb = new StringBuilder("anchors detected: ");
        boolean first = true;
        for (var e : counts.entrySet()) {
            if (!first) sb.append(", ");
            sb.append(e.getValue()).append(' ').append(e.getKey());
            first = false;
        }
        return sb.toString();
    }
}
