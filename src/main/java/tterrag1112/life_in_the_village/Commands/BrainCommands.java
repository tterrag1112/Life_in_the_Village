package tterrag1112.life_in_the_village.Commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.ExpirableValue;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.schedule.Activity;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@code /lit brain dump <selector>} — prints the targeted
 * {@link TownspersonMob}'s Brain state (active activities, non-empty
 * memories, schedule expectation). Phase 6.0 inspection helper; the
 * vanilla {@code /data get entity} returns the raw codec form which
 * is not great for development.
 *
 * <p>Cost: report is only built when the command runs.
 */
public final class BrainCommands {

    private BrainCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("lit")
                .then(Commands.literal("brain")
                        .then(Commands.literal("dump")
                                .then(Commands.argument("target", EntityArgument.entity())
                                        .executes(BrainCommands::dump))))
        );
    }

    private static int dump(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Entity target = EntityArgument.getEntity(ctx, "target");
        if (!(target instanceof TownspersonMob npc)) {
            ctx.getSource().sendFailure(Component.literal(
                    "Target must be a TownspersonMob (got " + target.getType() + ")"));
            return 0;
        }

        Brain<TownspersonMob> brain = npc.getBrain();
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        long dayTime = level.getDayTime() % 24000L;

        src.sendSuccess(() -> Component.literal("=== Brain dump for "
                + npc.getDisplayName().getString() + " (" + npc.getUUID() + ") ==="), false);
        src.sendSuccess(() -> Component.literal("DayTime: " + dayTime), false);

        // Active activities
        Set<Activity> active = brain.getActiveActivities();
        src.sendSuccess(() -> Component.literal("Active activities: " + active), false);

        // Schedule's expectation at current day-time
        if (brain.getSchedule() != null) {
            Activity expected = brain.getSchedule().getActivityAt((int) dayTime);
            src.sendSuccess(() -> Component.literal("Schedule expects: " + expected), false);
        }

        // Running behaviors
        var running = brain.getRunningBehaviors();
        src.sendSuccess(() -> Component.literal("Running behaviors (" + running.size() + "):"),
                false);
        for (var bc : running) {
            src.sendSuccess(() -> Component.literal("  - " + bc.getClass().getSimpleName()
                    + " [" + bc.getStatus() + "]"), false);
        }

        // All non-empty memories
        src.sendSuccess(() -> Component.literal("Memories:"), false);
        Map<MemoryModuleType<?>, Optional<? extends ExpirableValue<?>>> memories =
                brain.getMemories();
        int count = 0;
        for (var entry : memories.entrySet()) {
            Optional<? extends ExpirableValue<?>> v = entry.getValue();
            if (v == null || v.isEmpty()) continue;
            count++;
            ResourceLocation key = BuiltInRegistries.MEMORY_MODULE_TYPE.getKey(entry.getKey());
            ExpirableValue<?> ev = v.get();
            String val = String.valueOf(ev.getValue());
            String ttl = ev.canExpire() ? " (ttl=" + ev.getTimeToLive() + ")" : "";
            final int idx = count;
            final String line = "  [" + idx + "] " + key + " = " + val + ttl;
            src.sendSuccess(() -> Component.literal(line), false);
        }
        if (count == 0) {
            src.sendSuccess(() -> Component.literal("  (none populated)"), false);
        }

        return 1;
    }
}
