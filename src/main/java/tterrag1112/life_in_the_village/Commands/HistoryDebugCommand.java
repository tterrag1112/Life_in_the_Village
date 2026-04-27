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
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.History.HistoryEntry;
import tterrag1112.life_in_the_village.Village.History.HistoryEventType;
import tterrag1112.life_in_the_village.Village.History.HistoryImportance;
import tterrag1112.life_in_the_village.Village.History.NotablePerson;
import tterrag1112.life_in_the_village.Village.History.NotablePersonRegistry;
import tterrag1112.life_in_the_village.Village.History.VillageHistoryLog;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Phase 4 doc 30 debug surface.
 *
 * <ul>
 *   <li>{@code /history list <village> [importance]} — every entry,
 *       optionally filtered to {@code >=} importance.</li>
 *   <li>{@code /history recent <village> <count>} — last N entries.</li>
 *   <li>{@code /history legendary <village>} — LEGENDARY entries only.</li>
 *   <li>{@code /history add <village> <type> <summary>} — debug entry
 *       seeded with importance from the type's default. The summary
 *       string is stored in the {@code summary} key for any template
 *       that uses it.</li>
 *   <li>{@code /history prune <village>} — force eviction pass.</li>
 *   <li>{@code /history notable [count]} — kingdom-wide notable
 *       persons sorted by peak tick.</li>
 * </ul>
 */
public final class HistoryDebugCommand {

