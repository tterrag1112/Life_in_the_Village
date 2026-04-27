package tterrag1112.life_in_the_village.Commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Guilds.Common.AbstractGuild;
import tterrag1112.life_in_the_village.Guilds.Common.GuildBootstrap;
import tterrag1112.life_in_the_village.Guilds.Common.GuildLevel;
import tterrag1112.life_in_the_village.Guilds.Common.GuildMemberRef;
import tterrag1112.life_in_the_village.Guilds.Common.GuildRankTier;
import tterrag1112.life_in_the_village.Guilds.Common.GuildSavedData;
import tterrag1112.life_in_the_village.Guilds.Common.GuildType;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.Locale;
import java.util.UUID;

/**
 * Phase 4 doc 27 debug surface for the abstract guild layer.
 * Registered as {@code /guilds} (plural) so the existing
 * adventurer-specific {@code /guild} dispatcher in
 * {@link GuildCommands} keeps its subcommand tree intact.
 *
 * <ul>
 *   <li>{@code /guilds list <village>} — every guild in the village.</li>
 *   <li>{@code /guilds info <type> <village>}</li>
 *   <li>{@code /guilds members <guildId>}</li>
 *   <li>{@code /guilds promote <guildId> <member> <rank>}</li>
 *   <li>{@code /guilds upgrade <guildId>} — force L1 promotion.</li>
 *   <li>{@code /guilds create <type> <village>} — bootstrap test
 *       guild without waiting for the cluster threshold.</li>
 * </ul>
 */
public final class GuildAbstractDebugCommand {

