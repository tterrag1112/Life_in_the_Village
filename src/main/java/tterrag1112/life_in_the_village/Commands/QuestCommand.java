package tterrag1112.life_in_the_village.Commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import tterrag1112.life_in_the_village.Npc.Religion.GodRegistry;
import tterrag1112.life_in_the_village.Quests.Objective;
import tterrag1112.life_in_the_village.Quests.Quest;
import tterrag1112.life_in_the_village.Quests.QuestDifficulty;
import tterrag1112.life_in_the_village.Quests.QuestIssuer;
import tterrag1112.life_in_the_village.Quests.QuestSavedData;
import tterrag1112.life_in_the_village.Quests.QuestStatus;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * F2a-1 — the {@code /quest} surface: list the executing player's quests, and a debug
 * grant of the proving religious quest. The player-facing readout this stage (a journal
 * UI is later).
 *
 * <p>Gate-0 rework of the guild surface: the {@code <kind>} string argument is replaced
 * by per-kind literals so each target can be a proper registry id —
 * {@code /quest guild hunt minecraft:zombie 3 EASY} parses ({@link IdentifierArgument}
 * accepts namespaced ids; the old single-word string choked on {@code :}), tab-completes
 * from the right registry, and is validated against it (no more {@code zmbie} quests).
 * Stored ids are namespaced via {@link Identifier} normalization so they compare
 * like-for-like with the namespaced ids {@code QuestEventHooks} emits. Plus
 * {@code /quest abandon <index> | all} to drop active quests (persisted).</p>
 */
public final class QuestCommand {

