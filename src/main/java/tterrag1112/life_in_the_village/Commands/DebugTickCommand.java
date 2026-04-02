package tterrag1112.life_in_the_village.Commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import tterrag1112.life_in_the_village.Events.TickSubsystemRegistry;

import java.util.Map;

/**
 * Debug commands for inspecting server tick performance.
 *
 * <h3>Commands</h3>
 * <ul>
 *   <li>{@code /liv debug timings} — shows last tick duration per subsystem</li>
 *   <li>{@code /liv debug errors} — shows error counts per subsystem</li>
 *   <li>{@code /liv debug errors reset} — resets error counters</li>
 * </ul>
 *
 * Registration: call {@code DebugTickCommand.register(dispatcher)} from
 * your {@code RegisterCommandsEvent} handler.
 */
public class DebugTickCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("liv")
                        .then(Commands.literal("debug")

                                .then(Commands.literal("timings")
                                        .executes(DebugTickCommand::showTimings))

                                .then(Commands.literal("errors")
                                        .executes(DebugTickCommand::showErrors)
                                        .then(Commands.literal("reset")
                                                .executes(DebugTickCommand::resetErrors)))
                        )
        );
    }

    private static int showTimings(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        Map<String, Long> timings = TickSubsystemRegistry.getAllTimingsMicros();

        if (timings.isEmpty()) {
            src.sendSuccess(() -> Component.literal(
                            "No timing data yet — wait for at least one server tick."),
                    false);
            return 0;
        }

        src.sendSuccess(() -> Component.literal(
                "=== Tick Subsystem Timings (last tick) ==="), false);

        long totalMicros = 0;
        for (Map.Entry<String, Long> entry : timings.entrySet()) {
            String name = entry.getKey();
            long micros = entry.getValue();
            totalMicros += micros;

            int errors = TickSubsystemRegistry.getErrorCount(name);
            String errorSuffix = errors > 0
                    ? " [" + errors + " errors]"
                    : "";

            String line = String.format("  %-20s %,6d µs%s",
                    name, micros, errorSuffix);

            src.sendSuccess(() -> Component.literal(line), false);
        }

        long total = totalMicros;
        src.sendSuccess(() -> Component.literal(
                String.format("  %-20s %,6d µs", "TOTAL", total)), false);

        return timings.size();
    }

    private static int showErrors(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();

        boolean anyErrors = false;
        for (String name : TickSubsystemRegistry.getSystemNames()) {
            int count = TickSubsystemRegistry.getErrorCount(name);
            if (count > 0) {
                anyErrors = true;
                src.sendSuccess(() -> Component.literal(
                        "  " + name + ": " + count + " errors"), false);
            }
        }

        if (!anyErrors) {
            src.sendSuccess(() -> Component.literal(
                    "No errors recorded across all subsystems."), false);
        }

        return 1;
    }

    private static int resetErrors(CommandContext<CommandSourceStack> ctx) {
        TickSubsystemRegistry.resetErrorCounts();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Error counters reset for all subsystems."), false);
        return 1;
    }
}