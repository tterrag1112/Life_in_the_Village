package tterrag1112.life_in_the_village.Commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import tterrag1112.life_in_the_village.Village.Planning.V2.V2Settings;

/**
 * {@code /litv config adaptive_v2 <on|off|status>} — runtime toggle
 * for the V2 planner branch in {@code VillageSpawner.spawnVillage}.
 * Track A1a flag.
 */
public final class ConfigCommand {

    private ConfigCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("litv")
                .then(Commands.literal("config")
                        .then(Commands.literal("adaptive_v2")
                                .then(Commands.literal("on")
                                        .executes(ctx -> set(ctx, true)))
                                .then(Commands.literal("off")
                                        .executes(ctx -> set(ctx, false)))
                                .then(Commands.literal("status")
                                        .executes(ConfigCommand::status))
                                .executes(ConfigCommand::status))));
    }

    private static int set(CommandContext<CommandSourceStack> ctx, boolean value) {
        V2Settings.setAdaptiveV2(value);
        send(ctx, "adaptive_v2 = " + value);
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        send(ctx, "adaptive_v2 = " + V2Settings.adaptiveV2Enabled());
        return 1;
    }

    private static void send(CommandContext<CommandSourceStack> ctx, String msg) {
        ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
    }
}
