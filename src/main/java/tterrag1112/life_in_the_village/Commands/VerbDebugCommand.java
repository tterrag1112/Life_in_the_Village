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
import tterrag1112.life_in_the_village.Npc.Verbs.PlayerVerb;
import tterrag1112.life_in_the_village.Npc.Verbs.PlayerVerbRegistry;
import tterrag1112.life_in_the_village.Npc.Verbs.VerbContext;
import tterrag1112.life_in_the_village.Npc.Verbs.VerbInvocation;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Debug surface for the player-verb runtime. {@code /verb list <uuid>}
 * shows verbs available for the issuing player against the supplied
 * NPC; {@code /verb fire <verb> <uuid> [k=v k=v ...]} invokes a verb
 * for end-to-end testing without needing the UI button.
 */
public final class VerbDebugCommand {

    private VerbDebugCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("verb")
                .then(Commands.literal("list")
                        .then(Commands.argument("uuid", UuidArgument.uuid())
                                .executes(VerbDebugCommand::handleList)))
                .then(Commands.literal("fire")
                        .then(Commands.argument("verb", StringArgumentType.word())
                                .suggests((c, b) -> {
                                    PlayerVerbRegistry.all().forEach(v -> b.suggest(v.id()));
                                    return b.buildFuture();
                                })
                                .then(Commands.argument("uuid", UuidArgument.uuid())
                                        .executes(VerbDebugCommand::handleFire)
                                        .then(Commands.argument("args", StringArgumentType.greedyString())
                                                .executes(VerbDebugCommand::handleFireWithArgs)))))
        );
    }

    // =========================================================================
    // Handlers
    // =========================================================================

    private static int handleList(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer player = src.getEntity() instanceof ServerPlayer p ? p : null;
        if (player == null) {
            src.sendFailure(Component.literal("This command must be issued by a player."));
            return 0;
        }
        UUID id = UuidArgument.getUuid(ctx, "uuid");
        ServerLevel level = src.getLevel();
        TownspersonMob npc = TownspersonMob.findByUUID(level, id).orElse(null);
        if (npc == null) {
            src.sendFailure(Component.literal("No NPC with UUID " + id));
            return 0;
        }

        long now = level.getGameTime();
        VerbContext vctx = PlayerVerb.context(player, npc, level);
        List<PlayerVerb> available = PlayerVerbRegistry.availableFor(vctx);

        StringBuilder sb = new StringBuilder();
        sb.append("§e=== Verbs for §f").append(displayName(npc))
                .append(" §7(").append(id).append(")§e ===");
        if (available.isEmpty()) {
            sb.append("\n§7(none)");
        } else {
            for (PlayerVerb v : available) {
                long remaining = npc.getVerbCooldowns()
                        .ticksRemaining(v.id(), player.getUUID(), now);
                String cdTag = remaining > 0
                        ? " §c[cooldown " + Math.max(1L, remaining / 20L) + "s]"
                        : "";
                sb.append(String.format(Locale.ROOT,
                        "%n  §a%-14s §f%s%s",
                        v.id(), v.label().getString(), cdTag));
            }
        }
        // Also show verbs that exist but aren't currently available — useful
        // for diagnosing eligibility gates.
        List<String> availIds = available.stream().map(PlayerVerb::id).toList();
        sb.append("\n§7-- unavailable: ");
        boolean any = false;
        for (PlayerVerb v : PlayerVerbRegistry.all()) {
            if (availIds.contains(v.id())) continue;
            if (any) sb.append(", ");
            sb.append(v.id());
            any = true;
        }
        if (!any) sb.append("(none)");

        src.sendSuccess(() -> Component.literal(sb.toString()).withStyle(ChatFormatting.WHITE), false);
        return 1;
    }

    private static int handleFire(CommandContext<CommandSourceStack> ctx) {
        return doFire(ctx, Map.of());
    }

    private static int handleFireWithArgs(CommandContext<CommandSourceStack> ctx) {
        String raw = StringArgumentType.getString(ctx, "args");
        return doFire(ctx, parseArgs(raw));
    }

    private static int doFire(CommandContext<CommandSourceStack> ctx, Map<String, String> args) {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer player = src.getEntity() instanceof ServerPlayer p ? p : null;
        if (player == null) {
            src.sendFailure(Component.literal("This command must be issued by a player."));
            return 0;
        }
        String verbId = StringArgumentType.getString(ctx, "verb");
        UUID id = UuidArgument.getUuid(ctx, "uuid");
        ServerLevel level = src.getLevel();
        TownspersonMob npc = TownspersonMob.findByUUID(level, id).orElse(null);
        if (npc == null) {
            src.sendFailure(Component.literal("No NPC with UUID " + id));
            return 0;
        }
        var result = VerbInvocation.invoke(player, npc, level, verbId, new HashMap<>(args));
        src.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                        "Verb §f%s§r → success=§f%s§r tree=§f%s§r status=§7%s",
                        verbId, result.success(), result.resultTreeId(),
                        result.statusText())),
                false);
        return result.success() ? 1 : 0;
    }

    /** Parses {@code k1=v1 k2=v2} into a map. Whitespace separates pairs. */
    private static Map<String, String> parseArgs(String raw) {
        Map<String, String> out = new HashMap<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String token : raw.split("\\s+")) {
            int eq = token.indexOf('=');
            if (eq <= 0) continue;
            out.put(token.substring(0, eq), token.substring(eq + 1));
        }
        return out;
    }

    private static String displayName(TownspersonMob npc) {
        return npc.getCustomName() != null
                ? npc.getCustomName().getString()
                : npc.getName().getString();
    }
}
