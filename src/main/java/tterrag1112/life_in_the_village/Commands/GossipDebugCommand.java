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
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Gossip.GossipChannel;
import tterrag1112.life_in_the_village.Npc.Gossip.GossipContext;
import tterrag1112.life_in_the_village.Npc.Gossip.GossipExchange;
import tterrag1112.life_in_the_village.Npc.Gossip.GossipScheduler;
import tterrag1112.life_in_the_village.Npc.Gossip.GossipTopicHeat;
import tterrag1112.life_in_the_village.Npc.Knowledge.KnowledgeCategory;
import tterrag1112.life_in_the_village.Npc.Knowledge.KnowledgeEntry;
import tterrag1112.life_in_the_village.Npc.Knowledge.KnowledgeSource;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code /gossip} debug commands for Phase 2 task 12. Spec lines 282-285
 * list four required subcommands; this class registers all four.
 */
public final class GossipDebugCommand {

    private GossipDebugCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("gossip")

                // ── /gossip list <village> ─────────────────────────────
                .then(Commands.literal("list")
                        .then(Commands.argument("village", StringArgumentType.greedyString())
                                .executes(GossipDebugCommand::handleList)))

                // ── /gossip trace <topic> ──────────────────────────────
                .then(Commands.literal("trace")
                        .then(Commands.argument("topic", StringArgumentType.greedyString())
                                .executes(GossipDebugCommand::handleTrace)))

                // ── /gossip seed <topic> <npc> [content] ───────────────
                .then(Commands.literal("seed")
                        .then(Commands.argument("topic", StringArgumentType.word())
                                .then(Commands.argument("npc", UuidArgument.uuid())
                                        .executes(GossipDebugCommand::handleSeedNoContent)
                                        .then(Commands.argument("content", StringArgumentType.greedyString())
                                                .executes(GossipDebugCommand::handleSeed)))))

                // ── /gossip force-exchange <speaker> <listener> [topic] ─
                .then(Commands.literal("force-exchange")
                        .then(Commands.argument("speaker", UuidArgument.uuid())
                                .then(Commands.argument("listener", UuidArgument.uuid())
                                        .executes(GossipDebugCommand::handleForceExchange))))

                // ── /gossip channels  (list active) ────────────────────
                .then(Commands.literal("channels")
                        .executes(GossipDebugCommand::handleChannels))

