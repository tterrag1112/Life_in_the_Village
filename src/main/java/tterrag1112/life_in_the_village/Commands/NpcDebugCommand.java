package tterrag1112.life_in_the_village.Commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Traits.DisplayedTrait;
import tterrag1112.life_in_the_village.Npc.Traits.TraitAxis;
import tterrag1112.life_in_the_village.Npc.Traits.TraitVector;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Root {@code /npc} debug command. Each NPC subsystem adds its own
 * subcommand here (traits, memory, knowledge, mood, skills, offices, ...).
 * Phase 0 ships with {@code /npc traits <uuid>}.
 */
public final class NpcDebugCommand {

    private NpcDebugCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("npc")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("traits")
                        .then(Commands.argument("uuid", UuidArgument.uuid())
                                .executes(NpcDebugCommand::handleTraits)))
        );
    }

    private static int handleTraits(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        UUID id = UuidArgument.getUuid(ctx, "uuid");

        Optional<TownspersonMob> found = TownspersonMob.findByUUID(level, id);
        if (found.isEmpty()) {
            src.sendFailure(Component.literal("No NPC with UUID " + id + " found in this level."));
            return 0;
        }
        TownspersonMob npc = found.get();
        TraitVector vector = npc.getTraitVector();

        StringBuilder sb = new StringBuilder();
        sb.append("§e=== Traits: ").append(displayName(npc)).append(" §7(").append(id).append(")§e ===\n");

        // Raw values, always in enum order — easy to diff across save/load.
        for (TraitAxis axis : TraitAxis.values()) {
            float v = vector.get(axis);
            sb.append(String.format(Locale.ROOT,
                    "  %-12s %+.3f %s%n",
                    axis.name(), v, intensityTag(v)));
        }

        // Significant-trait labels (what the UI will surface in later phases).
        List<DisplayedTrait> significant = vector.significantTraits();
        sb.append("§6Displayed: ");
        if (significant.isEmpty()) {
            sb.append("§7(none; all axes below ±").append(TraitVector.DISPLAY_THRESHOLD).append(")");
        } else {
            for (int i = 0; i < significant.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(significant.get(i).label());
            }
        }

        src.sendSuccess(() -> Component.literal(sb.toString()).withStyle(ChatFormatting.WHITE), false);
        return 1;
    }

    private static String intensityTag(float v) {
        float mag = Math.abs(v);
        if (mag >= TraitVector.EMPHATIC_THRESHOLD) return "§c[EMPHATIC]";
        if (mag >= TraitVector.DISPLAY_THRESHOLD)  return "§e[NORMAL]";
        return "§8[neutral]";
    }

    private static String displayName(TownspersonMob npc) {
        return npc.getCustomName() != null
                ? npc.getCustomName().getString()
                : npc.getName().getString();
    }
}
