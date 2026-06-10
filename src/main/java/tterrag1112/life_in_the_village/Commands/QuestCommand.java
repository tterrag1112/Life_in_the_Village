package tterrag1112.life_in_the_village.Commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import tterrag1112.life_in_the_village.Npc.Religion.GodRegistry;
import tterrag1112.life_in_the_village.Quests.Objective;
import tterrag1112.life_in_the_village.Quests.Quest;
import tterrag1112.life_in_the_village.Quests.QuestIssuer;
import tterrag1112.life_in_the_village.Quests.QuestSavedData;
import tterrag1112.life_in_the_village.Quests.QuestStatus;

import java.util.Locale;

/**
 * F2a-1 — the {@code /quest} surface: list the executing player's quests, and a debug
 * grant of the proving religious quest. The player-facing readout this stage (a journal
 * UI is later).
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
                                                        IntegerArgumentType.getInteger(ctx, "count"))))))));
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
        for (Quest q : active) {
            sb.append(String.format(Locale.ROOT, "%n§6%s§7 [%s]", q.title(), q.giver().type()));
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