    private HistoryDebugCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("history")
                .then(Commands.literal("list")
                        .then(Commands.argument("village", StringArgumentType.string())
                                .executes(c -> handleList(c, null))
                                .then(Commands.argument("importance", StringArgumentType.word())
                                        .suggests((c, b) -> {
                                            for (HistoryImportance i : HistoryImportance.values())
                                                b.suggest(i.name());
                                            return b.buildFuture();
                                        })
                                        .executes(c -> handleList(c, parseImportance(c))))))
                .then(Commands.literal("recent")
                        .then(Commands.argument("village", StringArgumentType.string())
                                .then(Commands.argument("count",
                                                IntegerArgumentType.integer(1, 200))
                                        .executes(HistoryDebugCommand::handleRecent))))
                .then(Commands.literal("legendary")
                        .then(Commands.argument("village", StringArgumentType.greedyString())
                                .executes(HistoryDebugCommand::handleLegendary)))
                .then(Commands.literal("add")
                        .then(Commands.argument("village", StringArgumentType.string())
                                .then(Commands.argument("type", StringArgumentType.word())
                                        .suggests((c, b) -> {
                                            for (HistoryEventType t : HistoryEventType.values())
                                                b.suggest(t.name());
                                            return b.buildFuture();
                                        })
                                        .then(Commands.argument("summary",
                                                        StringArgumentType.greedyString())
                                                .executes(HistoryDebugCommand::handleAdd)))))
                .then(Commands.literal("prune")
                        .then(Commands.argument("village", StringArgumentType.greedyString())
                                .executes(HistoryDebugCommand::handlePrune)))
                .then(Commands.literal("notable")
                        .executes(c -> handleNotable(c, 25))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 200))
                                .executes(c -> handleNotable(c,
                                        IntegerArgumentType.getInteger(c, "count")))))
        );
    }

    // ── list / recent / legendary ─────────────────────────────────────────

    private static int handleList(CommandContext<CommandSourceStack> ctx,
                                  HistoryImportance min) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        Village v = villageOrFail(ctx, level, src);
        if (v == null) return 0;
        List<HistoryEntry> entries = min == null
                ? VillageHistoryLog.get(level).all(v.getId())
                : VillageHistoryLog.get(level).byImportance(v.getId(), min);
        return printEntries(src, v, entries,
                "list" + (min == null ? "" : " (>=" + min.name() + ")"));
    }

    private static int handleRecent(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        Village v = villageOrFail(ctx, level, src);
        if (v == null) return 0;
        int count = IntegerArgumentType.getInteger(ctx, "count");
        return printEntries(src, v, VillageHistoryLog.get(level).recent(v.getId(), count),
                "recent " + count);
    }

    private static int handleLegendary(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        Village v = villageOrFail(ctx, level, src);
        if (v == null) return 0;
        return printEntries(src, v,
                VillageHistoryLog.get(level).byImportance(v.getId(), HistoryImportance.LEGENDARY),
                "legendary");
    }

    private static int printEntries(CommandSourceStack src, Village v,
                                    List<HistoryEntry> entries, String label) {
        StringBuilder sb = new StringBuilder("§e=== History (")
                .append(label).append(") of ").append(v.getName())
                .append(" — ").append(entries.size()).append(" entries ===§r");
        if (entries.isEmpty()) sb.append("\n  §7(none)");
        for (HistoryEntry e : entries) {
            sb.append(String.format(Locale.ROOT,
                    "%n  §7[%d]§r §a%-22s§r §8%s§r %s",
                    e.tick(), e.type().name(),
                    e.importance().name().substring(0, 1),
                    e.renderedSummary()));
        }
        src.sendSuccess(() -> Component.literal(sb.toString())
                .withStyle(ChatFormatting.WHITE), false);
        return 1;
    }

    // ── add ──────────────────────────────────────────────────────────────

    private static int handleAdd(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        Village v = villageOrFail(ctx, level, src);
        if (v == null) return 0;
        HistoryEventType type;
        try {
            type = HistoryEventType.valueOf(
                    StringArgumentType.getString(ctx, "type").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            src.sendFailure(Component.literal("Unknown event type"));
            return 0;
        }
        String summary = StringArgumentType.getString(ctx, "summary");
        Map<String, String> details = Map.of(
                "village_name", v.getName(),
                "summary", summary);
        boolean added = VillageHistoryLog.get(level)
                .record(type, v.getId(), level.getGameTime(), details, List.of());
        if (!added) {
            src.sendFailure(Component.literal("Add rejected (duplicate of recent entry)"));
            return 0;
        }
        src.sendSuccess(() -> Component.literal("§aAdded§r " + type.name()
                + " to " + v.getName()), false);
        return 1;
    }

    // ── prune ────────────────────────────────────────────────────────────

    private static int handlePrune(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        Village v = villageOrFail(ctx, level, src);
        if (v == null) return 0;
        int removed = VillageHistoryLog.get(level)
                .pruneVillage(v.getId(), level.getGameTime());
        src.sendSuccess(() -> Component.literal("§aPruned§r " + removed
                + " entries from " + v.getName()), false);
        return 1;
    }

    // ── notable ──────────────────────────────────────────────────────────

    private static int handleNotable(CommandContext<CommandSourceStack> ctx, int count) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        List<NotablePerson> people = NotablePersonRegistry.get(level).mostRecent(count);
        StringBuilder sb = new StringBuilder("§e=== Notable persons (")
                .append(people.size()).append(") ===§r");
        if (people.isEmpty()) sb.append("\n  §7(none)");
        for (NotablePerson p : people) {
            sb.append(String.format(Locale.ROOT,
                    "%n  §a%s§r §7(%s)§r — peak tick %d, %d achievements",
                    p.npcName(), p.notabilityReason(),
                    p.peakAchievementTick(), p.achievements().size()));
        }
        src.sendSuccess(() -> Component.literal(sb.toString())
                .withStyle(ChatFormatting.WHITE), false);
        return 1;
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static HistoryImportance parseImportance(CommandContext<CommandSourceStack> ctx) {
        try {
            return HistoryImportance.valueOf(
                    StringArgumentType.getString(ctx, "importance").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Village villageOrFail(CommandContext<CommandSourceStack> ctx,
                                         ServerLevel level, CommandSourceStack src) {
        String name = StringArgumentType.getString(ctx, "village");
        Optional<Village> v = VillageSavedData.get(level).getVillageByName(name);
        if (v.isEmpty()) {
            src.sendFailure(Component.literal("No village " + name));
            return null;
        }
        return v.get();
    }
}
