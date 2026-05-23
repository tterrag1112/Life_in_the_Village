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
import tterrag1112.life_in_the_village.Guilds.Adventurer.GuildData;
import tterrag1112.life_in_the_village.Guilds.Companies.Business;
import tterrag1112.life_in_the_village.Guilds.Companies.BusinessSavedData;
import tterrag1112.life_in_the_village.Kingdom.Kingdom;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Office.OfficeDefinition;
import tterrag1112.life_in_the_village.Npc.Office.OfficeElection;
import tterrag1112.life_in_the_village.Npc.Office.OfficeHolding;
import tterrag1112.life_in_the_village.Npc.Office.OfficePower;
import tterrag1112.life_in_the_village.Npc.Office.OfficeRegistry;
import tterrag1112.life_in_the_village.Npc.Office.OfficeState;
import tterrag1112.life_in_the_village.Npc.Office.OrgType;
import tterrag1112.life_in_the_village.Npc.Office.SelectionMethod;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * {@code /office} root debug command. Reads and writes
 * {@link OfficeState} on each org type. EntityRef syntax is
 * {@code <orgType>:<uuid>} where {@code orgType} ∈
 * {@code village, guild, business, kingdom}.
 */
public final class OfficeDebugCommand {

    private OfficeDebugCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("office")
                .then(Commands.literal("info")
                        .then(Commands.argument("entityRef", StringArgumentType.string())
                                .executes(OfficeDebugCommand::handleInfo)))
                .then(Commands.literal("set")
                        .then(Commands.argument("entityRef", StringArgumentType.string())
                                .then(Commands.argument("officeId", StringArgumentType.word())
                                        .suggests((c, b) -> {
                                            for (OfficeDefinition d : OfficeRegistry.all()) b.suggest(d.id());
                                            return b.buildFuture();
                                        })
                                        .then(Commands.argument("holderUuid", UuidArgument.uuid())
                                                .executes(OfficeDebugCommand::handleSet)))))
                .then(Commands.literal("vacate")
                        .then(Commands.argument("entityRef", StringArgumentType.string())
                                .then(Commands.argument("officeId", StringArgumentType.word())
                                        .suggests((c, b) -> {
                                            for (OfficeDefinition d : OfficeRegistry.all()) b.suggest(d.id());
                                            return b.buildFuture();
                                        })
                                        .executes(OfficeDebugCommand::handleVacate))))
                .then(Commands.literal("list-all")
                        .executes(OfficeDebugCommand::handleListAll))
                .then(Commands.literal("election")
                        .then(Commands.argument("entityRef", StringArgumentType.string())
                                .then(Commands.argument("officeId", StringArgumentType.word())
                                        .suggests((c, b) -> {
                                            for (OfficeDefinition d : OfficeRegistry.all()) b.suggest(d.id());
                                            return b.buildFuture();
                                        })
                                        .executes(OfficeDebugCommand::handleElection))))
                .then(Commands.literal("grant")
                        .then(Commands.argument("entityRef", StringArgumentType.string())
                                .then(Commands.argument("officeId", StringArgumentType.word())
                                        .suggests((c, b) -> {
                                            for (OfficeDefinition d : OfficeRegistry.all()) b.suggest(d.id());
                                            return b.buildFuture();
                                        })
                                        .then(Commands.argument("holderUuid", UuidArgument.uuid())
                                                .executes(OfficeDebugCommand::handleGrant)))))
                .then(Commands.literal("vacate-and-elect")
                        .then(Commands.argument("entityRef", StringArgumentType.string())
                                .then(Commands.argument("officeId", StringArgumentType.word())
                                        .suggests((c, b) -> {
                                            for (OfficeDefinition d : OfficeRegistry.all()) b.suggest(d.id());
                                            return b.buildFuture();
                                        })
                                        .executes(OfficeDebugCommand::handleVacateAndElect))))
                .then(Commands.literal("powers")
                        .then(Commands.argument("holderUuid", UuidArgument.uuid())
                                .executes(OfficeDebugCommand::handlePowers)))
                .then(Commands.literal("me")
                        .executes(OfficeDebugCommand::handleMe))
        );
    }

    // =========================================================================
    // EntityRef resolution
    // =========================================================================

    /** Parsed form of an "{@code orgType:uuid}" debug-command argument. */
    private record EntityRef(OrgType orgType, UUID uuid) {}

    /** A handle to a mutable OfficeState plus a "save it" callback. */
    private record EntityHandle(OrgType orgType, UUID uuid, String displayName,
                                OfficeState state, Runnable persist) {}

    private static EntityRef parseRef(String raw) {
        int colon = raw.indexOf(':');
        if (colon < 0) return null;
        String type = raw.substring(0, colon).toLowerCase(Locale.ROOT);
        String idText = raw.substring(colon + 1);
        UUID uuid;
        try {
            uuid = UUID.fromString(idText);
        } catch (IllegalArgumentException e) {
            return null;
        }
        return switch (type) {
            case "village" -> new EntityRef(OrgType.VILLAGE, uuid);
            case "guild"   -> new EntityRef(OrgType.GUILD,   uuid);
            case "business" -> new EntityRef(OrgType.BUSINESS, uuid);
            case "kingdom" -> new EntityRef(OrgType.KINGDOM, uuid);
            default        -> null;
        };
    }

    /**
     * Resolves an EntityRef to a mutable OfficeState plus the persist
     * callback that flushes the change. Returns {@code null} when the
     * entity doesn't exist; the caller has already failed the command.
     */
    private static EntityHandle resolve(CommandSourceStack src, EntityRef ref) {
        ServerLevel level = src.getLevel();
        VillageSavedData vdata = VillageSavedData.get(level);
        switch (ref.orgType) {
            case VILLAGE -> {
                Village v = vdata.getVillageById(ref.uuid).orElse(null);
                if (v == null) return null;
                return new EntityHandle(OrgType.VILLAGE, ref.uuid, v.getName(),
                        v.getOffices(), vdata::markDirty);
            }
            case GUILD -> {
                GuildData g = vdata.getGuildById(ref.uuid).orElse(null);
                if (g == null) return null;
                return new EntityHandle(OrgType.GUILD, ref.uuid, "Guild " + ref.uuid,
                        g.offices(), vdata::markDirty);
            }
            case KINGDOM -> {
                Kingdom k = vdata.getAllKingdoms().stream()
                        .filter(x -> x.getId().equals(ref.uuid))
                        .findFirst().orElse(null);
                if (k == null) return null;
                return new EntityHandle(OrgType.KINGDOM, ref.uuid, k.getName(),
                        k.getOffices(), vdata::markDirty);
            }
            case BUSINESS -> {
                BusinessSavedData cdata = BusinessSavedData.get(level);
                Business c = cdata.getAllBusinesses().stream()
                        .filter(x -> x.getBusinessId().equals(ref.uuid))
                        .findFirst().orElse(null);
                if (c == null) return null;
                return new EntityHandle(OrgType.BUSINESS, ref.uuid, c.getName(),
                        c.getOffices(), cdata::setDirty);
            }
            default -> { return null; }
        }
    }

    private static EntityHandle resolveOrFail(CommandSourceStack src, String rawRef) {
        EntityRef ref = parseRef(rawRef);
        if (ref == null) {
            src.sendFailure(Component.literal(
                    "Bad entityRef '" + rawRef + "' — expected village:<uuid>, guild:<uuid>, business:<uuid>, or kingdom:<uuid>"));
            return null;
        }
        EntityHandle h = resolve(src, ref);
        if (h == null) {
            src.sendFailure(Component.literal(
                    "No " + ref.orgType.name().toLowerCase(Locale.ROOT) + " with id " + ref.uuid));
        }
        return h;
    }

    // =========================================================================
    // Handlers
    // =========================================================================

    private static int handleInfo(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        EntityHandle h = resolveOrFail(src, StringArgumentType.getString(ctx, "entityRef"));
        if (h == null) return 0;
        long now = src.getLevel().getGameTime();

        StringBuilder sb = new StringBuilder();
        sb.append("§e=== Offices: ").append(h.orgType.name()).append(" '").append(h.displayName)
                .append("' §7(").append(h.uuid).append(")§e ===");
        for (Map.Entry<String, OfficeHolding> e : h.state.snapshot().entrySet()) {
            sb.append(formatHolding(e.getValue(), now));
        }
        src.sendSuccess(() -> Component.literal(sb.toString()).withStyle(ChatFormatting.WHITE), false);
        return 1;
    }

    private static int handleSet(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        EntityHandle h = resolveOrFail(src, StringArgumentType.getString(ctx, "entityRef"));
        if (h == null) return 0;
        String officeId = StringArgumentType.getString(ctx, "officeId");
        UUID holderUuid = UuidArgument.getUuid(ctx, "holderUuid");

        OfficeDefinition def = OfficeRegistry.get(officeId);
        if (def == null) {
            src.sendFailure(Component.literal("Unknown officeId: " + officeId));
            return 0;
        }
        if (def.orgType() != h.orgType) {
            src.sendFailure(Component.literal(
                    "Office '" + officeId + "' belongs to " + def.orgType()
                            + ", not " + h.orgType));
            return 0;
        }

        // v1 warning: the same UUID already holds another office in THIS org.
        if (h.state.isHeldBy(holderUuid)) {
            src.sendSuccess(() -> Component.literal(
                    "§e[warn] §r" + holderUuid + " already holds another office in this org. "
                            + "Phase 3 will enforce one-per-org; v1 just warns.").withStyle(ChatFormatting.YELLOW), false);
        }

        // Treat the holder UUID as a player UUID iff it matches a connected player;
        // otherwise assume NPC. Phase 3 will replace with explicit /office set-player.
        boolean isOnlinePlayer = src.getServer().getPlayerList().getPlayer(holderUuid) != null;
        long now = src.getLevel().getGameTime();
        long termEnd = def.isIndefiniteTerm() ? 0L : now + (long) def.termDays() * 24000L;
        OfficeHolding holding = isOnlinePlayer
                ? OfficeHolding.heldByPlayer(officeId, h.uuid, holderUuid, now, termEnd, def.defaultSelection())
                : OfficeHolding.heldByNpc(officeId, h.uuid, holderUuid, now, termEnd, def.defaultSelection());
        h.state.set(officeId, holding);
        h.persist.run();

        src.sendSuccess(() -> Component.literal(
                "Set §f" + officeId + "§r on §f" + h.displayName + "§r to "
                        + (isOnlinePlayer ? "player " : "NPC ") + holderUuid),
                false);
        return 1;
    }

    private static int handleVacate(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        EntityHandle h = resolveOrFail(src, StringArgumentType.getString(ctx, "entityRef"));
        if (h == null) return 0;
        String officeId = StringArgumentType.getString(ctx, "officeId");
        if (OfficeRegistry.get(officeId) == null) {
            src.sendFailure(Component.literal("Unknown officeId: " + officeId));
            return 0;
        }
        h.state.vacate(officeId);
        h.persist.run();
        src.sendSuccess(() -> Component.literal(
                "Vacated §f" + officeId + "§r on §f" + h.displayName), false);
        return 1;
    }

    private static int handleElection(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        EntityHandle h = resolveOrFail(src, StringArgumentType.getString(ctx, "entityRef"));
        if (h == null) return 0;
        String officeId = StringArgumentType.getString(ctx, "officeId");
        if (OfficeRegistry.get(officeId) == null) {
            src.sendFailure(Component.literal("Unknown officeId: " + officeId));
            return 0;
        }
        OfficeHolding result = OfficeElection.runElection(h.orgType, h.uuid, officeId, src.getLevel());
        h.persist.run();
        long now = src.getLevel().getGameTime();
        src.sendSuccess(() -> Component.literal(
                "Ran election for §f" + officeId + "§r on §f" + h.displayName + "§r → "
                        + (result.isVacant() ? "vacant"
                            : (result.holderIsPlayer() ? "PLAYER " : "NPC ")
                                + result.holderUuid().orElseThrow()
                                + " (" + result.actualSelection() + ", "
                                + (result.daysRemaining(now) < 0 ? "indefinite"
                                    : result.daysRemaining(now) + "d)"))), false);
        return 1;
    }

    private static int handleGrant(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        EntityHandle h = resolveOrFail(src, StringArgumentType.getString(ctx, "entityRef"));
        if (h == null) return 0;
        String officeId = StringArgumentType.getString(ctx, "officeId");
        UUID holderUuid = UuidArgument.getUuid(ctx, "holderUuid");
        OfficeDefinition def = OfficeRegistry.get(officeId);
        if (def == null) {
            src.sendFailure(Component.literal("Unknown officeId: " + officeId));
            return 0;
        }
        if (def.orgType() != h.orgType) {
            src.sendFailure(Component.literal(
                    "Office '" + officeId + "' belongs to " + def.orgType()
                            + ", not " + h.orgType));
            return 0;
        }
        boolean isOnlinePlayer = src.getServer().getPlayerList().getPlayer(holderUuid) != null;
        if (isOnlinePlayer) {
            OfficeElection.seatPlayer(h.orgType, h.uuid, officeId, holderUuid, src.getLevel());
        } else {
            // Phase 6.3.2.d — debug command bypasses the eligibility predicate.
            OfficeElection.seatNpcForced(h.orgType, h.uuid, officeId, holderUuid,
                    SelectionMethod.APPOINTED, src.getLevel());
        }
        h.persist.run();
        src.sendSuccess(() -> Component.literal(
                "Granted §f" + officeId + "§r on §f" + h.displayName + "§r to "
                        + (isOnlinePlayer ? "player " : "NPC ") + holderUuid
                        + " §7(events fired)"), false);
        return 1;
    }

    private static int handleVacateAndElect(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        EntityHandle h = resolveOrFail(src, StringArgumentType.getString(ctx, "entityRef"));
        if (h == null) return 0;
        String officeId = StringArgumentType.getString(ctx, "officeId");
        if (OfficeRegistry.get(officeId) == null) {
            src.sendFailure(Component.literal("Unknown officeId: " + officeId));
            return 0;
        }
        h.state.vacate(officeId);
        OfficeHolding result = OfficeElection.runElection(h.orgType, h.uuid, officeId, src.getLevel());
        h.persist.run();
        src.sendSuccess(() -> Component.literal(
                "Vacated and re-elected §f" + officeId + "§r on §f" + h.displayName + "§r → "
                        + (result.isVacant() ? "vacant" : result.holderUuid().orElseThrow().toString())), false);
        return 1;
    }

    private static int handlePowers(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        UUID holderUuid = UuidArgument.getUuid(ctx, "holderUuid");
        var matches = OfficeRegistry.findOfficesHeldBy(holderUuid, src.getLevel());
        if (matches.isEmpty()) {
            src.sendSuccess(() -> Component.literal(
                    "§7" + holderUuid + " holds no offices."), false);
            return 1;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("§e=== Powers held by ").append(holderUuid).append(" ===");
        for (var m : matches) {
            OfficeDefinition def = OfficeRegistry.get(m.holding().officeId());
            sb.append("\n  §a").append(m.holding().officeId())
                    .append("§7 on ").append(m.orgType().name().toLowerCase(Locale.ROOT))
                    .append(" §f").append(m.orgDisplayName());
            if (def != null) {
                for (OfficePower p : def.powers()) {
                    sb.append("\n      §b• ").append(p.name());
                }
            }
        }
        src.sendSuccess(() -> Component.literal(sb.toString()).withStyle(ChatFormatting.WHITE), false);
        return 1;
    }

    private static int handleMe(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        var player = src.getPlayer();
        if (player == null) {
            src.sendFailure(Component.literal("/office me requires a player executor."));
            return 0;
        }
        var matches = OfficeRegistry.findOfficesHeldBy(player.getUUID(), src.getLevel());
        long now = src.getLevel().getGameTime();
        if (matches.isEmpty()) {
            src.sendSuccess(() -> Component.literal(
                    "§7You hold no offices."), false);
            return 1;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("§e=== Your offices ===");
        for (var m : matches) {
            OfficeDefinition def = OfficeRegistry.get(m.holding().officeId());
            long days = m.holding().daysRemaining(now);
            sb.append("\n  §a").append(def != null ? def.displayName() : m.holding().officeId())
                    .append("§7 of §f").append(m.orgDisplayName())
                    .append(" §7(").append(days < 0 ? "indefinite" : days + "d remaining").append(")");
            if (def != null) {
                StringBuilder powerLine = new StringBuilder();
                for (OfficePower p : def.powers()) {
                    if (powerLine.length() > 0) powerLine.append(", ");
                    powerLine.append(p.name());
                }
                if (powerLine.length() > 0) {
                    sb.append("\n      §bPowers: §f").append(powerLine);
                }
            }
        }
        sb.append("\n§7Use §f/office grant§7 / §f/office vacate-and-elect§7 to manage these.");
        src.sendSuccess(() -> Component.literal(sb.toString()).withStyle(ChatFormatting.WHITE), false);
        return 1;
    }

    private static int handleListAll(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        VillageSavedData vdata = VillageSavedData.get(level);
        BusinessSavedData cdata = BusinessSavedData.get(level);
        long now = level.getGameTime();

        StringBuilder sb = new StringBuilder();
        sb.append("§e=== All Offices ===");
        appendSection(sb, "Villages", vdata.getAllVillages().size());
        for (Village v : vdata.getAllVillages()) {
            sb.append(formatHeader("village", v.getId(), v.getName()));
            for (var e : v.getOffices().snapshot().entrySet()) sb.append(formatHolding(e.getValue(), now));
        }
        appendSection(sb, "Guilds", vdata.getAllGuilds().size());
        for (GuildData g : vdata.getAllGuilds()) {
            sb.append(formatHeader("guild", g.guildId(), "Guild " + g.guildId()));
            for (var e : g.offices().snapshot().entrySet()) sb.append(formatHolding(e.getValue(), now));
        }
        appendSection(sb, "Kingdoms", vdata.getAllKingdoms().size());
        for (Kingdom k : vdata.getAllKingdoms()) {
            sb.append(formatHeader("kingdom", k.getId(), k.getName()));
            for (var e : k.getOffices().snapshot().entrySet()) sb.append(formatHolding(e.getValue(), now));
        }
        appendSection(sb, "Companies", cdata.getAllBusinesses().size());
        for (Business c : cdata.getAllBusinesses()) {
            sb.append(formatHeader("business", c.getBusinessId(), c.getName()));
            for (var e : c.getOffices().snapshot().entrySet()) sb.append(formatHolding(e.getValue(), now));
        }

        src.sendSuccess(() -> Component.literal(sb.toString()).withStyle(ChatFormatting.WHITE), false);
        return 1;
    }

    // =========================================================================
    // Formatting helpers
    // =========================================================================

    private static void appendSection(StringBuilder sb, String label, int count) {
        sb.append("\n§6").append(label).append(" §7(").append(count).append(")");
    }

    private static String formatHeader(String type, UUID uuid, String displayName) {
        return String.format(Locale.ROOT, "%n  §6%s:%s §f%s",
                type, uuid, displayName);
    }

    private static String formatHolding(OfficeHolding h, long now) {
        if (h.isVacant()) {
            return String.format(Locale.ROOT,
                    "%n    §8%s §7[vacant — %s]",
                    h.officeId(), h.actualSelection().name());
        }
        UUID holder = h.holderUuid().orElseThrow();
        String kind = h.holderIsPlayer() ? "PLAYER" : "NPC";
        long days = h.daysRemaining(now);
        String termDisplay = days < 0 ? "indefinite" : days + "d remaining";
        return String.format(Locale.ROOT,
                "%n    §a%s §f%s§7=§f%s §7(%s, %s)",
                h.officeId(), kind, holder, h.actualSelection().name(), termDisplay);
    }
}