    private QuestCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("quest")
                .executes(QuestCommand::handleList)
                .then(Commands.literal("list").executes(QuestCommand::handleList))
                .then(Commands.literal("grant")
                        .then(Commands.argument("god", StringArgumentType.word())
                                .suggests((c, b) -> {
                                    for (var g : GodRegistry.all()) b.suggest(g.id());
                                    return b.buildFuture();
                                })
                                .executes(ctx -> handleGrant(ctx, "offering", 3))
                                .then(Commands.argument("kind", StringArgumentType.word())
                                        .suggests((c, b) -> {
                                            for (String k : QuestIssuer.KINDS) b.suggest(k);
                                            return b.buildFuture();
                                        })
                                        .executes(ctx -> handleGrant(ctx,
                                                StringArgumentType.getString(ctx, "kind"), 3))
                                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
                                                .executes(ctx -> handleGrant(ctx,
                                                        StringArgumentType.getString(ctx, "kind"),
                                                        IntegerArgumentType.getInteger(ctx, "count")))))))
                // F2b-1 — issue a guild-kind quest. Gate-0: per-kind literals with
                // registry-id targets:
                //   /quest guild hunt <entityType> [count] [difficulty]
                //   /quest guild gather|deliver <item> [count] [difficulty]
                //   /quest guild explore <biome> [count] [difficulty]
                //   /quest guild escort <destination> [count] [difficulty]
                .then(Commands.literal("guild")
                        .then(guildIdKind("hunt"))
                        .then(guildIdKind("gather"))
                        .then(guildIdKind("deliver"))
                        .then(guildIdKind("explore"))
                        .then(guildEscort()))
                // Gate-0 — abandon active quests (indices as shown by /quest list).
                .then(Commands.literal("abandon")
                        .then(Commands.literal("all").executes(QuestCommand::handleAbandonAll))
                        .then(Commands.argument("index", IntegerArgumentType.integer(1))
                                .executes(QuestCommand::handleAbandon)))
                // F2b-1 — the poll / turn-in evaluation (completes satisfied poll quests).
                .then(Commands.literal("turnin").executes(QuestCommand::handleTurnin))
                .then(Commands.literal("check").executes(QuestCommand::handleTurnin)));
    }

    // ── Guild nodes ──────────────────────────────────────────────────────────

    /** A guild kind whose target is a registry id (hunt / gather / deliver / explore). */
    private static LiteralArgumentBuilder<CommandSourceStack> guildIdKind(String kind) {
        return Commands.literal(kind)
                .then(Commands.argument("target", IdentifierArgument.id())
                        .suggests((c, b) -> suggestTargets(kind, c, b))
                        .executes(ctx -> handleGuild(ctx, kind, 3, "EASY"))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
                                .executes(ctx -> handleGuild(ctx, kind,
                                        IntegerArgumentType.getInteger(ctx, "count"), "EASY"))
                                .then(Commands.argument("difficulty", StringArgumentType.word())
                                        .suggests(QuestCommand::suggestDifficulties)
                                        .executes(ctx -> handleGuild(ctx, kind,
                                                IntegerArgumentType.getInteger(ctx, "count"),
                                                StringArgumentType.getString(ctx, "difficulty"))))));
    }

    /** Escort's destination is a free string (not a registry id) — kept as string(). */
    private static LiteralArgumentBuilder<CommandSourceStack> guildEscort() {
        return Commands.literal("escort")
                .then(Commands.argument("target", StringArgumentType.string())
                        .executes(ctx -> handleEscort(ctx, 3, "EASY"))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
                                .executes(ctx -> handleEscort(ctx,
                                        IntegerArgumentType.getInteger(ctx, "count"), "EASY"))
                                .then(Commands.argument("difficulty", StringArgumentType.word())
                                        .suggests(QuestCommand::suggestDifficulties)
                                        .executes(ctx -> handleEscort(ctx,
                                                IntegerArgumentType.getInteger(ctx, "count"),
                                                StringArgumentType.getString(ctx, "difficulty"))))));
    }

    // ── Suggestions ──────────────────────────────────────────────────────────

    private static CompletableFuture<Suggestions> suggestTargets(
            String kind, CommandContext<CommandSourceStack> c, SuggestionsBuilder b) {
        Iterable<Identifier> ids = switch (kind) {
            case "hunt" -> BuiltInRegistries.ENTITY_TYPE.keySet();
            case "gather", "deliver" -> BuiltInRegistries.ITEM.keySet();
            // listElements + key().identifier() — the PoiDiscovery registry-walk
            // idiom (the in-repo precedent for enumerating a dynamic registry).
            case "explore" -> c.getSource().getLevel().registryAccess()
                    .lookupOrThrow(Registries.BIOME)
                    .listElements().map(h -> h.key().identifier()).toList();
            default -> List.of();
        };
        String rem = b.getRemaining().toLowerCase(Locale.ROOT);
        for (Identifier id : ids) {
            String s = id.toString();
            if (s.startsWith(rem) || id.getPath().startsWith(rem)) b.suggest(s);
        }
        return b.buildFuture();
    }

    private static CompletableFuture<Suggestions> suggestDifficulties(
            CommandContext<CommandSourceStack> c, SuggestionsBuilder b) {
        for (QuestDifficulty d : QuestDifficulty.values()) b.suggest(d.name());
        return b.buildFuture();
    }

    // ── Handlers ─────────────────────────────────────────────────────────────

    private static int handleGuild(CommandContext<CommandSourceStack> ctx,
                                   String kind, int count, String diffName) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        ServerPlayer player = src.getPlayer();
        if (player == null) { src.sendFailure(Component.literal("Run as a player.")); return 0; }
        Identifier target = IdentifierArgument.getId(ctx, "target");
        // Validate against the right registry — reject unknown ids loudly instead of
        // minting an uncompletable quest (the 'zmbie' trap).
        String invalid = switch (kind) {
            case "hunt" -> BuiltInRegistries.ENTITY_TYPE.getOptional(target).isEmpty()
                    ? "Unknown entity type" : null;
            case "gather", "deliver" -> BuiltInRegistries.ITEM.getOptional(target).isEmpty()
                    ? "Unknown item" : null;
            case "explore" -> level.registryAccess().lookupOrThrow(Registries.BIOME)
                    .get(ResourceKey.create(Registries.BIOME, target)).isEmpty()
                    ? "Unknown biome" : null;
            default -> null;
        };
        if (invalid != null) {
            src.sendFailure(Component.literal(invalid + " '" + target + "'"));
            return 0;
        }
        QuestDifficulty diff = parseDifficulty(src, diffName);
        if (diff == null) return 0;
        return issueGuild(src, level, player, kind, target.toString(), count, diff);
    }

    private static int handleEscort(CommandContext<CommandSourceStack> ctx,
                                    int count, String diffName) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        ServerPlayer player = src.getPlayer();
        if (player == null) { src.sendFailure(Component.literal("Run as a player.")); return 0; }
        String target = StringArgumentType.getString(ctx, "target");
        QuestDifficulty diff = parseDifficulty(src, diffName);
        if (diff == null) return 0;
        return issueGuild(src, level, player, "escort", target, count, diff);
    }

    /** Parses a difficulty name; null (after a clear failure message) when unknown. */
    private static QuestDifficulty parseDifficulty(CommandSourceStack src, String diffName) {
        try {
            return QuestDifficulty.valueOf(diffName.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            StringBuilder names = new StringBuilder();
            for (QuestDifficulty d : QuestDifficulty.values()) {
                if (names.length() > 0) names.append(" / ");
                names.append(d.name());
            }
            src.sendFailure(Component.literal(
                    "Unknown difficulty '" + diffName + "' (" + names + ")"));
            return null;
        }
    }

    private static int issueGuild(CommandSourceStack src, ServerLevel level,
                                  ServerPlayer player, String kind, String target,
                                  int count, QuestDifficulty diff) {
        Quest q = QuestIssuer.grantGuild(level, player, kind, target, count, diff);
        if (q == null) {
            src.sendFailure(Component.literal("Unknown guild kind '" + kind
                    + "' (hunt / gather / deliver / explore / escort)"));
            return 0;
        }
        Quest issued = q;
        src.sendSuccess(() -> Component.literal("§aIssued: " + issued.title()
                + " [" + issued.difficulty() + "]"), false);
        return 1;
    }

    private static int handleAbandon(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        ServerPlayer player = src.getPlayer();
        if (player == null) { src.sendFailure(Component.literal("Run as a player.")); return 0; }
        QuestSavedData store = QuestSavedData.get(level);
        List<Quest> active = store.active(player.getUUID());
        int index = IntegerArgumentType.getInteger(ctx, "index");
        if (index > active.size()) {
            src.sendFailure(Component.literal("No active quest #" + index
                    + " (you have " + active.size() + " — see /quest list)"));
            return 0;
        }
        Quest q = active.get(index - 1);
        store.remove(player.getUUID(), q.questId());
        src.sendSuccess(() -> Component.literal("§7Abandoned: " + q.title()), false);
        return 1;
    }

    private static int handleAbandonAll(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        ServerPlayer player = src.getPlayer();
        if (player == null) { src.sendFailure(Component.literal("Run as a player.")); return 0; }
        QuestSavedData store = QuestSavedData.get(level);
        List<Quest> active = store.active(player.getUUID());
        for (Quest q : active) store.remove(player.getUUID(), q.questId());
        int n = active.size();
        src.sendSuccess(() -> Component.literal("§7Abandoned " + n + " active quest(s)."), false);
        return n;
    }

    private static int handleTurnin(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer player = src.getPlayer();
        if (player == null) { src.sendFailure(Component.literal("Run as a player.")); return 0; }
        tterrag1112.life_in_the_village.Quests.QuestEvents.evaluate(player);
        src.sendSuccess(() -> Component.literal("§7Turn-in evaluated."), false);
        return 1;
    }

    private static int handleGrant(CommandContext<CommandSourceStack> ctx, String kind, int count) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        ServerPlayer player = src.getPlayer();
        if (player == null) { src.sendFailure(Component.literal("Run as a player.")); return 0; }
        String godId = StringArgumentType.getString(ctx, "god");
        Quest q = QuestIssuer.grant(level, player, godId, kind, count);
        if (q == null) {
            src.sendFailure(Component.literal("Unknown god '" + godId + "' or kind '" + kind
                    + "' (kinds: offering / pilgrimage / relic / rites)"));
            return 0;
        }
        src.sendSuccess(() -> Component.literal("§aIssued: " + q.title()), false);
        return 1;
    }

    private static int handleList(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        ServerPlayer player = src.getPlayer();
        if (player == null) { src.sendFailure(Component.literal("Run as a player.")); return 0; }
        QuestSavedData store = QuestSavedData.get(level);
        var all = store.questsOf(player.getUUID());
        long completed = all.stream().filter(q -> q.status() == QuestStatus.COMPLETED).count();

        StringBuilder sb = new StringBuilder("§e=== Quests ===");
        var active = store.active(player.getUUID());
        if (active.isEmpty()) sb.append("\n  §7(no active quests — try /quest grant <god>)");
        int idx = 0;
        for (Quest q : active) {
            idx++;
            // The index doubles as the /quest abandon <index> handle.
            sb.append(String.format(Locale.ROOT, "%n§8%d) §6%s§7 [%s]",
                    idx, q.title(), q.giver().type()));
            for (Objective o : q.objectives()) {
                String tick = o.isComplete() ? "§a✓" : "§e…";
                sb.append(String.format(Locale.ROOT, "%n  %s §7%s", tick, o.describe()));
            }
        }
        sb.append(String.format(Locale.ROOT, "%n§8Completed: %d", completed));

        // F2a-3 — the player religious career: devotion rank per divine giver.
        var standings = store.standings(player.getUUID());
        if (!standings.isEmpty()) {
            sb.append("\n§e--- Devotion ---");
            standings.forEach((key, count) -> {
                String godId = key.startsWith("DIVINE:") ? key.substring("DIVINE:".length()) : key;
                String godName = tterrag1112.life_in_the_village.Npc.Religion.GodRegistry.find(godId)
                        .map(g -> g.displayName()).orElse(godId);
                var rank = tterrag1112.life_in_the_village.Quests.DevotionRank.fromCount(count);
                sb.append(String.format(Locale.ROOT, "%n  §6%s§7 — §d%s§7 (%d quest(s))",
                        godName, rank.displayName(), count));
            });
        }
        src.sendSuccess(() -> Component.literal(sb.toString())
                .withStyle(ChatFormatting.WHITE), false);
        return 1;
    }
}
