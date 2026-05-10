package tterrag1112.life_in_the_village.Commands;

import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Planning.V2.Debug.AutoDumpConfig;
import tterrag1112.life_in_the_village.Village.Planning.V2.Debug.LayoutDumpSerializer;
import tterrag1112.life_in_the_village.Village.Village;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Track E1 — on-demand V2 layout dump command. Track E1 follow-up
 * adds the {@code autodump} runtime toggle subcommand.
 *
 * <p>Three subcommands under {@code /litv layout debug}:
 * <ul>
 *   <li>{@code dump <villageName>} — re-runs V2 Layers 1-4
 *       against the named village's anchor; produces schema-v2
 *       JSON with {@code command="dump"}; realization section
 *       absent.</li>
 *   <li>{@code dump_at [radius]} — runs V2 Layers 1-4 at the
 *       calling player's position; produces schema-v2 JSON with
 *       {@code command="dump_at"}; realization section absent.</li>
 *   <li>{@code autodump <on|off|status>} — runtime toggle for
 *       {@code AutoDumpConfig}. {@code status} reports the
 *       current setting. The on-demand commands are unaffected
 *       by this toggle.</li>
 * </ul>
 *
 * <p>Heavy lifting lives in {@link LayoutDumpSerializer}; this
 * class only parses args + invokes the serializer + replies.
 */
public final class LayoutDumpCommand {

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
                                                .executes(LayoutDumpCommand::autodumpToggle))))));
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
        src.sendSuccess(() -> Component.literal(
                "Layout dumped to " + outFile.toAbsolutePath()), false);
        return 1;
    }
}
