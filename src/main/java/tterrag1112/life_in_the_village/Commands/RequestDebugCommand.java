package tterrag1112.life_in_the_village.Commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import tterrag1112.life_in_the_village.Guilds.Common.AbstractGuild;
import tterrag1112.life_in_the_village.Guilds.Common.GuildSavedData;
import tterrag1112.life_in_the_village.Guilds.Common.Requests.Request;
import tterrag1112.life_in_the_village.Guilds.Common.Requests.RequestBoard;
import tterrag1112.life_in_the_village.Guilds.Common.Requests.RequestPosting;
import tterrag1112.life_in_the_village.Guilds.Common.Requests.RequestScope;
import tterrag1112.life_in_the_village.Guilds.Common.Requests.RequestSettlement;
import tterrag1112.life_in_the_village.Guilds.Common.Requests.RequestStatus;
import tterrag1112.life_in_the_village.Guilds.Common.Requests.RequestType;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 4 doc 28 debug surface.
 *
 * <ul>
 *   <li>{@code /request list [scope]} — every request, optionally
 *       filtered by scope.</li>
 *   <li>{@code /request post <guildId> <type> <itemOrMobId> <count>
 *       <bounty> [scope]} — manual post bypassing channel routing.</li>
 *   <li>{@code /request accept <requestId> <guildId>} — assign an
 *       accepting guild + first-tier fulfiller.</li>
 *   <li>{@code /request fulfill <requestId>} — force completion +
 *       run the settlement payment flow.</li>
 *   <li>{@code /request escalate <requestId>} — bump scope one tier.</li>
 * </ul>
 */
public final class RequestDebugCommand {

