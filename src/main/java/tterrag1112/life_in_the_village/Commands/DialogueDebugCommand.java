package tterrag1112.life_in_the_village.Commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Dialogue.DialogueContext;
import tterrag1112.life_in_the_village.Npc.Dialogue.DialogueLine;
import tterrag1112.life_in_the_village.Npc.Dialogue.DialogueNode;
import tterrag1112.life_in_the_village.Npc.Dialogue.DialogueRegistry;
import tterrag1112.life_in_the_village.Npc.Dialogue.DialogueRunner;
import tterrag1112.life_in_the_village.Npc.Dialogue.DialogueTree;
import tterrag1112.life_in_the_village.Npc.Dialogue.DialogueWalker;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Debug surface for the dialogue tree runtime. {@code /dialogue} root
 * with {@code list / show / start / test-predicate} subcommands.
 */
public final class DialogueDebugCommand {

    private DialogueDebugCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("dialogue")
                .then(Commands.literal("list")
                        .executes(DialogueDebugCommand::handleList))
                .then(Commands.literal("show")
                        .then(Commands.argument("treeId", StringArgumentType.string())
                                .suggests((c, b) -> {
                                    DialogueRegistry.allIds().forEach(b::suggest);
                                    return b.buildFuture();
                                })
                                .executes(DialogueDebugCommand::handleShow)))
                .then(Commands.literal("start")
                        .then(Commands.argument("speakerUuid", UuidArgument.uuid())
                                .then(Commands.argument("treeId", StringArgumentType.string())
                                        .suggests((c, b) -> {
                                            DialogueRegistry.allIds().forEach(b::suggest);
                                            return b.buildFuture();
                                        })
                                        .executes(DialogueDebugCommand::handleStart))))
                .then(Commands.literal("test-predicate")
                        .then(Commands.argument("treeId", StringArgumentType.string())
                                .suggests((c, b) -> {
                                    DialogueRegistry.allIds().forEach(b::suggest);
                                    return b.buildFuture();
                                })
                                .then(Commands.argument("speakerUuid", UuidArgument.uuid())
                                        .executes(DialogueDebugCommand::handleTestPredicate))))
        );
    }

    // =========================================================================
    // Handlers
    // =========================================================================

    private static int handleList(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        DialogueRegistry.ensureInit();
        var ids = DialogueRegistry.allIds();
        StringBuilder sb = new StringBuilder();
        sb.append("§e=== Dialogue trees §7(").append(ids.size()).append(")§e ===");
        for (String id : ids) sb.append("\n  §f").append(id);
        src.sendSuccess(() -> Component.literal(sb.toString()).withStyle(ChatFormatting.WHITE), false);
        return 1;
    }

    private static int handleShow(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        String id = StringArgumentType.getString(ctx, "treeId");
        Optional<DialogueTree> tree = DialogueRegistry.get(id);
        if (tree.isEmpty()) {
            src.sendFailure(Component.literal("No tree registered as '" + id + "'"));
            return 0;
        }
        StringBuilder sb = new StringBuilder("§e=== ").append(id).append(" ===");
        renderNode(sb, tree.get().root(), 1);
        src.sendSuccess(() -> Component.literal(sb.toString()).withStyle(ChatFormatting.WHITE), false);
        return 1;
    }

    private static void renderNode(StringBuilder sb, DialogueNode node, int depth) {
        String indent = "  ".repeat(depth);
        switch (node) {
            case DialogueNode.Branch b -> {
                sb.append("\n").append(indent).append("§6Branch §7(")
                        .append(b.predicate().describe()).append(")");
                sb.append("\n").append(indent).append("  §atrue:");
                renderNode(sb, b.trueChild(), depth + 2);
                sb.append("\n").append(indent).append("  §cfalse:");
                renderNode(sb, b.falseChild(), depth + 2);
            }
            case DialogueNode.Lines ll -> {
                sb.append("\n").append(indent).append("§eLines (")
                        .append(ll.pool().size()).append("):");
                int shown = 0;
                for (DialogueLine l : ll.pool()) {
                    if (shown >= 4) {
                        sb.append("\n").append(indent).append("  §7... ")
                                .append(ll.pool().size() - shown).append(" more");
                        break;
                    }
                    String text = l.text();
                    if (text.length() > 60) text = text.substring(0, 57) + "...";
                    sb.append("\n").append(indent).append("  §f\"").append(text).append("\"");
                    if (!l.effects().isEmpty()) {
                        sb.append(" §8[");
                        for (int i = 0; i < l.effects().size(); i++) {
                            if (i > 0) sb.append(",");
                            sb.append(l.effects().get(i).type().name());
                        }
                        sb.append("]");
                    }
                    shown++;
                }
            }
            case DialogueNode.Ref r -> sb.append("\n").append(indent)
                    .append("§dRef → §f").append(r.treeId());
        }
    }

    private static int handleStart(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        UUID speakerId = UuidArgument.getUuid(ctx, "speakerUuid");
        String treeId = StringArgumentType.getString(ctx, "treeId");
        ServerLevel level = src.getLevel();
        TownspersonMob speaker = TownspersonMob.findByUUID(level, speakerId).orElse(null);
        if (speaker == null) {
            src.sendFailure(Component.literal("No NPC found with UUID " + speakerId));
            return 0;
        }
        ServerPlayer player = src.getEntity() instanceof ServerPlayer p ? p : null;
        String line = DialogueRunner.lineFor(speaker, player, level, treeId);
        String resolvedTree = DialogueRegistry.lookupWithFallback(treeId).treeId();
        src.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "§eTree resolved to §f%s%n§e<%s>§r %s",
                resolvedTree, displayName(speaker), line)),
                false);
        return 1;
    }

    /**
     * Predicate test: finds the first {@link DialogueNode.Branch} node
     * by walking the tree from the root with the given speaker context
     * and reports the predicate's outcome.
     */
    private static int handleTestPredicate(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        String treeId = StringArgumentType.getString(ctx, "treeId");
        UUID speakerId = UuidArgument.getUuid(ctx, "speakerUuid");
        ServerLevel level = src.getLevel();
        TownspersonMob speaker = TownspersonMob.findByUUID(level, speakerId).orElse(null);
        if (speaker == null) {
            src.sendFailure(Component.literal("No NPC found with UUID " + speakerId));
            return 0;
        }
        Optional<DialogueTree> tree = DialogueRegistry.get(treeId);
        if (tree.isEmpty()) {
            src.sendFailure(Component.literal("No tree registered as '" + treeId + "'"));
            return 0;
        }
        ServerPlayer player = src.getEntity() instanceof ServerPlayer p ? p : null;
        DialogueContext dctx = DialogueContext.forPlayer(speaker, player, level);
        StringBuilder sb = new StringBuilder("§e=== Predicate trace: ").append(treeId).append(" ===");
        tracePredicates(sb, tree.get().root(), dctx, 0);
        src.sendSuccess(() -> Component.literal(sb.toString()).withStyle(ChatFormatting.WHITE), false);
        return 1;
    }

    private static void tracePredicates(StringBuilder sb, DialogueNode node,
                                        DialogueContext ctx, int depth) {
        if (depth > 16) {
            sb.append("\n  §7... (depth limit)");
            return;
        }
        switch (node) {
            case DialogueNode.Branch b -> {
                boolean result = b.predicate().test(ctx);
                sb.append("\n  ").append("  ".repeat(depth))
                        .append(result ? "§a✔ " : "§c✘ ")
                        .append(b.predicate().describe());
                tracePredicates(sb, result ? b.trueChild() : b.falseChild(), ctx, depth + 1);
            }
            case DialogueNode.Lines ll -> {
                DialogueLine pick = DialogueWalker.select(
                        new DialogueTree("__trace__", ll), ctx);
                String text = pick.text();
                if (text.length() > 80) text = text.substring(0, 77) + "...";
                sb.append("\n  ").append("  ".repeat(depth))
                        .append("§e→ §f\"").append(text).append("\"");
            }
            case DialogueNode.Ref r -> sb.append("\n  ").append("  ".repeat(depth))
                    .append("§d→ ref ").append(r.treeId());
        }
    }

    private static String displayName(TownspersonMob npc) {
        return npc.getCustomName() != null
                ? npc.getCustomName().getString()
                : npc.getName().getString();
    }
}
