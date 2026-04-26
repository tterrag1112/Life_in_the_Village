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
import tterrag1112.life_in_the_village.Npc.Religion.ReligiousCalendar;
import tterrag1112.life_in_the_village.Npc.Religion.Rite;
import tterrag1112.life_in_the_village.Npc.Religion.RiteScheduler;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.List;
import java.util.Locale;
import java.util.Map;

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
                                            for (Religion r : ReligionRegistry.all()) b.suggest(r.id());
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
        );
    }

    // ── /religion list ────────────────────────────────────────────────────

    private static int handleList(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        StringBuilder sb = new StringBuilder("§e=== Registered religions ===");
        for (Religion r : ReligionRegistry.all()) {
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
        if (ReligionRegistry.find(religionId).isEmpty()) {
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
        Religion religion = ReligionRegistry.get(ReligionRegistry.dominantReligionFor(culture));
        if (religion == null) {
            src.sendFailure(Component.literal("No dominant religion resolved"));
            return 0;
        }
        long now = level.getGameTime();
        int today = (int) ((now / 24000L) % ReligiousCalendar.DAYS_PER_YEAR);
        StringBuilder sb = new StringBuilder();
        sb.append("§e=== ").append(village.getName()).append(" — ")
                .append(religion.displayName()).append(" calendar ===");
        sb.append("\n§7Today is day-of-year §f").append(today);
        for (Map.Entry<String, Integer> e : religion.calendar().holyDaysByName().entrySet()) {
            int d = ((e.getValue() % ReligiousCalendar.DAYS_PER_YEAR) + ReligiousCalendar.DAYS_PER_YEAR)
                    % ReligiousCalendar.DAYS_PER_YEAR;
            int days = (d - today + ReligiousCalendar.DAYS_PER_YEAR) % ReligiousCalendar.DAYS_PER_YEAR;
            sb.append(String.format(Locale.ROOT, "%n  §a%-22s§r day §f%d§7 (%d days away)",
                    e.getKey(), d, days));
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
