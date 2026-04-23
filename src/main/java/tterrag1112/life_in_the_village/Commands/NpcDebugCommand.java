package tterrag1112.life_in_the_village.Commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
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
import tterrag1112.life_in_the_village.Npc.Memory.MemoryType;
import tterrag1112.life_in_the_village.Npc.Memory.NpcMemory;
import tterrag1112.life_in_the_village.Npc.Memory.NpcMemoryLog;
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
 */
public final class NpcDebugCommand {

    private static final long TICKS_PER_DAY = 24000L;

    private NpcDebugCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("npc")
                .requires(src -> src.hasPermission(2))

                // ── /npc traits <uuid> ───────────────────────────────────────
                .then(Commands.literal("traits")
                        .then(Commands.argument("uuid", UuidArgument.uuid())
                                .executes(NpcDebugCommand::handleTraits)))

                // ── /npc memory <uuid> ───────────────────────────────────────
                // ── /npc memory add <uuid> <type> <subjectUuid> <value> <summary>
                // ── /npc memory decay <uuid> <days> ──────────────────────────
                .then(Commands.literal("memory")
                        .then(Commands.argument("uuid", UuidArgument.uuid())
                                .executes(NpcDebugCommand::handleMemoryList))
                        .then(Commands.literal("add")
                                .then(Commands.argument("uuid", UuidArgument.uuid())
                                        .then(Commands.argument("type", StringArgumentType.word())
                                                .suggests((c, b) -> {
                                                    for (MemoryType t : MemoryType.values()) b.suggest(t.name());
                                                    return b.buildFuture();
                                                })
                                                .then(Commands.argument("subject", UuidArgument.uuid())
                                                        .then(Commands.argument("initialValue",
                                                                        IntegerArgumentType.integer(1, 100))
                                                                .then(Commands.argument("summary",
                                                                                StringArgumentType.greedyString())
                                                                        .executes(NpcDebugCommand::handleMemoryAdd)))))))
                        .then(Commands.literal("decay")
                                .then(Commands.argument("uuid", UuidArgument.uuid())
                                        .then(Commands.argument("days", FloatArgumentType.floatArg(0f, 3650f))
                                                .executes(NpcDebugCommand::handleMemoryDecay)))))
        );
    }

    // =========================================================================
    // Traits
    // =========================================================================

    private static int handleTraits(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        UUID id = UuidArgument.getUuid(ctx, "uuid");
        TownspersonMob npc = resolveOrFail(src, id);
        if (npc == null) return 0;

        TraitVector vector = npc.getTraitVector();

        StringBuilder sb = new StringBuilder();
        sb.append("§e=== Traits: ").append(displayName(npc)).append(" §7(").append(id).append(")§e ===\n");

        for (TraitAxis axis : TraitAxis.values()) {
            float v = vector.get(axis);
            sb.append(String.format(Locale.ROOT,
                    "  %-12s %+.3f %s%n",
                    axis.name(), v, intensityTag(v)));
        }

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

    // =========================================================================
    // Memory
    // =========================================================================

    private static int handleMemoryList(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        UUID id = UuidArgument.getUuid(ctx, "uuid");
        TownspersonMob npc = resolveOrFail(src, id);
        if (npc == null) return 0;

        NpcMemoryLog log = npc.getMemory();
        long now = src.getLevel().getGameTime();

        StringBuilder sb = new StringBuilder();
        sb.append("§e=== Memory: ").append(displayName(npc))
                .append(" §7(").append(id).append(")§e  [")
                .append(log.size()).append("/").append(NpcMemoryLog.MAX_ENTRIES).append("] ===");

        if (log.isEmpty()) {
            sb.append("\n§7(log is empty)");
        } else {
            for (NpcMemory m : log.byCurrentValueDesc()) {
                long daysSince = (now - m.tick()) / TICKS_PER_DAY;
                sb.append(String.format(Locale.ROOT,
                        "%n  %s%s §f%-20s §7cur=%5.1f / init=%3d  %3dd ago  §f%s",
                        m.pinned() ? "§6[PIN] " : "       ",
                        polarityTag(m),
                        m.type().name(),
                        m.currentValue(),
                        m.initialValue(),
                        daysSince,
                        m.summary()));
                if (!m.participantIds().isEmpty()) {
                    sb.append(" §8(participants: ");
                    for (int i = 0; i < m.participantIds().size(); i++) {
                        if (i > 0) sb.append(", ");
                        sb.append(m.participantIds().get(i).toString(), 0, 8);
                    }
                    sb.append(")");
                }
            }
        }

        src.sendSuccess(() -> Component.literal(sb.toString()).withStyle(ChatFormatting.WHITE), false);
        return 1;
    }

    private static String polarityTag(NpcMemory m) {
        return switch (m.type().polarity()) {
            case 1 -> "§a+";
            case -1 -> "§c−";
            default -> "§7·";
        };
    }

    private static int handleMemoryAdd(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        UUID id = UuidArgument.getUuid(ctx, "uuid");
        TownspersonMob npc = resolveOrFail(src, id);
        if (npc == null) return 0;

        String typeName = StringArgumentType.getString(ctx, "type").toUpperCase(Locale.ROOT);
        MemoryType type;
        try {
            type = MemoryType.valueOf(typeName);
        } catch (IllegalArgumentException e) {
            src.sendFailure(Component.literal("Unknown MemoryType: " + typeName));
            return 0;
        }
        UUID subject = UuidArgument.getUuid(ctx, "subject");
        int initialValue = IntegerArgumentType.getInteger(ctx, "initialValue");
        String summary = StringArgumentType.getString(ctx, "summary");

        NpcMemory memory = NpcMemory.create(
                type,
                List.of(subject),
                src.getLevel().getGameTime(),
                initialValue,
                summary);

        boolean stored = npc.getMemory().add(memory);
        if (!stored) {
            src.sendFailure(Component.literal("Log full of pinned memories — entry dropped."));
            return 0;
        }
        src.sendSuccess(() -> Component.literal(
                "Added " + type.name() + " (" + initialValue + ", "
                        + (memory.pinned() ? "pinned" : "unpinned")
                        + ") to " + displayName(npc)), false);
        return 1;
    }

    private static int handleMemoryDecay(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        UUID id = UuidArgument.getUuid(ctx, "uuid");
        TownspersonMob npc = resolveOrFail(src, id);
        if (npc == null) return 0;

        float days = FloatArgumentType.getFloat(ctx, "days");
        NpcMemoryLog log = npc.getMemory();
        int before = log.size();
        log.decayAll(days);
        int evicted = log.removeExpired();
        int remaining = log.size();
        src.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "Advanced decay %.2f day(s): %d entr%s before, %d evicted, %d remaining.",
                days, before, before == 1 ? "y" : "ies", evicted, remaining)), false);
        return 1;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static TownspersonMob resolveOrFail(CommandSourceStack src, UUID id) {
        ServerLevel level = src.getLevel();
        Optional<TownspersonMob> found = TownspersonMob.findByUUID(level, id);
        if (found.isEmpty()) {
            src.sendFailure(Component.literal("No NPC with UUID " + id + " found in this level."));
            return null;
        }
        return found.get();
    }

    private static String displayName(TownspersonMob npc) {
        return npc.getCustomName() != null
                ? npc.getCustomName().getString()
                : npc.getName().getString();
    }
}
