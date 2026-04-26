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
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Health.ActiveCondition;
import tterrag1112.life_in_the_village.Npc.Health.HealthCondition;
import tterrag1112.life_in_the_village.Npc.Health.HealthTicker;

import java.util.Locale;
import java.util.UUID;

/**
 * {@code /health <npc>} per spec line 213. Subcommands:
 *
 * <ul>
 *   <li>{@code /health show <npc>} — list active conditions + constitution</li>
 *   <li>{@code /health add  <npc> <condition> [severity]}</li>
 *   <li>{@code /health treat <npc> <condition> [healer]}</li>
 *   <li>{@code /health clear <npc>} — remove all conditions</li>
 * </ul>
 */
public final class HealthDebugCommand {

    private HealthDebugCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("health")
                .then(Commands.literal("show")
                        .then(Commands.argument("npc", UuidArgument.uuid())
                                .executes(HealthDebugCommand::handleShow)))
                .then(Commands.literal("add")
                        .then(Commands.argument("npc", UuidArgument.uuid())
                                .then(Commands.argument("condition", StringArgumentType.word())
                                        .suggests((c, b) -> {
                                            for (HealthCondition v : HealthCondition.values())
                                                b.suggest(v.name());
                                            return b.buildFuture();
                                        })
                                        .executes(HealthDebugCommand::handleAdd)
                                        .then(Commands.argument("severity",
                                                        IntegerArgumentType.integer(1, 5))
                                                .executes(HealthDebugCommand::handleAdd)))))
                .then(Commands.literal("treat")
                        .then(Commands.argument("npc", UuidArgument.uuid())
                                .then(Commands.argument("condition", StringArgumentType.word())
                                        .suggests((c, b) -> {
                                            for (HealthCondition v : HealthCondition.values())
                                                b.suggest(v.name());
                                            return b.buildFuture();
                                        })
                                        .executes(HealthDebugCommand::handleTreat)
                                        .then(Commands.argument("healer", UuidArgument.uuid())
                                                .executes(HealthDebugCommand::handleTreat)))))
                .then(Commands.literal("clear")
                        .then(Commands.argument("npc", UuidArgument.uuid())
                                .executes(HealthDebugCommand::handleClear)))
        );
    }

    // ── Handlers ────────────────────────────────────────────────────────────

    private static int handleShow(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        UUID npcId = UuidArgument.getUuid(ctx, "npc");
        TownspersonMob npc = TownspersonMob.findByUUID(level, npcId).orElse(null);
        if (npc == null) { src.sendFailure(Component.literal("No NPC " + npcId)); return 0; }
        StringBuilder sb = new StringBuilder("§e=== Health for ");
        sb.append(npc.getNpcName()).append(" §7(constitution=").append(npc.getHealth().constitution())
                .append(", workMod=").append(String.format(Locale.ROOT, "%.2f",
                        npc.getHealth().workEfficiencyModifier())).append(") ===");
        for (ActiveCondition c : npc.getHealth().active()) {
            sb.append(String.format(Locale.ROOT,
                    "%n  §c%-22s§r sev §f%d§r %s expires §f%d",
                    c.type().name(), c.severity(),
                    c.treated() ? "§a[treated]§r" : "§7[untreated]§r",
                    c.resolutionTick()));
        }
        if (npc.getHealth().active().isEmpty()) sb.append("\n  §a(healthy)");
        src.sendSuccess(() -> Component.literal(sb.toString()).withStyle(ChatFormatting.WHITE), false);
        return 1;
    }

    private static int handleAdd(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        UUID npcId = UuidArgument.getUuid(ctx, "npc");
        TownspersonMob npc = TownspersonMob.findByUUID(level, npcId).orElse(null);
        if (npc == null) { src.sendFailure(Component.literal("No NPC " + npcId)); return 0; }
        HealthCondition type;
        try {
            type = HealthCondition.valueOf(
                    StringArgumentType.getString(ctx, "condition").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            src.sendFailure(Component.literal("Unknown condition")); return 0;
        }
        int severity = 2;
        try { severity = IntegerArgumentType.getInteger(ctx, "severity"); }
        catch (IllegalArgumentException ignored) {}
        HealthTicker.addCondition(npc, type, severity, level.getGameTime());
        src.sendSuccess(() -> Component.literal(
                "§aAdded §c" + type.name() + "§r sev §f" + severity
                        + "§r to §f" + npc.getNpcName()), false);
        return 1;
    }

    private static int handleTreat(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        UUID npcId = UuidArgument.getUuid(ctx, "npc");
        TownspersonMob npc = TownspersonMob.findByUUID(level, npcId).orElse(null);
        if (npc == null) { src.sendFailure(Component.literal("No NPC " + npcId)); return 0; }
        HealthCondition type;
        try {
            type = HealthCondition.valueOf(
                    StringArgumentType.getString(ctx, "condition").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            src.sendFailure(Component.literal("Unknown condition")); return 0;
        }
        UUID healerId = npcId;
        try { healerId = UuidArgument.getUuid(ctx, "healer"); }
        catch (IllegalArgumentException ignored) {}
        boolean ok = npc.getHealth().markTreated(type, healerId, level.getGameTime());
        if (!ok) { src.sendFailure(Component.literal("They don't have that condition.")); return 0; }
        src.sendSuccess(() -> Component.literal(
                "§aTreated §c" + type.name() + "§r on §f" + npc.getNpcName()), false);
        return 1;
    }

    private static int handleClear(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        UUID npcId = UuidArgument.getUuid(ctx, "npc");
        TownspersonMob npc = TownspersonMob.findByUUID(level, npcId).orElse(null);
        if (npc == null) { src.sendFailure(Component.literal("No NPC " + npcId)); return 0; }
        for (HealthCondition c : HealthCondition.values()) npc.getHealth().remove(c);
        src.sendSuccess(() -> Component.literal(
                "§aCleared all conditions on §f" + npc.getNpcName()), false);
        return 1;
    }
}
