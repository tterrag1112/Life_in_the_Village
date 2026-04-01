package tterrag1112.life_in_the_village.Commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import tterrag1112.life_in_the_village.Kingdom.Castle.*;

import java.util.List;

/**
 * /castle design — NBT content authoring workflow.
 *
 * Commands:
 *
 *   /castle design begin <type> <style>
 *       Starts a design session. Types: wall, battlement, tower_cap,
 *       gatehouse, corner, window, donjon_roof
 *
 *   /castle design pos1
 *       Marks the first corner of the design region at your feet.
 *
 *   /castle design pos2
 *       Marks the second corner at your feet.
 *
 *   /castle design layer <relY> <trait>
 *       Assigns a layer trait to a Y slice (0 = bottom of region).
 *       Traits: standard, clone_once, clone_thrice, optional,
 *               merge_above, merge_below
 *
 *   /castle design layers
 *       Lists all layer trait assignments in the current session.
 *
 *   /castle design info
 *       Shows current session state.
 *
 *   /castle design export
 *       Saves the region to the correct NBT path and clears the session.
 *
 *   /castle design cancel
 *       Clears the session without saving.
 *
 *   /castle design checklist <style>
 *       Shows which NBT files exist and which are still needed.
 *
 *   /castle design checklist <style> <minVariants>
 *       Same, with a custom minimum variant count.
 */
