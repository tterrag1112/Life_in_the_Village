// src/main/java/tterrag1112/life_in_the_village/Commands/PlacementFailuresDebugCommand.java
package tterrag1112.life_in_the_village.Commands;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import tterrag1112.life_in_the_village.Kingdom.Placement.PlacementFailureRecorder;

import java.util.Map;

/**
 * /liv debug placement-failures        — print the tally
 * /liv debug placement-failures reset  — reset counters
 */
public class PlacementFailuresDebugCommand {
    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("liv")
                .then(Commands.literal("debug")
                        .then(Commands.literal("placement-failures")
                                .executes(PlacementFailuresDebugCommand::run)
                                .then(Commands.literal("reset")
                                        .executes(ctx -> {
                                            PlacementFailureRecorder.resetAggregate();
                                            ctx.getSource().sendSuccess(
                                                    () -> Component.literal("Tally reset"),
                                                    false);
                                            return 1;
                                        })))));
    }

    private static int run(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        PlacementFailureRecorder.AggregateSnapshot snap = PlacementFailureRecorder.snapshot();

        StringBuilder sb = new StringBuilder();
        sb.append("Placement attempts: ").append(snap.totalAttempts())
                .append(" | failures: ").append(snap.totalFailures())
                .append(String.format(" (%.1f%%)", snap.failureRate() * 100))
                .append("\n");

        if (snap.perReason().isEmpty()) {
            sb.append("  (no recorded failures)");
        } else {
            sb.append("By reason:\n");
            snap.perReason().entrySet().stream()
                    .sorted(Map.Entry.<PlacementFailureRecorder.Reason, Integer>comparingByValue()
                            .reversed())
                    .forEach(e -> sb.append("  ")
                            .append(String.format("%-28s %d%n", e.getKey(), e.getValue())));
        }
        ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
    }
}