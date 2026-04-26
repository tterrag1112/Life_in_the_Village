package tterrag1112.life_in_the_village.Commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Laws.LawEnactment;
import tterrag1112.life_in_the_village.Npc.Laws.LawParams;
import tterrag1112.life_in_the_village.Npc.Laws.LawPopularity;
import tterrag1112.life_in_the_village.Npc.Laws.LawScheduleHooks;
import tterrag1112.life_in_the_village.Npc.Laws.LawTaxHooks;
import tterrag1112.life_in_the_village.Npc.Laws.VillageLaw;
import tterrag1112.life_in_the_village.Npc.Laws.VillagePolicy;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.Locale;
import java.util.Optional;

/**
 * {@code /law} debug subcommands per spec line 233:
 *
 * <ul>
 *   <li>{@code /law list <village>} — active laws + popularity.</li>
 *   <li>{@code /law enact <village> <law> [param=value ...]}</li>
 *   <li>{@code /law repeal <village> <law>}</li>
 *   <li>{@code /law popularity <village> <law>}</li>
 *   <li>{@code /law audit <village>} — show which subsystem values are
 *       currently affected (sanity-check that hooks fire).</li>
 * </ul>
 */
public final class LawDebugCommand {

    private LawDebugCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("law")
                .then(Commands.literal("list")
                        .then(Commands.argument("village", StringArgumentType.string())
                                .executes(LawDebugCommand::handleList)))
                .then(Commands.literal("enact")
                        .then(Commands.argument("village", StringArgumentType.string())
                                .then(Commands.argument("law", StringArgumentType.word())
                                        .suggests((c, b) -> { for (var l : VillageLaw.values()) b.suggest(l.name()); return b.buildFuture(); })
                                        .executes(LawDebugCommand::handleEnact)
                                        .then(Commands.argument("paramKey", StringArgumentType.word())
                                                .then(Commands.argument("paramValue", FloatArgumentType.floatArg())
                                                        .executes(LawDebugCommand::handleEnactWithParam))))))
                .then(Commands.literal("repeal")
                        .then(Commands.argument("village", StringArgumentType.string())
                                .then(Commands.argument("law", StringArgumentType.word())
                                        .suggests((c, b) -> { for (var l : VillageLaw.values()) b.suggest(l.name()); return b.buildFuture(); })
                                        .executes(LawDebugCommand::handleRepeal))))
                .then(Commands.literal("popularity")
                        .then(Commands.argument("village", StringArgumentType.string())
                                .then(Commands.argument("law", StringArgumentType.word())
                                        .suggests((c, b) -> { for (var l : VillageLaw.values()) b.suggest(l.name()); return b.buildFuture(); })
                                        .executes(LawDebugCommand::handlePopularity))))
                .then(Commands.literal("audit")
                        .then(Commands.argument("village", StringArgumentType.string())
                                .executes(LawDebugCommand::handleAudit)))
        );
    }

    // ── Handlers ───────────────────────────────────────────────────────────

    private static int handleList(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        Village v = villageOrFail(src, StringArgumentType.getString(ctx, "village"));
        if (v == null) return 0;
        VillagePolicy policy = v.getPolicy();
        StringBuilder sb = new StringBuilder();
        sb.append("§e=== Active laws — ").append(v.getName()).append(" ===");
        if (policy.activeLaws().isEmpty()) {
            sb.append("\n  §8(none)");
        } else {
            for (VillageLaw law : policy.activeLaws()) {
                int pop = LawPopularity.compute(law, v, VillageSavedData.get(src.getLevel()), src.getLevel());
                String suspended = policy.isSuspended(law) ? " §c[suspended]§r" : "";
                sb.append(String.format(Locale.ROOT,
                        "%n  §a%-30s§r popularity §f%+d§r%s%n      §7%s",
                        law.name(), pop, suspended, law.description()));
            }
        }
        src.sendSuccess(() -> Component.literal(sb.toString()).withStyle(ChatFormatting.WHITE), false);
        return 1;
    }

    private static int handleEnact(CommandContext<CommandSourceStack> ctx) {
        return doEnact(ctx, null, 0f);
    }

    private static int handleEnactWithParam(CommandContext<CommandSourceStack> ctx) {
        return doEnact(ctx,
                StringArgumentType.getString(ctx, "paramKey"),
                FloatArgumentType.getFloat(ctx, "paramValue"));
    }

    private static int doEnact(CommandContext<CommandSourceStack> ctx, String paramKey, float paramValue) {
        CommandSourceStack src = ctx.getSource();
        Village v = villageOrFail(src, StringArgumentType.getString(ctx, "village"));
        if (v == null) return 0;
        VillageLaw law = parseLawOrFail(src, StringArgumentType.getString(ctx, "law"));
        if (law == null) return 0;
        LawParams params = LawParams.withDefaults(law);
        if (paramKey != null) params = params.withNumeric(paramKey, paramValue);
        var actor = src.getPlayer();
        java.util.UUID actorId = actor == null ? null : actor.getUUID();
        LawEnactment.Outcome out = LawEnactment.enact(v, law, params, src.getLevel(), actorId);
        if (out.success()) {
            src.sendSuccess(() -> Component.literal(
                    "§aEnacted §f" + law.name() + "§a on §f" + v.getName()), false);
        } else {
            src.sendFailure(Component.literal(
                    "§cEnactment failed: §r" + out.reason()));
        }
        return out.success() ? 1 : 0;
    }

    private static int handleRepeal(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        Village v = villageOrFail(src, StringArgumentType.getString(ctx, "village"));
        if (v == null) return 0;
        VillageLaw law = parseLawOrFail(src, StringArgumentType.getString(ctx, "law"));
        if (law == null) return 0;
        var actor = src.getPlayer();
        java.util.UUID actorId = actor == null ? null : actor.getUUID();
        LawEnactment.Outcome out = LawEnactment.repeal(v, law, src.getLevel(), actorId);
        if (out.success()) {
            src.sendSuccess(() -> Component.literal(
                    "§aRepealed §f" + law.name() + "§a on §f" + v.getName()), false);
        } else {
            src.sendFailure(Component.literal(
                    "§cRepeal failed: §r" + out.reason()));
        }
        return out.success() ? 1 : 0;
    }

    private static int handlePopularity(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        Village v = villageOrFail(src, StringArgumentType.getString(ctx, "village"));
        if (v == null) return 0;
        VillageLaw law = parseLawOrFail(src, StringArgumentType.getString(ctx, "law"));
        if (law == null) return 0;
        int pop = LawPopularity.compute(law, v, VillageSavedData.get(src.getLevel()), src.getLevel());
        src.sendSuccess(() -> Component.literal(
                "§ePopularity of " + law.name() + " in " + v.getName() + ": §f" + pop
                        + " §7(base " + law.popularityBase() + ")"), false);
        return 1;
    }

    private static int handleAudit(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        Village v = villageOrFail(src, StringArgumentType.getString(ctx, "village"));
        if (v == null) return 0;
        ServerLevel level = src.getLevel();
        StringBuilder sb = new StringBuilder();
        sb.append("§e=== Law audit — ").append(v.getName()).append(" ===");
        sb.append(String.format(Locale.ROOT,
                "%n  §7Property tax mult §f%.2f", LawTaxHooks.propertyTaxMultiplier(v)));
        sb.append(String.format(Locale.ROOT,
                "%n  §7Market    tax mult §f%.2f", LawTaxHooks.marketTaxMultiplier(v)));
        sb.append(String.format(Locale.ROOT,
                "%n  §7Profits→treasury  §f%.2f", LawTaxHooks.profitsToTreasuryFraction(v)));
        // Per-profession subsidy breakdown for the four currently
        // covered professions (HEALER added when doc 21 lands).
        for (Profession p : new Profession[]{Profession.FARMER, Profession.BLACKSMITH,
                Profession.SCHOLAR, Profession.MERCHANT}) {
            long subsidy = LawTaxHooks.dailySubsidyForProfession(v, p);
            if (subsidy > 0) {
                sb.append(String.format(Locale.ROOT,
                        "%n  §7Subsidy/%s/day §f%d§7 bronze",
                        p.name(), subsidy));
            }
        }
        sb.append(String.format(Locale.ROOT,
                "%n  §7Festival mandatory §f%s",
                LawScheduleHooks.festivalMandatory(v)));
        sb.append(String.format(Locale.ROOT,
                "%n  §7Compulsory schooling §f%s",
                LawScheduleHooks.compulsorySchooling(v)));
        src.sendSuccess(() -> Component.literal(sb.toString()).withStyle(ChatFormatting.WHITE), false);
        return 1;
    }

    // ── Resolution helpers ────────────────────────────────────────────────

    private static Village villageOrFail(CommandSourceStack src, String name) {
        ServerLevel level = src.getLevel();
        Optional<Village> v = VillageSavedData.get(level).getVillageByName(name);
        if (v.isEmpty()) {
            src.sendFailure(Component.literal("No village named '" + name + "'"));
            return null;
        }
        return v.get();
    }

    private static VillageLaw parseLawOrFail(CommandSourceStack src, String name) {
        Optional<VillageLaw> law = VillageLaw.fromName(name);
        if (law.isEmpty()) {
            src.sendFailure(Component.literal("Unknown law: " + name));
            return null;
        }
        return law.get();
    }
}