                // ── /gossip clear  (drop all active channels) ──────────
                .then(Commands.literal("clear")
                        .executes(ctx -> {
                            GossipScheduler.clearAll();
                            ctx.getSource().sendSuccess(
                                    () -> Component.literal("Cleared all gossip channels."), true);
                            return 1;
                        }))
        );
    }

    // =========================================================================
    // /gossip list <village>
    // =========================================================================

    private static int handleList(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        String name = StringArgumentType.getString(ctx, "village");
        VillageSavedData data = VillageSavedData.get(level);
        Optional<Village> village = data.getVillageByName(name);
        if (village.isEmpty()) {
            src.sendFailure(Component.literal("No village named '" + name + "'."));
            return 0;
        }
        GossipTopicHeat heat = data.getGossipHeat(village.get().getId()).orElse(null);
        if (heat == null || heat.isEmpty()) {
            src.sendSuccess(() -> Component.literal("No tracked gossip in '" + name + "'."), false);
            return 1;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("§e=== Hot topics in ").append(name).append(" ===\n");
        for (var e : heat.topN(10)) {
            sb.append(String.format(Locale.ROOT, "  %-40s %.2f%n",
                    truncate(e.getKey(), 40), e.getValue()));
        }
        src.sendSuccess(() -> Component.literal(sb.toString()).withStyle(ChatFormatting.WHITE), false);
        return 1;
    }

    // =========================================================================
    // /gossip trace <topic>
    // =========================================================================

    private static int handleTrace(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        String topic = StringArgumentType.getString(ctx, "topic");
        StringBuilder sb = new StringBuilder();
        sb.append("§e=== Trace for topic '").append(topic).append("' ===\n");
        int count = 0;
        for (var entity : level.getEntities().getAll()) {
            if (!(entity instanceof TownspersonMob mob)) continue;
            var entry = mob.getKnowledge().get(topic).orElse(null);
            if (entry == null) continue;
            count++;
            sb.append(String.format(Locale.ROOT,
                    "  %-22s fid=%.2f src=%s tick=%d%n    \"%s\"%n",
                    truncate(mob.getNpcName(), 22),
                    entry.fidelity(),
                    entry.source().name(),
                    entry.acquiredTick(),
                    truncate(entry.content(), 80)));
        }
        if (count == 0) {
            sb.append("  (no NPC carries this topic)\n");
        } else {
            sb.append("  ").append(count).append(" NPCs hold this topic.\n");
        }
        src.sendSuccess(() -> Component.literal(sb.toString()).withStyle(ChatFormatting.WHITE), false);
        return 1;
    }

    // =========================================================================
    // /gossip seed <topic> <npc> [content]
    // =========================================================================

    private static int handleSeedNoContent(CommandContext<CommandSourceStack> ctx) {
        return seedImpl(ctx, "Seeded for testing.");
    }

    private static int handleSeed(CommandContext<CommandSourceStack> ctx) {
        return seedImpl(ctx, StringArgumentType.getString(ctx, "content"));
    }

    private static int seedImpl(CommandContext<CommandSourceStack> ctx, String content) {
        CommandSourceStack src = ctx.getSource();
        String topic = StringArgumentType.getString(ctx, "topic");
        UUID npcId = UuidArgument.getUuid(ctx, "npc");
        TownspersonMob npc = TownspersonMob.findByUUID(src.getLevel(), npcId).orElse(null);
        if (npc == null) {
            src.sendFailure(Component.literal("No NPC with UUID " + npcId));
            return 0;
        }
        KnowledgeEntry entry = KnowledgeEntry.create(
                topic,
                KnowledgeCategory.PERSONAL,
                0.9f,
                KnowledgeSource.WITNESS,
                src.getLevel().getGameTime(),
                content,
                null);
        boolean added = npc.getKnowledge().add(entry);
        src.sendSuccess(() -> Component.literal(
                (added ? "Seeded " : "Skipped (existing higher fidelity) ")
                        + topic + " on " + npc.getNpcName()), true);
        return added ? 1 : 0;
    }

    // =========================================================================
    // /gossip force-exchange <speaker> <listener>
    // =========================================================================

    private static int handleForceExchange(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        UUID speakerId  = UuidArgument.getUuid(ctx, "speaker");
        UUID listenerId = UuidArgument.getUuid(ctx, "listener");
        TownspersonMob speaker  = TownspersonMob.findByUUID(level, speakerId).orElse(null);
        TownspersonMob listener = TownspersonMob.findByUUID(level, listenerId).orElse(null);
        if (speaker == null || listener == null) {
            src.sendFailure(Component.literal("Unknown speaker or listener."));
            return 0;
        }
        GossipExchange.ExchangeResult result =
                GossipExchange.run(level, speaker, listener, level.getGameTime());
        if (!result.transferred()) {
            src.sendSuccess(() -> Component.literal(
                    "No exchange (no shareable topic, or listener already knows it)."), false);
            return 0;
        }
        // Also register a synthetic channel so /gossip trace + ListenIn pick it up.
        var ch = GossipScheduler.channelFor(speakerId)
                .orElseGet(() -> GossipScheduler.startChannel(
                        speaker, listener, GossipContext.CASUAL_MEETING, level.getGameTime()));
        ch.recordExchange(level.getGameTime(), result.topic(), result.content());

        src.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "§a%s → %s§r topic=%s fid=%.2f%s%s%n  \"%s\"",
                speaker.getNpcName(), listener.getNpcName(),
                result.topic(), result.fidelity(),
                result.slantedNegative() ? " §c[neg-slant]§r" :
                        result.slantedPositive() ? " §a[pos-slant]§r" : "",
                result.embellished() ? " §6[embellished]§r" : "",
                result.content())), true);
        return 1;
    }

    // =========================================================================
    // /gossip channels
    // =========================================================================

    private static int handleChannels(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        Map<UUID, GossipChannel> active = GossipScheduler.activeChannels();
        if (active.isEmpty()) {
            src.sendSuccess(() -> Component.literal("No active gossip channels."), false);
            return 1;
        }
        StringBuilder sb = new StringBuilder("§e=== Active gossip channels ===\n");
        for (var ch : active.values()) {
            sb.append(String.format(Locale.ROOT,
                    "  speaker=%s listener=%s ctx=%s exchanges=%d%s%n",
                    ch.speakerId(), ch.listenerId(), ch.context().name(),
                    ch.exchangeCount(), ch.interrupted() ? " [interrupted]" : ""));
        }
        src.sendSuccess(() -> Component.literal(sb.toString()).withStyle(ChatFormatting.WHITE), false);
        return 1;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }
}