public class CastleDesignCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("castle")
                        .then(Commands.literal("design")

                                // begin <type> <style>
                                .then(Commands.literal("begin")
                                        .then(Commands.argument("type", StringArgumentType.word())
                                                .suggests((ctx, b) -> {
                                                    for (DesignType t : DesignType.values())
                                                        b.suggest(t.name().toLowerCase());
                                                    return b.buildFuture();
                                                })
                                                .then(Commands.argument("style", StringArgumentType.word())
                                                        .suggests((ctx, b) -> {
                                                            tterrag1112.life_in_the_village.Kingdom.Castle
                                                                    .CastleStyleLoader.INSTANCE.getAll()
                                                                    .keySet().forEach(id ->
                                                                            b.suggest(id.getPath()));
                                                            return b.buildFuture();
                                                        })
                                                        .executes(ctx -> beginSession(ctx,
                                                                StringArgumentType.getString(ctx, "type"),
                                                                StringArgumentType.getString(ctx, "style")))
                                                )
                                        )
                                )

                                // pos1
                                .then(Commands.literal("pos1")
                                        .executes(CastleDesignCommand::setPos1))

                                // pos2
                                .then(Commands.literal("pos2")
                                        .executes(CastleDesignCommand::setPos2))

                                // layer <relY> <trait>
                                .then(Commands.literal("layer")
                                        .then(Commands.argument("relY", IntegerArgumentType.integer(0))
                                                .then(Commands.argument("trait", StringArgumentType.word())
                                                        .suggests((ctx, b) -> {
                                                            for (LayerTrait t : LayerTrait.values())
                                                                b.suggest(t.name().toLowerCase());
                                                            return b.buildFuture();
                                                        })
                                                        .executes(ctx -> setLayerTrait(ctx,
                                                                IntegerArgumentType.getInteger(ctx, "relY"),
                                                                StringArgumentType.getString(ctx, "trait")))
                                                )
                                        )
                                )

                                // layers — list all assignments
                                .then(Commands.literal("layers")
                                        .executes(CastleDesignCommand::listLayerTraits))

                                // info
                                .then(Commands.literal("info")
                                        .executes(CastleDesignCommand::showInfo))

                                // export
                                .then(Commands.literal("export")
                                        .executes(CastleDesignCommand::exportDesign))

                                // cancel
                                .then(Commands.literal("cancel")
                                        .executes(CastleDesignCommand::cancelSession))

                                // checklist <style> [minVariants]
                                .then(Commands.literal("checklist")
                                        .then(Commands.argument("style", StringArgumentType.word())
                                                .executes(ctx -> showChecklist(ctx,
                                                        StringArgumentType.getString(ctx, "style"), 3))
                                                .then(Commands.argument("minVariants",
                                                                IntegerArgumentType.integer(1, 20))
                                                        .executes(ctx -> showChecklist(ctx,
                                                                StringArgumentType.getString(ctx, "style"),
                                                                IntegerArgumentType.getInteger(ctx, "minVariants")))
                                                )
                                        )
                                )
                        )
        );
    }

    // =========================================================================
    // Handlers
    // =========================================================================

    private static int beginSession(CommandContext<CommandSourceStack> ctx,
                                    String typeArg, String styleId) {
        ServerPlayer player = getPlayer(ctx);
        if (player == null) return 0;

        DesignType type = parseType(ctx, typeArg);
        if (type == null) return 0;

        // Clear any existing session
        DesignSession.clear(player.getUUID());
        DesignSession session = DesignSession.begin(player.getUUID(), type, styleId);

        ctx.getSource().sendSuccess(() -> Component.literal(
                "§aDesign session started: §f" + session.summary() + "\n" +
                        "§7Next: /castle design pos1 and pos2 to mark the region."
        ), false);
        return 1;
    }

    private static int setPos1(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = getPlayer(ctx);
        if (player == null) return 0;

        DesignSession session = requireSession(ctx, player);
        if (session == null) return 0;

        session.pos1 = player.blockPosition();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "§aPos1 set to " + session.pos1 +
                        (session.pos2 != null ? " — region: " + session.summary() :
                                " — now set /castle design pos2.")
        ), false);
        return 1;
    }

    private static int setPos2(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = getPlayer(ctx);
        if (player == null) return 0;

        DesignSession session = requireSession(ctx, player);
        if (session == null) return 0;

        session.pos2 = player.blockPosition();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "§aPos2 set to " + session.pos2 + "\n§7Region: " + session.summary()
        ), false);

        // Give usage hint
        ctx.getSource().sendSuccess(() -> Component.literal(
                "§7Tip: Use §f/castle design layer <relY> <trait>§7 to tag rows,\n" +
                        "then §f/castle design export§7 to save."
        ), false);
        return 1;
    }

    private static int setLayerTrait(CommandContext<CommandSourceStack> ctx,
                                     int relY, String traitArg) {
        ServerPlayer player = getPlayer(ctx);
        if (player == null) return 0;

        DesignSession session = requireSession(ctx, player);
        if (session == null) return 0;

        if (!session.hasRegion()) {
            ctx.getSource().sendFailure(Component.literal(
                    "Set the region first with pos1 and pos2."));
            return 0;
        }

        if (relY >= session.height()) {
            ctx.getSource().sendFailure(Component.literal(
                    "relY " + relY + " is outside the region height " +
                            session.height() + ". (0 = bottom of region)"));
            return 0;
        }

        LayerTrait trait = parseTrait(ctx, traitArg);
        if (trait == null) return 0;

        if (trait == LayerTrait.STANDARD) {
            session.layerTraits.remove(relY);
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "§7Cleared trait at relY=" + relY + " (reverted to STANDARD)."
            ), false);
        } else {
            session.layerTraits.put(relY, trait);
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "§aAssigned §f" + trait.name() + "§a to relY=" + relY +
                            " (world Y=" + (session.min().getY() + relY) + ")."
            ), false);
        }
        return 1;
    }

    private static int listLayerTraits(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = getPlayer(ctx);
        if (player == null) return 0;

        DesignSession session = requireSession(ctx, player);
        if (session == null) return 0;

        if (!session.hasRegion()) {
            ctx.getSource().sendFailure(Component.literal("No region selected."));
            return 0;
        }

        StringBuilder sb = new StringBuilder("§6Layer traits for §f");
        sb.append(session.summary()).append("\n");
        sb.append("§7(relY | worldY | trait)\n");

        int baseY = session.min().getY();
        for (int relY = 0; relY < session.height(); relY++) {
            LayerTrait trait = session.layerTraits.getOrDefault(relY, LayerTrait.STANDARD);
            String color = switch (trait) {
                case STANDARD    -> "§7";
                case CLONE_ONCE, CLONE_THRICE -> "§b";
                case OPTIONAL    -> "§e";
                case MERGE_ABOVE, MERGE_BELOW -> "§d";
            };
            // Only print non-standard rows to keep output short
            if (trait != LayerTrait.STANDARD) {
                sb.append(color)
                        .append("  ").append(String.format("%2d", relY))
                        .append(" | Y=").append(baseY + relY)
                        .append(" | ").append(trait.name())
                        .append("\n");
            }
        }

        if (session.layerTraits.isEmpty()) {
            sb.append("§7  (all STANDARD — no traits assigned yet)");
        }

        final String msg = sb.toString();
        ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }

    private static int showInfo(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = getPlayer(ctx);
        if (player == null) return 0;

        DesignSession session = DesignSession.get(player.getUUID());
        if (session == null) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "§7No active session. Start one with /castle design begin <type> <style>."
            ), false);
            return 0;
        }

        ctx.getSource().sendSuccess(() -> Component.literal(
                "§6Active session:\n§f" + session.summary()
        ), false);
        return 1;
    }

    private static int exportDesign(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = getPlayer(ctx);
        if (player == null) return 0;

        DesignSession session = requireSession(ctx, player);
        if (session == null) return 0;

        if (!session.hasRegion()) {
            ctx.getSource().sendFailure(Component.literal(
                    "Set the region first with /castle design pos1 and pos2."));
            return 0;
        }

        ServerLevel level = player.level();
        DesignExporter.ExportResult result = DesignExporter.export(session, level);

        if (result.success()) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "§aExported! §f" + result.message() + "\n" +
                            "§7Size: " + result.width() + "×" + result.height() +
                            "×" + result.depth() + " blocks.\n" +
                            "§7Index: " + result.index() + ". " +
                            "Run §f/castle design checklist " + session.styleId() +
                            "§7 to see progress."
            ), true);

            DesignSession.clear(player.getUUID());

            // Hint: what to build next
            printNextHint(ctx, session.type, session.styleId);
        } else {
            ctx.getSource().sendFailure(Component.literal(
                    "§cExport failed: " + result.message()));
        }

        return result.success() ? 1 : 0;
    }

    private static int cancelSession(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = getPlayer(ctx);
        if (player == null) return 0;

        DesignSession session = DesignSession.get(player.getUUID());
        DesignSession.clear(player.getUUID());

        if (session == null) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "§7No active session to cancel."), false);
        } else {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "§eSession cancelled."), false);
        }
        return 1;
    }

    private static int showChecklist(CommandContext<CommandSourceStack> ctx,
                                     String styleId, int minVariants) {
        List<DesignChecklist.CheckItem> items =
                DesignChecklist.build(styleId, minVariants);

        StringBuilder sb = new StringBuilder(
                "§6NBT checklist for style: §f" + styleId + "\n");

        int complete = 0;
        for (DesignChecklist.CheckItem item : items) {
            sb.append(item.format()).append("\n");
            if (item.complete()) complete++;
        }

        sb.append("\n§7").append(complete).append("/")
                .append(items.size()).append(" types complete.");

        if (complete < items.size()) {
            sb.append("\n§7Use §f/castle design begin <type> ").append(styleId)
                    .append("§7 to start the next one.");
        }

        final String msg = sb.toString();
        ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }

    // =========================================================================
    // Hints
    // =========================================================================

    private static void printNextHint(CommandContext<CommandSourceStack> ctx,
                                      DesignType justExported, String styleId) {
        // Find the next type that still needs content
        List<DesignChecklist.CheckItem> items = DesignChecklist.build(styleId);
        DesignChecklist.CheckItem next = items.stream()
                .filter(i -> !i.complete())
                .findFirst()
                .orElse(null);

        if (next == null) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "§a✔ All design types for '§f" + styleId +
                            "§a' are complete!"), false);
        } else {
            final String nextType = next.type().name().toLowerCase();
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "§7Next needed: §f/castle design begin " + nextType +
                            " " + styleId), false);
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static ServerPlayer getPlayer(CommandContext<CommandSourceStack> ctx) {
        try {
            return ctx.getSource().getPlayerOrException();
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal(
                    "This command must be run by a player."));
            return null;
        }
    }

    private static DesignSession requireSession(CommandContext<CommandSourceStack> ctx,
                                                ServerPlayer player) {
        DesignSession session = DesignSession.get(player.getUUID());
        if (session == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "No active session. Start one with " +
                            "/castle design begin <type> <style>"));
        }
        return session;
    }

    private static DesignType parseType(CommandContext<CommandSourceStack> ctx,
                                        String arg) {
        try {
            return DesignType.valueOf(arg.toUpperCase());
        } catch (IllegalArgumentException e) {
            StringBuilder valid = new StringBuilder();
            for (DesignType t : DesignType.values())
                valid.append(t.name().toLowerCase()).append(", ");
            ctx.getSource().sendFailure(Component.literal(
                    "Unknown design type '" + arg + "'. Valid: " + valid));
            return null;
        }
    }

    private static LayerTrait parseTrait(CommandContext<CommandSourceStack> ctx,
                                         String arg) {
        try {
            return LayerTrait.valueOf(arg.toUpperCase());
        } catch (IllegalArgumentException e) {
            StringBuilder valid = new StringBuilder();
            for (LayerTrait t : LayerTrait.values())
                valid.append(t.name().toLowerCase()).append(", ");
            ctx.getSource().sendFailure(Component.literal(
                    "Unknown trait '" + arg + "'. Valid: " + valid));
            return null;
        }
    }

    // DesignSession needs a styleId() accessor
    // Add to DesignSession: public String styleId() { return styleId; }
}