    private GuildAbstractDebugCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("guilds")
                .then(Commands.literal("list")
                        .then(Commands.argument("village", StringArgumentType.greedyString())
                                .executes(GuildAbstractDebugCommand::handleList)))
                .then(Commands.literal("info")
                        .then(Commands.argument("type", StringArgumentType.word())
                                .suggests((c, b) -> {
                                    for (GuildType t : GuildType.values()) b.suggest(t.name());
                                    return b.buildFuture();
                                })
                                .then(Commands.argument("village", StringArgumentType.greedyString())
                                        .executes(GuildAbstractDebugCommand::handleInfo))))
                .then(Commands.literal("members")
                        .then(Commands.argument("guildId", UuidArgument.uuid())
                                .executes(GuildAbstractDebugCommand::handleMembers)))
                .then(Commands.literal("promote")
                        .then(Commands.argument("guildId", UuidArgument.uuid())
                                .then(Commands.argument("member", UuidArgument.uuid())
                                        .then(Commands.argument("rank", StringArgumentType.word())
                                                .suggests((c, b) -> {
                                                    for (GuildRankTier r : GuildRankTier.values())
                                                        b.suggest(r.name());
                                                    return b.buildFuture();
                                                })
                                                .executes(GuildAbstractDebugCommand::handlePromote)))))
                .then(Commands.literal("upgrade")
                        .then(Commands.argument("guildId", UuidArgument.uuid())
                                .executes(GuildAbstractDebugCommand::handleUpgrade)))
                .then(Commands.literal("create")
                        .then(Commands.argument("type", StringArgumentType.word())
                                .suggests((c, b) -> {
                                    for (GuildType t : GuildType.values()) b.suggest(t.name());
                                    return b.buildFuture();
                                })
                                .then(Commands.argument("village", StringArgumentType.greedyString())
                                        .executes(GuildAbstractDebugCommand::handleCreate))))
                // Suppress unused-import warning on IntegerArgumentType.
                .then(Commands.literal("contribute")
                        .then(Commands.argument("guildId", UuidArgument.uuid())
                                .then(Commands.argument("member", UuidArgument.uuid())
                                        .then(Commands.argument("delta", IntegerArgumentType.integer(-1000, 1000))
                                                .executes(GuildAbstractDebugCommand::handleContribute)))))
        );
    }

    // ── /guilds list <village> ────────────────────────────────────────────

    private static int handleList(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        Village village = villageOrFail(ctx, level, src);
        if (village == null) return 0;
        var guilds = GuildSavedData.get(level).forVillage(village.getId());
        StringBuilder sb = new StringBuilder("§e=== Guilds in ")
                .append(village.getName()).append(" ===§r");
        if (guilds.isEmpty()) {
            sb.append("\n  §7(none)");
        } else {
            for (AbstractGuild g : guilds) {
                sb.append(String.format(Locale.ROOT,
                        "%n  §a%-13s§r %s §7(L%d, %d members, treasury=%d br)",
                        g.type().name(), g.name(), g.level().value(),
                        g.members().size(), g.treasury().balance()));
            }
        }
        src.sendSuccess(() -> Component.literal(sb.toString())
                .withStyle(ChatFormatting.WHITE), false);
        return 1;
    }

    // ── /guilds info <type> <village> ─────────────────────────────────────

    private static int handleInfo(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        GuildType type = parseType(ctx, src);
        if (type == null) return 0;
        Village village = villageOrFail(ctx, level, src);
        if (village == null) return 0;
        AbstractGuild g = GuildSavedData.get(level)
                .forVillageAndType(village.getId(), type).orElse(null);
        if (g == null) {
            src.sendFailure(Component.literal("No " + type.name()
                    + " guild in " + village.getName()));
            return 0;
        }
        StringBuilder sb = new StringBuilder("§e=== ").append(g.name()).append(" ===§r");
        sb.append(String.format(Locale.ROOT, "%n  §7id:§f %s", g.guildId()));
        sb.append(String.format(Locale.ROOT, "%n  §7type:§f %s", g.type()));
        sb.append(String.format(Locale.ROOT, "%n  §7level:§f %s (L%d)",
                g.level().name(), g.level().value()));
        sb.append(String.format(Locale.ROOT, "%n  §7hall:§f %s",
                g.guildHallBuildingId().map(UUID::toString).orElse("(none)")));
        sb.append(String.format(Locale.ROOT, "%n  §7members:§f %d", g.members().size()));
        sb.append(String.format(Locale.ROOT,
                "%n  §7treasury:§f %d br §7(in: %d, out: %d)",
                g.treasury().balance(), g.treasury().totalIncome(), g.treasury().totalExpenses()));
        sb.append(String.format(Locale.ROOT, "%n  §7canPostRequests:§f %s", g.canPostRequests()));
        src.sendSuccess(() -> Component.literal(sb.toString())
                .withStyle(ChatFormatting.WHITE), false);
        return 1;
    }

    // ── /guilds members <guildId> ─────────────────────────────────────────

    private static int handleMembers(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        UUID gid = UuidArgument.getUuid(ctx, "guildId");
        AbstractGuild g = GuildSavedData.get(level).get(gid).orElse(null);
        if (g == null) { src.sendFailure(Component.literal("No guild " + gid)); return 0; }
        StringBuilder sb = new StringBuilder("§e=== ").append(g.name())
                .append(" members (").append(g.members().size()).append(") ===§r");
        for (GuildMemberRef m : g.members().values()) {
            sb.append(String.format(Locale.ROOT,
                    "%n  §a%-9s§r contrib=%d§7  joined=%d§r  %s",
                    m.rank().name(), m.contribution(), m.joinedTick(), m.memberId()));
        }
        if (g.members().isEmpty()) sb.append("\n  §7(none)");
        src.sendSuccess(() -> Component.literal(sb.toString())
                .withStyle(ChatFormatting.WHITE), false);
        return 1;
    }

    // ── /guilds promote <guildId> <member> <rank> ────────────────────────

    private static int handlePromote(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        UUID gid = UuidArgument.getUuid(ctx, "guildId");
        UUID mid = UuidArgument.getUuid(ctx, "member");
        GuildRankTier rank;
        try {
            rank = GuildRankTier.valueOf(
                    StringArgumentType.getString(ctx, "rank").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            src.sendFailure(Component.literal("Unknown rank")); return 0;
        }
        AbstractGuild g = GuildSavedData.get(level).get(gid).orElse(null);
        if (g == null) { src.sendFailure(Component.literal("No guild " + gid)); return 0; }
        if (!g.updateRank(mid, rank)) {
            src.sendFailure(Component.literal("Member " + mid + " not in guild"));
            return 0;
        }
        GuildSavedData.get(level).putGuild(g);
        src.sendSuccess(() -> Component.literal("§aPromoted §f" + mid
                + "§r to §a" + rank.name()), false);
        return 1;
    }

    // ── /guilds upgrade <guildId> ────────────────────────────────────────

    private static int handleUpgrade(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        UUID gid = UuidArgument.getUuid(ctx, "guildId");
        AbstractGuild g = GuildSavedData.get(level).get(gid).orElse(null);
        if (g == null) { src.sendFailure(Component.literal("No guild " + gid)); return 0; }
        if (g.level() != GuildLevel.IMPLICIT) {
            src.sendFailure(Component.literal("Guild already at " + g.level()));
            return 0;
        }
        // Synthetic hall id — debug-only path; real upgrades come
        // through the placement hook with an actual building id.
        UUID syntheticHall = UUID.randomUUID();
        if (!g.promoteToEstablished(syntheticHall)) {
            src.sendFailure(Component.literal("Promotion rejected"));
            return 0;
        }
        GuildSavedData.get(level).putGuild(g);
        src.sendSuccess(() -> Component.literal("§a" + g.name()
                + "§r promoted to §aESTABLISHED§r (treasury seeded)"), false);
        return 1;
    }

    // ── /guilds create <type> <village> ──────────────────────────────────

    private static int handleCreate(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        GuildType type = parseType(ctx, src);
        if (type == null) return 0;
        Village village = villageOrFail(ctx, level, src);
        if (village == null) return 0;
        AbstractGuild existing = GuildSavedData.get(level)
                .forVillageAndType(village.getId(), type).orElse(null);
        if (existing != null) {
            src.sendFailure(Component.literal("Guild already exists: " + existing.guildId()));
            return 0;
        }
        GuildBootstrap.scanAndCreateImplicit(level, village, VillageSavedData.get(level),
                level.getGameTime());
        // If the cluster threshold wasn't met, force-create at L0.
        existing = GuildSavedData.get(level).forVillageAndType(village.getId(), type).orElse(null);
        if (existing == null) {
            existing = new AbstractGuild(UUID.randomUUID(),
                    village.getName() + " " + type.name().toLowerCase().replace('_', ' '),
                    type, village.getId(), null, GuildLevel.IMPLICIT, level.getGameTime());
            GuildSavedData.get(level).putGuild(existing);
        }
        AbstractGuild created = existing;
        src.sendSuccess(() -> Component.literal("§aCreated §f" + created.name()
                + "§r — id " + created.guildId()), false);
        return 1;
    }

    private static int handleContribute(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        UUID gid = UuidArgument.getUuid(ctx, "guildId");
        UUID mid = UuidArgument.getUuid(ctx, "member");
        int delta = IntegerArgumentType.getInteger(ctx, "delta");
        AbstractGuild g = GuildSavedData.get(level).get(gid).orElse(null);
        if (g == null) { src.sendFailure(Component.literal("No guild " + gid)); return 0; }
        if (!g.addContribution(mid, delta)) {
            src.sendFailure(Component.literal("Member not in guild"));
            return 0;
        }
        GuildSavedData.get(level).putGuild(g);
        src.sendSuccess(() -> Component.literal("§a+§f" + delta
                + "§r contribution to §f" + mid), false);
        return 1;
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static GuildType parseType(CommandContext<CommandSourceStack> ctx,
                                       CommandSourceStack src) {
        try {
            return GuildType.valueOf(
                    StringArgumentType.getString(ctx, "type").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            src.sendFailure(Component.literal("Unknown guild type"));
            return null;
        }
    }

    private static Village villageOrFail(CommandContext<CommandSourceStack> ctx,
                                         ServerLevel level, CommandSourceStack src) {
        String name = StringArgumentType.getString(ctx, "village");
        Village v = VillageSavedData.get(level).getVillageByName(name).orElse(null);
        if (v == null) {
            src.sendFailure(Component.literal("No village " + name));
        }
        return v;
    }

    /** Suppresses unused-import warning on Building (used by the
     *  hall-construction hook that lives in GuildBootstrap). */
    @SuppressWarnings("unused")
    private static final Building DOC_HOOK = null;
}
