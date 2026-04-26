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
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Crime.CrimeReport;
import tterrag1112.life_in_the_village.Npc.Crime.CrimeReporter;
import tterrag1112.life_in_the_village.Npc.Crime.CrimeSavedData;
import tterrag1112.life_in_the_village.Npc.Crime.CrimeType;
import tterrag1112.life_in_the_village.Npc.Crime.Punishment;
import tterrag1112.life_in_the_village.Npc.Crime.PunishmentExecutor;
import tterrag1112.life_in_the_village.Npc.Crime.PunishmentSelector;
import tterrag1112.life_in_the_village.Npc.Crime.PunishmentType;
import tterrag1112.life_in_the_village.Npc.Crime.ReportStatus;
import tterrag1112.life_in_the_village.Npc.Crime.Trial;
import tterrag1112.life_in_the_village.Npc.Crime.TrialExecutor;
import tterrag1112.life_in_the_village.Npc.Crime.TrialVerdict;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code /crime} debug subcommands per spec line 302.
 *
 * <ul>
 *   <li>{@code /crime list <village>}</li>
 *   <li>{@code /crime report <type> <perp> [victim]}</li>
 *   <li>{@code /crime trial <reportId>} — force-run a scheduled trial</li>
 *   <li>{@code /crime punish <reportId> <punishmentType>} — apply
 *   without running a trial</li>
 *   <li>{@code /crime convict <reportId> [verdict]} — force a
 *   verdict; defaults to GUILTY</li>
 * </ul>
 */
public final class CrimeDebugCommand {

