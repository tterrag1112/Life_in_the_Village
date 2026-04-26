package tterrag1112.life_in_the_village.Commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Health.HealthSavedData;
import tterrag1112.life_in_the_village.Npc.Health.Plague;
import tterrag1112.life_in_the_village.Npc.Health.PlagueScheduler;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.Locale;

/**
 * {@code /plague} debug subcommands per spec line 214.
 *
 * <ul>
 *   <li>{@code /plague start <village>} — force outbreak</li>
 *   <li>{@code /plague status <village>} — current state</li>
 *   <li>{@code /plague end <village>} — force resolution</li>
 * </ul>
 */
public final class PlagueDebugCommand {

    private PlagueDebugCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("plague")
                .then(Commands.literal("start")
                        .then(Commands.argument("village", StringArgumentType.string())
                                .executes(PlagueDebugCommand::handleStart)))
                .then(Commands.literal("status")
                        .then(Commands.argument("village", StringArgumentType.string())
                                .executes(PlagueDebugCommand::handleStatus)))
                .then(Commands.literal("end")
                        .then(Commands.argument("village", StringArgumentType.string())
                                .executes(PlagueDebugCommand::handleEnd)))
        );
    }

    private static int handleStart(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        Village v = villageOrFail(ctx, level, src);
        if (v == null) return 0;
        if (HealthSavedData.get(level).getActivePlague(v.getId()).isPresent()) {
            src.sendFailure(Component.literal("Plague already active in " + v.getName()));
            return 0;
        }
        Plague p = PlagueScheduler.start(level, v, level.getGameTime());
        src.sendSuccess(() -> Component.literal(
                "§cPlague started in §f" + v.getName() + "§r — id " + p.plagueId()), false);
        return 1;
    }

    private static int handleStatus(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        Village v = villageOrFail(ctx, level, src);
        if (v == null) return 0;
        Plague p = HealthSavedData.get(level).getActivePlague(v.getId()).orElse(null);
        if (p == null) {
            src.sendSuccess(() -> Component.literal(
                    "§a" + v.getName() + "§r has no active plague."), false);
            return 1;
        }
        long daysLeft = Math.max(0L,
                (p.endTick() - level.getGameTime()) / 24000L);
        StringBuilder sb = new StringBuilder("§e=== Plague in ")
                .append(v.getName()).append(" ===");
        sb.append(String.format(Locale.ROOT,
                "%n  §7stage:§f %s§7  infected:§f %d§7  recovered:§f %d§7  died:§f %d",
                p.stage().name(), p.infectedCount(),
                p.recoveredCount(), p.diedCount()));
        sb.append(String.format(Locale.ROOT,
                "%n  §7days remaining:§f %d§7  on-duty healer:§f %s",
                daysLeft, p.healerOnDuty().map(java.util.UUID::toString).orElse("none")));
        src.sendSuccess(() -> Component.literal(sb.toString())
                .withStyle(ChatFormatting.WHITE), false);
        return 1;
    }

    private static int handleEnd(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        Village v = villageOrFail(ctx, level, src);
        if (v == null) return 0;
        if (HealthSavedData.get(level).getActivePlague(v.getId()).isEmpty()) {
            src.sendFailure(Component.literal("No active plague in " + v.getName()));
            return 0;
        }
        PlagueScheduler.resolve(level, v.getId());
        src.sendSuccess(() -> Component.literal(
                "§aPlague resolved in §f" + v.getName()), false);
        return 1;
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
}
