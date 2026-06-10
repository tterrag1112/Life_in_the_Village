package tterrag1112.life_in_the_village.Commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
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
import tterrag1112.life_in_the_village.Npc.Religion.Religion;
import tterrag1112.life_in_the_village.Npc.Religion.ReligionRegistry;
import tterrag1112.life_in_the_village.Npc.Religion.Religions;
import tterrag1112.life_in_the_village.Npc.Religion.Rite;
import tterrag1112.life_in_the_village.Npc.Religion.RiteScheduler;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.List;
import java.util.Locale;

/**
 * {@code /religion} debug subcommands per spec line 224.
 */
public final class ReligionDebugCommand {

    private ReligionDebugCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("religion")
                .then(Commands.literal("list")
                        .executes(ReligionDebugCommand::handleList))
                .then(Commands.literal("set")
                        .then(Commands.argument("npc", UuidArgument.uuid())
                                .then(Commands.argument("religionId", StringArgumentType.word())
                                        .suggests((c, b) -> {
                                            for (Religion r : Religions.all(c.getSource().getLevel())) b.suggest(r.id());
                                            return b.buildFuture();
                                        })
                                        .then(Commands.argument("strength", FloatArgumentType.floatArg(0f, 1f))
                                                .executes(ReligionDebugCommand::handleSet)))))
                .then(Commands.literal("rite")
                        .then(Commands.argument("type", StringArgumentType.word())
                                .suggests((c, b) -> {
                                    for (Rite r : Rite.values()) b.suggest(r.name());
                                    return b.buildFuture();
                                })
                                .then(Commands.argument("participant", UuidArgument.uuid())
                                        .executes(ReligionDebugCommand::handleRite))))
                .then(Commands.literal("calendar")
                        .then(Commands.argument("village", StringArgumentType.string())
                                .executes(ReligionDebugCommand::handleCalendar)))
                .then(Commands.literal("tithe")
                        .then(Commands.argument("player", UuidArgument.uuid())
                                .then(Commands.argument("amount", FloatArgumentType.floatArg(0f, 1f))
                                        .executes(ReligionDebugCommand::handleTithe))))
                // R3e-2 — override the nearest shrine's patron faith and re-fix
                // its seated priest's belief + order.
                .then(Commands.literal("shrine")
                        .then(Commands.argument("religionId", StringArgumentType.word())
                                .suggests((c, b) -> {
                                    for (Religion r : Religions.all(c.getSource().getLevel())) b.suggest(r.id());
                                    return b.buildFuture();
                                })
                                .executes(ReligionDebugCommand::handleShrine)))
                // R3e-3b-1 — send a realized resident on a pilgrimage to a
                // route-connected destination village.
                .then(Commands.literal("pilgrimage")
                        .then(Commands.argument("npc", UuidArgument.uuid())
                                .then(Commands.argument("destVillage", StringArgumentType.string())
                                        .executes(ReligionDebugCommand::handlePilgrimage))))
                // R5a — manually create a graveyard district at the executor's
                // position (rows × cols grave slots). No auto-layout this phase.
                .then(Commands.literal("graveyard")
                        .executes(ctx -> handleGraveyard(ctx, 4, 4))
                        .then(Commands.argument("rows",
                                        com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 16))
                                .then(Commands.argument("cols",
                                                com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 16))
                                        .executes(ctx -> handleGraveyard(ctx,
                                                com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "rows"),
                                                com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "cols"))))))
                // R9b — open the read-only temple screen for the nearest
                // religious building in the village at the executor's position.
                .then(Commands.literal("temple")
                        .executes(ReligionDebugCommand::handleTemple))
                // R9c — open the read-only player-religion + calendar screen.
                .then(Commands.literal("me")
                        .executes(ReligionDebugCommand::handleMe))
                // D1 — print a faith's realized-culture identity (cosmology, deity,
                // history, virtues, taboos, aesthetics, practices).
                .then(Commands.literal("identity")
                        .then(Commands.argument("religionId", StringArgumentType.word())
                                .suggests((c, b) -> {
                                    for (Religion r : Religions.all(c.getSource().getLevel())) b.suggest(r.id());
                                    return b.buildFuture();
                                })
                                .executes(ReligionDebugCommand::handleIdentity)))

                // F1a 3a/3b — /religion favour [view] | grant <god> <amt> | spend
                // <god> <amt> | offend <god> <amt>  (grant/spend/offend target a GOD id).
                .then(Commands.literal("favour")
                        .executes(ReligionDebugCommand::handleFavourView)
                        .then(Commands.literal("view").executes(ReligionDebugCommand::handleFavourView))
                        .then(Commands.literal("grant")
                                .then(Commands.argument("religionId", StringArgumentType.word())
                                        .then(Commands.argument("amount", FloatArgumentType.floatArg(0f, 100f))
                                                .executes(ctx -> handleFavourGrantSpend(ctx, true)))))
                        .then(Commands.literal("spend")
                                .then(Commands.argument("religionId", StringArgumentType.word())
                                        .then(Commands.argument("amount", FloatArgumentType.floatArg(0f, 100f))
                                                .executes(ctx -> handleFavourGrantSpend(ctx, false)))))
                        .then(Commands.literal("offend")
                                .then(Commands.argument("religionId", StringArgumentType.word())
                                        .then(Commands.argument("amount", FloatArgumentType.floatArg(0f, 200f))
                                                .executes(ReligionDebugCommand::handleFavourOffend)))))

                // Divine Layer V2 — /religion miracle list | cast <id>  (executing player).
                .then(Commands.literal("miracle")
                        .executes(ReligionDebugCommand::handleMiracleList)
                        .then(Commands.literal("list").executes(ReligionDebugCommand::handleMiracleList))
                        .then(Commands.literal("cast")
                                .then(Commands.argument("miracleId", StringArgumentType.word())
                                        .suggests((c, b) -> {
                                            for (var m : tterrag1112.life_in_the_village.Npc.Religion
                                                    .Miracles.all()) b.suggest(m.id());
                                            return b.buildFuture();
                                        })
                                        .executes(ReligionDebugCommand::handleMiracleCast))))

                // Divine Layer V4 — /religion sacrilege <faith> <amount>  (drive
                // displeasure for testing curses/wrath without committing a crime).
                .then(Commands.literal("sacrilege")
                        .then(Commands.argument("religionId", StringArgumentType.word())
                                .then(Commands.argument("amount", FloatArgumentType.floatArg(0f, 200f))
                                        .executes(ReligionDebugCommand::handleSacrilege))))

                // F1a 3b — /religion theophany favour|wrath <god>  (force-fire a named
                // god's manifestation for the executing player, for testing).
                .then(Commands.literal("theophany")
                        .then(Commands.literal("favour")
                                .then(Commands.argument("godId", StringArgumentType.word())
                                        .executes(ctx -> handleTheophany(ctx, false))))
                        .then(Commands.literal("wrath")
                                .then(Commands.argument("godId", StringArgumentType.word())
                                        .executes(ctx -> handleTheophany(ctx, true)))))

                // F1a — list the canonical gods (the new God/GodRegistry scaffolding;
                // the only consumer this stage). Read-only.
                .then(Commands.literal("gods")
                        .executes(ReligionDebugCommand::handleGods))

                // F1b 1a — list the PER-WORLD religion store (the new
                // ReligionSavedData scaffolding; the only consumer this stage).
                // Distinguish it from the static template list above. Read-only.
                .then(Commands.literal("world")
                        .then(Commands.literal("list")
                                .executes(ReligionDebugCommand::handleWorldList)))

                // F1b 2 — the interreligious stance matrix across the per-world
                // religions (each pair + KINDRED/NEUTRAL, marking any override).
                .then(Commands.literal("relations")
                        .executes(ReligionDebugCommand::handleRelations))
        );
    }

    // ── /religion gods (F1a) ─────────────────────────────────────────────────

    private static int handleGods(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        StringBuilder sb = new StringBuilder("§e=== Gods ===");
        for (var g : tterrag1112.life_in_the_village.Npc.Religion.GodRegistry.all()) {
            sb.append(String.format(Locale.ROOT,
                    "%n§6%-14s§7 %s §8[%s]§7 — %d virtues, %d taboos",
                    g.id(),
                    g.isImpersonal() ? "(impersonal)" : g.name().orElse(g.displayName()),
                    g.domain(), g.virtues().size(), g.taboos().size()));
            sb.append("\n  §7demands: §f").append(g.demands());
        }
        // F1a sub-stage 2 — the religion → god(s) link (verifiable in-world).
        sb.append("\n§e--- Religion → god(s) ---");
        for (Religion r : Religions.all(level)) {
            String primary = tterrag1112.life_in_the_village.Npc.Religion.GodRegistry
                    .primaryGod(r).map(g -> g.isImpersonal() ? "(impersonal)" : g.displayName())
                    .orElse("(none)");
            sb.append(String.format(Locale.ROOT, "%n§6%-12s§7 venerates §f%s§7 (primary: §f%s§7)",
                    r.id(), String.join(", ", r.godIds()), primary));
        }
        src.sendSuccess(() -> Component.literal(sb.toString())
                .withStyle(ChatFormatting.WHITE), false);
        return 1;
    }

    // ── /religion world list (F1b 1a) ───────────────────────────────────────

    /** Lists the PER-WORLD religion store (the seeded {@code ReligionSavedData}),
     *  distinct from the static {@code /religion list}: id + displayName + godIds.
     *  Verifies the store is seeded and persists across reload. Read-only. */
    private static int handleWorldList(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        var store = tterrag1112.life_in_the_village.Npc.Religion.Religions.all(level);
        StringBuilder sb = new StringBuilder("§e=== Per-world religions (" + store.size() + ") ===");
        for (Religion r : store) {
            sb.append(String.format(Locale.ROOT, "%n§6%-12s§7 \"%s\" §8venerates§7 [%s]",
                    r.id(), r.displayName(), String.join(", ", r.godIds())));
        }
        sb.append("\n§8(per-world store — seeded from the static templates; ")
                .append("F1b-1b migrates callers off the static registry)");
        src.sendSuccess(() -> Component.literal(sb.toString())
                .withStyle(ChatFormatting.WHITE), false);
        return 1;
    }

    // ── /religion relations (F1b 2) ─────────────────────────────────────────

    /** Prints the interreligious stance matrix across the per-world religions: each
     *  distinct pair + its stance (KINDRED/NEUTRAL derived from god overlap, or an
     *  explicit override), flagging which entries are overridden. Read-only. */
    private static int handleRelations(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        var store = new java.util.ArrayList<>(
                tterrag1112.life_in_the_village.Npc.Religion.Religions.all(level));
        var data = tterrag1112.life_in_the_village.Npc.Religion.ReligionSavedData.get(level);
        StringBuilder sb = new StringBuilder("§e=== Interreligious relations ===");
        for (int a = 0; a < store.size(); a++) {
            for (int b = a + 1; b < store.size(); b++) {
                String idA = store.get(a).id();
                String idB = store.get(b).id();
                var stance = tterrag1112.life_in_the_village.Npc.Religion.Relations
                        .relation(level, idA, idB);
                boolean overridden = data.relationOverride(idA, idB).isPresent();
                String colour = stance == tterrag1112.life_in_the_village.Npc.Religion
                        .RelationStance.KINDRED ? "§a"
                        : stance == tterrag1112.life_in_the_village.Npc.Religion
                                .RelationStance.NEUTRAL ? "§7" : "§c";
                sb.append(String.format(Locale.ROOT, "%n§6%-12s §7↔ §6%-12s %s%s%s",
                        idA, idB, colour, stance,
                        overridden ? " §8(override)" : ""));
            }
        }
        sb.append("\n§8(KINDRED = shares ≥1 god; NEUTRAL = disjoint; RIVAL/HERETICAL "
                + "are override-only — no writers yet)");
        src.sendSuccess(() -> Component.literal(sb.toString())
                .withStyle(ChatFormatting.WHITE), false);
        return 1;
    }

    private static int handleTheophany(CommandContext<CommandSourceStack> ctx, boolean wrath) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        var player = src.getPlayer();
        if (player == null) { src.sendFailure(Component.literal("Run as a player.")); return 0; }
        // F1a 3b — theophany targets a named GOD now.
        String gid = StringArgumentType.getString(ctx, "godId");
        var god = tterrag1112.life_in_the_village.Npc.Religion.GodRegistry.get(gid);
        if (god == null) {
            src.sendFailure(Component.literal("Unknown god " + gid
                    + " (try sun_mother / the_pattern / sea_mother / forge_father)"));
            return 0;
        }
        long now = level.getGameTime();
        if (wrath) {
            tterrag1112.life_in_the_village.Npc.Religion.DivineTheophany
                    .fireWrath(level, player, god, now);
        } else {
            tterrag1112.life_in_the_village.Npc.Religion.DivineTheophany
                    .fireFavour(level, player, god, now);
        }
        return 1;
    }

    private static int handleFavourOffend(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        var player = src.getPlayer();
        if (player == null) { src.sendFailure(Component.literal("Run as a player.")); return 0; }
        // F1a 3b — drive a NAMED god's displeasure (for per-god curse/wrath testing).
        String gid = StringArgumentType.getString(ctx, "religionId");
        if (tterrag1112.life_in_the_village.Npc.Religion.GodRegistry.get(gid) == null) {
            src.sendFailure(Component.literal("Unknown god " + gid));
            return 0;
        }
        float amount = FloatArgumentType.getFloat(ctx, "amount");
        long now = level.getGameTime();
        tterrag1112.life_in_the_village.Npc.Religion.DivineFavour
                .offend(level, player.getUUID(), gid, amount, now);
        float fav = tterrag1112.life_in_the_village.Npc.Religion.DivineFavour
                .current(level, player.getUUID(), gid, now);
        src.sendSuccess(() -> Component.literal(
                "§cOffended§7 god " + gid + " — favour now §f" + Math.round(fav)), false);
        return 1;
    }

    private static int handleSacrilege(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        var player = src.getPlayer();
        if (player == null) { src.sendFailure(Component.literal("Run as a player.")); return 0; }
        String fid = StringArgumentType.getString(ctx, "religionId");
        if (Religions.get(level, fid) == null) {
            src.sendFailure(Component.literal("Unknown religion " + fid));
            return 0;
        }
        float amount = FloatArgumentType.getFloat(ctx, "amount");
        long now = level.getGameTime();
        // Sacrilege is religion-relative (against your faith); fan out to its god(s).
        tterrag1112.life_in_the_village.Npc.Religion.DivineFavour
                .offendForReligion(level, player.getUUID(), fid, amount, now);
        float fav = tterrag1112.life_in_the_village.Npc.Religion.DivineFavour
                .currentForReligion(level, player.getUUID(), fid, now);
        src.sendSuccess(() -> Component.literal(
                "§cSacrilege§7 against " + fid + " — favour now §f" + Math.round(fav)), false);
        return 1;
    }

    // ── /religion miracle ────────────────────────────────────────────────────

    private static int handleMiracleList(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        var player = src.getPlayer();
        if (player == null) { src.sendFailure(Component.literal("Run as a player.")); return 0; }
        long now = level.getGameTime();

        // F1a 3b — miracles are per GOD now: the union over the player's gods (or all
        // gods if unaffiliated), each god's miracles selected by its domain.
        var gods = tterrag1112.life_in_the_village.Npc.Religion.GodRegistry
                .playerGods(level, player.getUUID());
        if (gods.isEmpty()) gods = tterrag1112.life_in_the_village.Npc.Religion.GodRegistry.all();
        StringBuilder sb = new StringBuilder("§e=== Miracles (per god) ===");
        for (var god : gods) {
            var set = tterrag1112.life_in_the_village.Npc.Religion.Miracles.forDomain(god.domain());
            if (set.isEmpty()) continue;
            float fav = tterrag1112.life_in_the_village.Npc.Religion.DivineFavour
                    .current(level, player.getUUID(), god.id(), now);
            sb.append(String.format(Locale.ROOT, "%n§6%s§8(%s)§7 (favour %.0f):",
                    god.displayName(), god.id(), fav));
            for (var m : set) {
                var st = tterrag1112.life_in_the_village.Npc.Religion.MiracleInvoker
                        .status(level, player, m, now);
                String tag = switch (st) {
                    case AVAILABLE     -> "§a✓";
                    case ON_COOLDOWN   -> "§e⏳";
                    case LOCKED_TIER, LOCKED_FAVOUR -> "§c🔒";
                };
                sb.append(String.format(Locale.ROOT, "%n  %s §f%-20s§7 cost %.0f, %s, id=§f%s",
                        tag, m.displayName(), m.cost(), m.minTier().displayName(), m.id()));
            }
        }
        src.sendSuccess(() -> Component.literal(sb.toString())
                .withStyle(ChatFormatting.WHITE), false);
        return 1;
    }

    private static int handleMiracleCast(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        var player = src.getPlayer();
        if (player == null) { src.sendFailure(Component.literal("Run as a player.")); return 0; }
        String id = StringArgumentType.getString(ctx, "miracleId");
        var result = tterrag1112.life_in_the_village.Npc.Religion.MiracleInvoker
                .cast(level, player, id, level.getGameTime());
        if (!result.success()) { src.sendFailure(Component.literal(result.message())); return 0; }
        src.sendSuccess(() -> Component.literal("§a" + result.message()), false);
        return 1;
    }

    // ── /religion favour ─────────────────────────────────────────────────────

    private static int handleFavourView(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        var player = src.getPlayer();
        if (player == null) { src.sendFailure(Component.literal("Run as a player.")); return 0; }
        long now = level.getGameTime();
        java.util.UUID pid = player.getUUID();
        var rites = tterrag1112.life_in_the_village.Npc.Religion.RiteSavedData.get(level);

        // F1a sub-stage 3a — favour is per GOD now. Show the player's standing with
        // each god they have an entry with, or whose religion they believe in.
        java.util.Set<String> gods = new java.util.LinkedHashSet<>();
        rites.getPlayerFavour(pid).ifPresent(f -> gods.addAll(f.all().keySet()));
        rites.getPlayerPiety(pid).ifPresent(p -> {
            for (String rid : p.beliefs().keySet())
                Religions.find(level, rid).ifPresent(r ->
                        tterrag1112.life_in_the_village.Npc.Religion.GodRegistry
                                .godsFor(r).forEach(g -> gods.add(g.id())));
        });

        StringBuilder sb = new StringBuilder("§e=== Divine Favour (per god) ===");
        if (gods.isEmpty()) sb.append("\n  §7(no standing with any god)");
        for (String gid : gods) {
            float fav = tterrag1112.life_in_the_village.Npc.Religion.DivineFavour
                    .current(level, pid, gid, now);
            var god = tterrag1112.life_in_the_village.Npc.Religion.GodRegistry.get(gid);
            String name = god != null ? god.displayName() : gid;
            sb.append(String.format(Locale.ROOT, "%n  §6%-16s§8(%s)§7 %.1f", name, gid, fav));
        }
        src.sendSuccess(() -> Component.literal(sb.toString())
                .withStyle(ChatFormatting.WHITE), false);
        return 1;
    }

    private static int handleFavourGrantSpend(CommandContext<CommandSourceStack> ctx,
                                              boolean grant) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        var player = src.getPlayer();
        if (player == null) { src.sendFailure(Component.literal("Run as a player.")); return 0; }
        // F1a sub-stage 3a — the favour debug grant/spend target a GOD id now.
        String gid = StringArgumentType.getString(ctx, "religionId");
        if (tterrag1112.life_in_the_village.Npc.Religion.GodRegistry.get(gid) == null) {
            src.sendFailure(Component.literal("Unknown god " + gid
                    + " (try sun_mother / the_pattern / sea_mother / forge_father)"));
            return 0;
        }
        float amount = FloatArgumentType.getFloat(ctx, "amount");
        long now = level.getGameTime();
        java.util.UUID pid = player.getUUID();
        if (grant) {
            // Raw debug grant (bypasses the piety cap so V2 spend can be exercised).
            float v = tterrag1112.life_in_the_village.Npc.Religion.DivineFavour
                    .debugGrant(level, pid, gid, amount, now);
            src.sendSuccess(() -> Component.literal(
                    "§aGranted§7 " + amount + " favour with god " + gid + " → §f" + v), false);
        } else {
            boolean ok = tterrag1112.life_in_the_village.Npc.Religion.DivineFavour
                    .spend(level, pid, gid, amount, now);
            if (!ok) { src.sendFailure(Component.literal("Insufficient favour with god " + gid)); return 0; }
            src.sendSuccess(() -> Component.literal(
                    "§aSpent§7 " + amount + " favour with god " + gid), false);
        }
        return 1;
    }

    // ── /religion identity <religion> ───────────────────────────────────────

    private static int handleIdentity(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        String id = StringArgumentType.getString(ctx, "religionId");
        Religion religion = Religions.get(level, id);
        if (religion == null) {
            src.sendFailure(Component.literal("Unknown religion " + id));
            return 0;
        }
        tterrag1112.life_in_the_village.Npc.Religion.ReligionIdentity identity =
                tterrag1112.life_in_the_village.Npc.Religion.ReligionIdentity.get(id);
        if (identity == null) {
            src.sendFailure(Component.literal("No authored identity for " + religion.displayName()));
            return 0;
        }
        // F1a 4a — deity attributes (name/domain/character/demands/rewards/virtues/
        // taboos) now come from the GOD; cosmology/history/aesthetics/practices stay
        // the religion's identity. (Keeps the readout clean of deity() reads so the
        // cleanup stage can delete the ReligionIdentity deity duplication.)
        var god = tterrag1112.life_in_the_village.Npc.Religion.GodRegistry
                .primaryGod(religion).orElse(null);
        String deityName = tterrag1112.life_in_the_village.Npc.Religion.GodRegistry
                .primaryDeityName(religion, religion.displayName());

        StringBuilder sb = new StringBuilder();
        sb.append("§e=== ").append(religion.displayName()).append(" — identity ===");
        sb.append("\n§6Cosmology§7: ").append(identity.cosmology());
        if (god != null) {
            sb.append("\n§6Deity§7: §f").append(deityName)
                    .append("§7 (domain §f").append(god.domain()).append("§7)");
            sb.append("\n  §7character: ").append(god.character());
            sb.append("\n  §7demands: ").append(god.demands());
            sb.append("\n  §7rewards: ").append(god.rewards());
        }
        sb.append("\n§6Sacred history§7: ").append(identity.history().foundingMyth());
        for (var e : identity.history().events()) {
            sb.append("\n  §a").append(e.title()).append("§7 — ").append(e.text());
        }
        sb.append("\n§6Virtues§7:");
        for (var v : tterrag1112.life_in_the_village.Npc.Religion.GodRegistry.unionVirtues(religion)) {
            sb.append("\n  §a[").append(v.concept().name()).append("]§7 ").append(v.text());
        }
        sb.append("\n§6Taboos§7:");
        for (var t : tterrag1112.life_in_the_village.Npc.Religion.GodRegistry.unionTaboos(religion)) {
            sb.append("\n  §c[").append(t.concept().name()).append("]§7 ").append(t.text());
        }
        var a = identity.aesthetics();
        sb.append("\n§6Aesthetics§7: style §f").append(a.styleId())
                .append("§7; palette ").append(a.palette())
                .append("; iconography ").append(a.iconography());
        sb.append("\n§6Practices§7:");
        for (String p : identity.practices()) sb.append("\n  §7• ").append(p);

        src.sendSuccess(() -> Component.literal(sb.toString())
                .withStyle(ChatFormatting.WHITE), false);
        return 1;
    }

    // ── /religion me ───────────────────────────────────────────────────────

    private static int handleMe(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        var player = src.getPlayer();
        if (player == null) {
            src.sendFailure(Component.literal("Run as a player."));
            return 0;
        }
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                tterrag1112.life_in_the_village.Npc.Religion.PlayerReligionSnapshotBuilder
                        .build(player, level));
        return 1;
    }

    // ── /religion temple ──────────────────────────────────────────────────

    private static int handleTemple(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        var player = src.getPlayer();
        if (player == null) {
            src.sendFailure(Component.literal("Run as a player (nearest-temple lookup)."));
            return 0;
        }
        VillageSavedData data = VillageSavedData.get(level);
        Village village = data.getVillageAt(player.blockPosition()).orElse(null);
        if (village == null) {
            src.sendFailure(Component.literal("No village at your position"));
            return 0;
        }
        tterrag1112.life_in_the_village.Village.Building best = null;
        double bestDist = Double.MAX_VALUE;
        for (java.util.UUID bid : village.getBuildingIds()) {
            var b = data.getBuildingById(bid).orElse(null);
            if (b == null || !tterrag1112.life_in_the_village.Npc.Religion.BuildingFaith
                    .isReligiousBuilding(b.getType())) continue;
            double d = b.getShape().getOrigin().distSqr(player.blockPosition());
            if (d < bestDist) { bestDist = d; best = b; }
        }
        if (best == null) {
            src.sendFailure(Component.literal(
                    "No temple/chapel/shrine in " + village.getName()));
            return 0;
        }
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                tterrag1112.life_in_the_village.Npc.Religion.TempleSnapshotBuilder
                        .build(level, village, best));
        return 1;
    }

    // ── /religion graveyard ──────────────────────────────────────────────

    private static int handleGraveyard(CommandContext<CommandSourceStack> ctx, int rows, int cols) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        var player = src.getPlayer();
        if (player == null) {
            src.sendFailure(Component.literal("Run as a player (graveyard placed at your position)."));
            return 0;
        }
        VillageSavedData data = VillageSavedData.get(level);
        Village village = data.getVillageAt(player.blockPosition()).orElse(null);
        if (village == null) {
            src.sendFailure(Component.literal("No village at your position."));
            return 0;
        }
        var graveyard = tterrag1112.life_in_the_village.Village.Graveyard.GraveyardSavedData.get(level)
                .createGraveyard(village.getId(), player.blockPosition(), rows, cols, 2);
        src.sendSuccess(() -> Component.literal(
                "Graveyard created in §f" + village.getName() + "§r with §a"
                        + graveyard.capacity() + "§r grave slots."), false);
        return 1;
    }

    // ── /religion pilgrimage ─────────────────────────────────────────────

    private static int handlePilgrimage(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        java.util.UUID npcId = UuidArgument.getUuid(ctx, "npc");
        String destName = StringArgumentType.getString(ctx, "destVillage");

        TownspersonMob npc = TownspersonMob.findByUUID(level, npcId).orElse(null);
        if (npc == null || !npc.isAlive()) {
            src.sendFailure(Component.literal("No realized NPC " + npcId));
            return 0;
        }
        if (npc.isVisitor()) {
            src.sendFailure(Component.literal("That NPC is a visitor, not a resident."));
            return 0;
        }
        VillageSavedData data = VillageSavedData.get(level);
        Village home = npc.getAssignedVillageName()
                .flatMap(data::getVillageByName).orElse(null);
        if (home == null) {
            src.sendFailure(Component.literal("That NPC has no home village."));
            return 0;
        }
        Village dest = data.getVillageByName(destName).orElse(null);
        if (dest == null) {
            src.sendFailure(Component.literal("No village " + destName));
            return 0;
        }
        if (home.getId().equals(dest.getId())) {
            src.sendFailure(Component.literal("Destination is the home village."));
            return 0;
        }
        var route = data.getRouteBetween(home.getId(), dest.getId()).orElse(null);
        if (route == null) {
            src.sendFailure(Component.literal(
                    "No trade route between " + home.getName() + " and " + dest.getName()
                            + " (R3e-3b-1 requires a route-connected destination)."));
            return 0;
        }
        tterrag1112.life_in_the_village.Village.Travel.PilgrimageSavedData.get(level)
                .dispatchPilgrimage(npc, route.getRouteId(), home.getId(), dest.getId(),
                        level.getGameTime());
        src.sendSuccess(() -> Component.literal(
                "§a" + npc.getNpcName() + "§r departs on pilgrimage to §f" + dest.getName()), false);
        return 1;
    }

    // ── /religion shrine ─────────────────────────────────────────────────

    private static int handleShrine(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        String religionId = StringArgumentType.getString(ctx, "religionId");
        if (Religions.find(level, religionId).isEmpty()) {
            src.sendFailure(Component.literal("Unknown religion " + religionId));
            return 0;
        }
        var player = src.getPlayer();
        if (player == null) {
            src.sendFailure(Component.literal("Run as a player (nearest-shrine lookup)."));
            return 0;
        }
        VillageSavedData data = VillageSavedData.get(level);
        Village village = data.getVillageAt(player.blockPosition()).orElse(null);
        if (village == null) {
            src.sendFailure(Component.literal("No village at your position"));
            return 0;
        }
        tterrag1112.life_in_the_village.Village.Building shrine = null;
        double best = Double.MAX_VALUE;
        for (java.util.UUID bid : village.getBuildingIds()) {
            var b = data.getBuildingById(bid).orElse(null);
            if (b == null || b.getType()
                    != tterrag1112.life_in_the_village.Village.Buildings.BuildingType.SHRINE) continue;
            double d = b.getShape().getOrigin().distSqr(player.blockPosition());
            if (d < best) { best = d; shrine = b; }
        }
        if (shrine == null) {
            src.sendFailure(Component.literal("No shrine in " + village.getName()));
            return 0;
        }
        shrine.setPatronFaith(religionId);
        data.markDirty();

        // Re-fix the seated priest (belief + order) if one is loaded.
        final tterrag1112.life_in_the_village.Village.Building shrineF = shrine;
        TownspersonMob priest = level.getEntitiesOfClass(TownspersonMob.class,
                        new net.minecraft.world.phys.AABB(shrineF.getShape().getOrigin()).inflate(48),
                        m -> m.getProfession() == tterrag1112.life_in_the_village.Profession.Profession.PRIEST
                                && m.getAssignedBuildingId().map(shrineF.getId()::equals).orElse(false))
                .stream().findFirst().orElse(null);
        if (priest != null) {
            priest.getSpecializationComponent().setLocked(false);
            tterrag1112.life_in_the_village.Npc.Religion.BuildingFaith
                    .applyClergyFaith(level, village, priest, shrineF);
            tterrag1112.life_in_the_village.Npc.Religion.ClergyOrders
                    .assignClergyOrder(level, priest);
        }
        final boolean fixed = priest != null;
        src.sendSuccess(() -> Component.literal(
                "Shrine in §f" + village.getName() + "§r now serves §a" + religionId
                        + "§r" + (fixed ? " (priest re-consecrated)" : " (no loaded priest)")), false);
        return 1;
    }

    // ── /religion list ────────────────────────────────────────────────────

    private static int handleList(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        StringBuilder sb = new StringBuilder("§e=== Registered religions ===");
        for (Religion r : Religions.all(level)) {
            sb.append(String.format(Locale.ROOT,
                    "%n  §a%-14s§r %s §7(rites=%d, holyDays=%d)",
                    r.id(), r.displayName(), r.rites().size(),
                    r.calendar().holyDaysByName().size()));
        }
        src.sendSuccess(() -> Component.literal(sb.toString()).withStyle(ChatFormatting.WHITE), false);
        return 1;
    }

    // ── /religion set ────────────────────────────────────────────────────

    private static int handleSet(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        java.util.UUID npcId = UuidArgument.getUuid(ctx, "npc");
        String religionId = StringArgumentType.getString(ctx, "religionId");
        float strength = FloatArgumentType.getFloat(ctx, "strength");

        TownspersonMob npc = TownspersonMob.findByUUID(level, npcId).orElse(null);
        if (npc == null) {
            src.sendFailure(Component.literal("No NPC " + npcId));
            return 0;
        }
        if (Religions.find(level, religionId).isEmpty()) {
            src.sendFailure(Component.literal("Unknown religion " + religionId));
            return 0;
        }
        npc.getPiety().setBelief(religionId, strength);
        src.sendSuccess(() -> Component.literal(
                "Set §f" + npc.getNpcName() + "§r belief in §a" + religionId
                        + "§r to §f" + strength), false);
        return 1;
    }

    // ── /religion rite ───────────────────────────────────────────────────

    private static int handleRite(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        Rite type;
        try { type = Rite.valueOf(StringArgumentType.getString(ctx, "type").toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) {
            src.sendFailure(Component.literal("Unknown rite type"));
            return 0;
        }
        java.util.UUID participant = UuidArgument.getUuid(ctx, "participant");
        TownspersonMob npc = TownspersonMob.findByUUID(level, participant).orElse(null);
        if (npc == null) {
            // Could be a player UUID — look up village from the
            // executor's location instead.
            var src2 = src.getPlayer();
            if (src2 == null) {
                src.sendFailure(Component.literal("Participant not found and no player executor."));
                return 0;
            }
            VillageSavedData vdata = VillageSavedData.get(level);
            Village village = vdata.getVillageAt(src2.blockPosition()).orElse(null);
            if (village == null) {
                src.sendFailure(Component.literal("No village at executor position"));
                return 0;
            }
            RiteScheduler.schedule(level, village, type, List.of(participant), 0L);
            src.sendSuccess(() -> Component.literal(
                    "Scheduled §a" + type.name() + "§r in §f" + village.getName()), false);
            return 1;
        }
        Village village = npc.getAssignedVillageName()
                .flatMap(VillageSavedData.get(level)::getVillageByName).orElse(null);
        if (village == null) {
            src.sendFailure(Component.literal("Participant has no village context"));
            return 0;
        }
        RiteScheduler.schedule(level, village, type, List.of(participant), 0L);
        src.sendSuccess(() -> Component.literal(
                "Scheduled §a" + type.name() + "§r in §f" + village.getName()), false);
        return 1;
    }

    // ── /religion calendar ───────────────────────────────────────────────

    private static int handleCalendar(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        String name = StringArgumentType.getString(ctx, "village");
        Village village = VillageSavedData.get(level).getVillageByName(name).orElse(null);
        if (village == null) {
            src.sendFailure(Component.literal("No village " + name));
            return 0;
        }
        VillageSavedData vdata = VillageSavedData.get(level);
        String culture = vdata.getKingdomForVillage(village.getId())
                .map(tterrag1112.life_in_the_village.Kingdom.Kingdom::getCulture)
                .orElse("default");
        Religion religion = Religions.get(level, ReligionRegistry.dominantReligionFor(culture));
        if (religion == null) {
            src.sendFailure(Component.literal("No dominant religion resolved"));
            return 0;
        }
        long now = level.getGameTime();
        int today = tterrag1112.life_in_the_village.Npc.Religion.CalendarView.dayOfYear(now);
        StringBuilder sb = new StringBuilder();
        sb.append("§e=== ").append(village.getName()).append(" — ")
                .append(religion.displayName()).append(" calendar ===");
        sb.append("\n§7Today is day-of-year §f").append(today);
        for (var entry : tterrag1112.life_in_the_village.Npc.Religion.CalendarView
                .upcomingFor(religion, now)) {
            sb.append(String.format(Locale.ROOT, "%n  §a%-22s§r day §f%d§7 (%d days away)",
                    entry.dayLabel(), entry.dayOfYear(), entry.daysAway()));
        }
        src.sendSuccess(() -> Component.literal(sb.toString()).withStyle(ChatFormatting.WHITE), false);
        return 1;
    }

    // ── /religion tithe ──────────────────────────────────────────────────

    private static int handleTithe(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        java.util.UUID playerId = UuidArgument.getUuid(ctx, "player");
        float amount = FloatArgumentType.getFloat(ctx, "amount");
        var piety = tterrag1112.life_in_the_village.Npc.Religion.RiteSavedData.get(level)
                .getOrCreatePlayerPiety(playerId);
        String religionId = piety.primaryReligion().orElse(ReligionRegistry.SUNSTEAD);
        piety.adjustBelief(religionId, amount);
        piety.recordRiteAttendance(level.getGameTime());
        tterrag1112.life_in_the_village.Npc.Religion.RiteSavedData.get(level).markDirty();
        src.sendSuccess(() -> Component.literal(
                "§aPaid tithe§r — piety in §f" + religionId + "§r now §f" + piety.beliefIn(religionId)), false);
        return 1;
    }
}