    private CrimeDebugCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("crime")
                .then(Commands.literal("list")
                        .then(Commands.argument("village", StringArgumentType.string())
                                .executes(CrimeDebugCommand::handleList)))
                .then(Commands.literal("report")
                        .then(Commands.argument("type", StringArgumentType.word())
                                .suggests((c, b) -> {
                                    for (CrimeType t : CrimeType.values()) b.suggest(t.name());
                                    return b.buildFuture();
                                })
                                .then(Commands.argument("perp", UuidArgument.uuid())
                                        .executes(CrimeDebugCommand::handleReport)
                                        .then(Commands.argument("victim", UuidArgument.uuid())
                                                .executes(CrimeDebugCommand::handleReportWithVictim)))))
                .then(Commands.literal("trial")
                        .then(Commands.argument("reportId", UuidArgument.uuid())
                                .executes(CrimeDebugCommand::handleTrial)))
                .then(Commands.literal("punish")
                        .then(Commands.argument("reportId", UuidArgument.uuid())
                                .then(Commands.argument("punishmentType", StringArgumentType.word())
                                        .suggests((c, b) -> {
                                            for (PunishmentType t : PunishmentType.values()) b.suggest(t.name());
                                            return b.buildFuture();
                                        })
                                        .executes(CrimeDebugCommand::handlePunish))))
                .then(Commands.literal("convict")
                        .then(Commands.argument("reportId", UuidArgument.uuid())
                                .executes(ctx -> doConvict(ctx, TrialVerdict.GUILTY))
                                .then(Commands.argument("verdict", StringArgumentType.word())
                                        .suggests((c, b) -> {
                                            for (TrialVerdict v : TrialVerdict.values()) b.suggest(v.name());
                                            return b.buildFuture();
                                        })
                                        .executes(CrimeDebugCommand::handleConvict))))
        );
    }

    // ── /crime list ────────────────────────────────────────────────────────

    private static int handleList(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        String name = StringArgumentType.getString(ctx, "village");
        Village village = VillageSavedData.get(level).getVillageByName(name).orElse(null);
        if (village == null) {
            src.sendFailure(Component.literal("No village '" + name + "'"));
            return 0;
        }
        CrimeSavedData crimes = CrimeSavedData.get(level);
        StringBuilder sb = new StringBuilder();
        sb.append("§e=== Crime reports — ").append(village.getName()).append(" ===");
        var list = crimes.reportsForVillage(village.getId());
        if (list.isEmpty()) sb.append("\n  §8(none)");
        for (CrimeReport r : list) {
            sb.append(String.format(Locale.ROOT,
                    "%n  §a%-12s§r %s §7status=§f%s §7witnesses=§f%d  §7id=§f%s",
                    r.type().name(),
                    r.perpetratorId().map(p -> "by " + p.toString().substring(0, 8))
                            .orElse("by unknown"),
                    r.status().name(), r.witnessIds().size(),
                    r.reportId().toString().substring(0, 8)));
        }
        src.sendSuccess(() -> Component.literal(sb.toString()).withStyle(ChatFormatting.WHITE), false);
        return 1;
    }

    // ── /crime report ─────────────────────────────────────────────────────

    private static int handleReport(CommandContext<CommandSourceStack> ctx) {
        return doReport(ctx, null);
    }

    private static int handleReportWithVictim(CommandContext<CommandSourceStack> ctx) {
        return doReport(ctx, UuidArgument.getUuid(ctx, "victim"));
    }

    private static int doReport(CommandContext<CommandSourceStack> ctx, UUID victim) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        String typeName = StringArgumentType.getString(ctx, "type");
        UUID perp = UuidArgument.getUuid(ctx, "perp");
        CrimeType type;
        try { type = CrimeType.valueOf(typeName.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) {
            src.sendFailure(Component.literal("Unknown crime type: " + typeName));
            return 0;
        }
        Optional<CrimeReport> filed = CrimeReporter.builder(type, level)
                .perpetrator(perp)
                .victim(victim)
                .reportedBy(src.getPlayer() != null ? src.getPlayer().getUUID() : perp)
                .location(src.getPlayer() != null
                        ? src.getPlayer().blockPosition()
                        : net.minecraft.core.BlockPos.ZERO)
                .commit();
        if (filed.isEmpty()) {
            src.sendFailure(Component.literal("Could not file (no village context)"));
            return 0;
        }
        CrimeReport r = filed.get();
        src.sendSuccess(() -> Component.literal(
                "§aFiled§r " + r.type().name() + " report §f" + r.reportId()), false);
        return 1;
    }

    // ── /crime trial ─────────────────────────────────────────────────────

    private static int handleTrial(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        UUID reportId = UuidArgument.getUuid(ctx, "reportId");
        CrimeSavedData crimes = CrimeSavedData.get(level);
        CrimeReport report = crimes.getReport(reportId).orElse(null);
        if (report == null) {
            src.sendFailure(Component.literal("No report " + reportId));
            return 0;
        }
        // Force any associated trial's scheduled tick to now, then run.
        Trial trial = report.trialId().flatMap(crimes::getTrial).orElse(null);
        if (trial != null) {
            crimes.putTrial(new Trial(trial.trialId(), trial.reportId(),
                    trial.presidingOfficerId(), trial.jurors(),
                    trial.evidence(), trial.testimonies(),
                    level.getGameTime(), trial.completedTick(),
                    trial.verdict(), trial.punishment()));
        }
        TrialExecutor.runDue(level);
        src.sendSuccess(() -> Component.literal("Forced trial run."), false);
        return 1;
    }

    // ── /crime punish ────────────────────────────────────────────────────

    private static int handlePunish(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        UUID reportId = UuidArgument.getUuid(ctx, "reportId");
        String punishName = StringArgumentType.getString(ctx, "punishmentType");
        CrimeReport report = CrimeSavedData.get(level).getReport(reportId).orElse(null);
        if (report == null) {
            src.sendFailure(Component.literal("No report " + reportId));
            return 0;
        }
        PunishmentType pt;
        try { pt = PunishmentType.valueOf(punishName.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) {
            src.sendFailure(Component.literal("Unknown punishment type: " + punishName));
            return 0;
        }
        PunishmentExecutor.execute(Punishment.of(pt), report, level);
        src.sendSuccess(() -> Component.literal(
                "Applied §a" + pt.name() + "§r to §f" + reportId), false);
        return 1;
    }

    // ── /crime convict ───────────────────────────────────────────────────

    private static int handleConvict(CommandContext<CommandSourceStack> ctx) {
        TrialVerdict v;
        try { v = TrialVerdict.valueOf(StringArgumentType.getString(ctx, "verdict").toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { v = TrialVerdict.GUILTY; }
        return doConvict(ctx, v);
    }

    private static int doConvict(CommandContext<CommandSourceStack> ctx, TrialVerdict verdict) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        UUID reportId = UuidArgument.getUuid(ctx, "reportId");
        CrimeSavedData crimes = CrimeSavedData.get(level);
        CrimeReport report = crimes.getReport(reportId).orElse(null);
        if (report == null) {
            src.sendFailure(Component.literal("No report " + reportId));
            return 0;
        }
        // Build a synthetic trial if none exists, stamp it with the
        // forced verdict, route through PunishmentExecutor on GUILTY.
        Trial trial = report.trialId().flatMap(crimes::getTrial).orElseGet(() ->
                new Trial(UUID.randomUUID(), reportId, Optional.empty(),
                        java.util.List.of(), java.util.List.of(), java.util.List.of(),
                        level.getGameTime(), level.getGameTime(),
                        TrialVerdict.PENDING, Optional.empty()));
        Punishment punishment = verdict == TrialVerdict.GUILTY
                ? PunishmentSelector.select(report, crimes, VillageSavedData.get(level)
                .getVillageById(report.villageId()).orElse(null), level)
                : null;
        Trial completed = trial.withVerdict(verdict, level.getGameTime(), punishment);
        crimes.putTrial(completed);
        crimes.putReport(report.withStatus(ReportStatus.TRIAL_COMPLETE));
        if (verdict == TrialVerdict.GUILTY && punishment != null) {
            PunishmentExecutor.execute(punishment, report, level);
        }
        TrialVerdict announced = verdict;
        src.sendSuccess(() -> Component.literal(
                "Forced verdict §a" + announced.name() + "§r on §f" + reportId), false);
        return 1;
    }
}