    private RequestDebugCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("request")
                .then(Commands.literal("list")
                        .executes(c -> handleList(c, null))
                        .then(Commands.argument("scope", StringArgumentType.word())
                                .suggests((c, b) -> {
                                    for (RequestScope s : RequestScope.values()) b.suggest(s.name());
                                    return b.buildFuture();
                                })
                                .executes(c -> handleList(c, parseScope(c)))))
                .then(Commands.literal("post")
                        .then(Commands.argument("guildId", UuidArgument.uuid())
                                .then(Commands.argument("type", StringArgumentType.word())
                                        .suggests((c, b) -> {
                                            for (RequestType t : RequestType.values()) b.suggest(t.name());
                                            return b.buildFuture();
                                        })
                                        .then(Commands.argument("targetId", StringArgumentType.string())
                                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 999))
                                                        .then(Commands.argument("bounty", IntegerArgumentType.integer(1, 99999))
                                                                .executes(RequestDebugCommand::handlePost)
                                                                .then(Commands.argument("scope", StringArgumentType.word())
                                                                        .suggests((c, b) -> {
                                                                            for (RequestScope s : RequestScope.values()) b.suggest(s.name());
                                                                            return b.buildFuture();
                                                                        })
                                                                        .executes(RequestDebugCommand::handlePost)))))))
                )
                .then(Commands.literal("accept")
                        .then(Commands.argument("requestId", UuidArgument.uuid())
                                .then(Commands.argument("guildId", UuidArgument.uuid())
                                        .executes(RequestDebugCommand::handleAccept))))
                .then(Commands.literal("fulfill")
                        .then(Commands.argument("requestId", UuidArgument.uuid())
                                .executes(RequestDebugCommand::handleFulfill)))
                .then(Commands.literal("escalate")
                        .then(Commands.argument("requestId", UuidArgument.uuid())
                                .executes(RequestDebugCommand::handleEscalate)))
        );
    }

    // ── list ─────────────────────────────────────────────────────────────

    private static int handleList(CommandContext<CommandSourceStack> ctx, RequestScope scope) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        StringBuilder sb = new StringBuilder("§e=== Request board");
        if (scope != null) sb.append(" (").append(scope).append(")");
        sb.append(" ===§r");
        int count = 0;
        for (Request r : RequestBoard.get(level).all()) {
            if (scope != null && r.scope() != scope) continue;
            count++;
            sb.append(String.format(Locale.ROOT,
                    "%n  §a%-9s§r §7%-13s§r %s §7(scope=%s, bounty=%d br, target=%s×%d)",
                    r.status().name(), r.type().name(), r.requestId(),
                    r.scope().name(), r.bronzeReward(),
                    targetLabel(r), r.target().targetCount()));
        }
        if (count == 0) sb.append("\n  §7(none)");
        src.sendSuccess(() -> Component.literal(sb.toString())
                .withStyle(ChatFormatting.WHITE), false);
        return 1;
    }

    // ── post ─────────────────────────────────────────────────────────────

    private static int handlePost(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        UUID guildId = UuidArgument.getUuid(ctx, "guildId");
        AbstractGuild guild = GuildSavedData.get(level).get(guildId).orElse(null);
        if (guild == null) { src.sendFailure(Component.literal("No guild " + guildId)); return 0; }
        RequestType type;
        try {
            type = RequestType.valueOf(
                    StringArgumentType.getString(ctx, "type").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            src.sendFailure(Component.literal("Unknown request type")); return 0;
        }
        String targetId = StringArgumentType.getString(ctx, "targetId");
        int count = IntegerArgumentType.getInteger(ctx, "count");
        long bounty = IntegerArgumentType.getInteger(ctx, "bounty");
        RequestScope scope = RequestScope.VILLAGE_PUBLIC;
        try {
            scope = RequestScope.valueOf(
                    StringArgumentType.getString(ctx, "scope").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {}

        long deadline = level.getGameTime() + 7L * 24000L;
        RequestPosting.RequestSpec spec;
        if (type == RequestType.HUNT) {
            spec = RequestPosting.RequestSpec.hunt(targetId, count, bounty, deadline, scope);
        } else if (type == RequestType.SURVEY || type == RequestType.ESCORT) {
            spec = RequestPosting.RequestSpec.atLocation(targetId, /* location */ null,
                    bounty, deadline, scope);
        } else {
            Item item = resolveItem(targetId);
            if (item == Items.AIR) {
                src.sendFailure(Component.literal("Unknown item: " + targetId)); return 0;
            }
            spec = RequestPosting.RequestSpec.items(item, count, bounty, deadline, scope);
        }
        Optional<Request> posted = RequestPosting.post(level, guild, type, spec);
        if (posted.isEmpty()) {
            src.sendFailure(Component.literal(
                    "Post failed (treasury too small or guild can't post)"));
            return 0;
        }
        UUID id = posted.get().requestId();
        src.sendSuccess(() -> Component.literal("§aPosted §f" + id), false);
        return 1;
    }

    // ── accept ───────────────────────────────────────────────────────────

    private static int handleAccept(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        UUID requestId = UuidArgument.getUuid(ctx, "requestId");
        UUID guildId   = UuidArgument.getUuid(ctx, "guildId");
        AbstractGuild guild = GuildSavedData.get(level).get(guildId).orElse(null);
        if (guild == null) { src.sendFailure(Component.literal("No guild " + guildId)); return 0; }
        Request r = RequestBoard.get(level).get(requestId).orElse(null);
        if (r == null) { src.sendFailure(Component.literal("No request " + requestId)); return 0; }
        UUID fulfiller = guild.members().keySet().stream().findFirst().orElse(null);
        if (fulfiller == null) {
            src.sendFailure(Component.literal("Guild has no members to act as fulfiller"));
            return 0;
        }
        boolean ok = RequestBoard.get(level).accept(requestId, guildId, fulfiller,
                Math.max(1, r.target().targetCount()), level.getGameTime());
        if (!ok) {
            src.sendFailure(Component.literal("Accept failed (request is " + r.status() + ")"));
            return 0;
        }
        src.sendSuccess(() -> Component.literal("§aAccepted§r — fulfiller=§f"
                + fulfiller), false);
        return 1;
    }

    // ── fulfill ──────────────────────────────────────────────────────────

    private static int handleFulfill(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        UUID requestId = UuidArgument.getUuid(ctx, "requestId");
        Request r = RequestBoard.get(level).get(requestId).orElse(null);
        if (r == null) { src.sendFailure(Component.literal("No request " + requestId)); return 0; }
        if (r.status() == RequestStatus.OPEN) {
            src.sendFailure(Component.literal("Request not yet accepted"));
            return 0;
        }
        if (!RequestBoard.get(level).fulfill(requestId)) {
            src.sendFailure(Component.literal("Fulfill failed (terminal already)"));
            return 0;
        }
        Request updated = RequestBoard.get(level).get(requestId).orElse(r);
        RequestSettlement.settleFulfilled(level, updated);
        src.sendSuccess(() -> Component.literal("§aFulfilled§r — settlement run"), false);
        return 1;
    }

    // ── escalate ─────────────────────────────────────────────────────────

    private static int handleEscalate(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        UUID requestId = UuidArgument.getUuid(ctx, "requestId");
        RequestScope newScope = RequestBoard.get(level).escalate(requestId, level.getGameTime());
        if (newScope == null) {
            src.sendFailure(Component.literal(
                    "Escalation rejected (request not OPEN or already at GLOBAL)"));
            return 0;
        }
        src.sendSuccess(() -> Component.literal(
                "§aEscalated§r to §f" + newScope), false);
        return 1;
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static RequestScope parseScope(CommandContext<CommandSourceStack> ctx) {
        try {
            return RequestScope.valueOf(
                    StringArgumentType.getString(ctx, "scope").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String targetLabel(Request r) {
        return switch (r.type()) {
            case HUNT -> r.target().targetMobId();
            case SURVEY, ESCORT -> r.target().targetBiome();
            default -> r.target().targetItem().toString();
        };
    }

    private static Item resolveItem(String id) {
        try {
            ResourceLocation rl = id.contains(":")
                    ? ResourceLocation.parse(id)
                    : ResourceLocation.parse("minecraft:" + id);
            return BuiltInRegistries.ITEM.getValue(rl);
        } catch (Exception e) {
            return Items.AIR;
        }
    }
}
