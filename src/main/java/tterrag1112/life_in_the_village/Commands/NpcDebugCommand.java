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
import tterrag1112.life_in_the_village.Npc.Knowledge.KnowledgeCategory;
import tterrag1112.life_in_the_village.Npc.Knowledge.KnowledgeEntry;
import tterrag1112.life_in_the_village.Npc.Knowledge.KnowledgeSource;
import tterrag1112.life_in_the_village.Npc.Knowledge.NpcKnowledgeLedger;
import tterrag1112.life_in_the_village.Npc.Knowledge.RumorMutator;
import tterrag1112.life_in_the_village.Npc.Memory.MemoryType;
import tterrag1112.life_in_the_village.Npc.Memory.NpcMemory;
import tterrag1112.life_in_the_village.Npc.Memory.NpcMemoryLog;
import tterrag1112.life_in_the_village.Npc.Mood.MoodEvent;
import tterrag1112.life_in_the_village.Npc.Mood.MoodTrigger;
import tterrag1112.life_in_the_village.Npc.Mood.NpcMoodState;
import tterrag1112.life_in_the_village.Npc.Office.OfficeRegistry;
import tterrag1112.life_in_the_village.Npc.Skills.ProfessionSkills;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;
import tterrag1112.life_in_the_village.Npc.Skills.SkillComponent;
import tterrag1112.life_in_the_village.Npc.Traits.DisplayedTrait;
import tterrag1112.life_in_the_village.Npc.Traits.TraitAxis;
import tterrag1112.life_in_the_village.Npc.Traits.TraitVector;

import java.util.List;
import java.util.Locale;
import java.util.Map;
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

                // ── /npc knowledge <uuid> ────────────────────────────────────
                // ── /npc knowledge add <uuid> <topic> <category> <fidelity> <source> <content...>
                // ── /npc knowledge mutate <text> <mutatorUuid> ───────────────
                .then(Commands.literal("knowledge")
                        .then(Commands.argument("uuid", UuidArgument.uuid())
                                .executes(NpcDebugCommand::handleKnowledgeList))
                        .then(Commands.literal("add")
                                .then(Commands.argument("uuid", UuidArgument.uuid())
                                        .then(Commands.argument("topic", StringArgumentType.word())
                                                .then(Commands.argument("category", StringArgumentType.word())
                                                        .suggests((c, b) -> {
                                                            for (KnowledgeCategory k : KnowledgeCategory.values()) b.suggest(k.name());
                                                            return b.buildFuture();
                                                        })
                                                        .then(Commands.argument("fidelity", FloatArgumentType.floatArg(0f, 1f))
                                                                .then(Commands.argument("source", StringArgumentType.word())
                                                                        .suggests((c, b) -> {
                                                                            for (KnowledgeSource s : KnowledgeSource.values()) b.suggest(s.name());
                                                                            return b.buildFuture();
                                                                        })
                                                                        .then(Commands.argument("content", StringArgumentType.greedyString())
                                                                                .executes(NpcDebugCommand::handleKnowledgeAdd))))))))
                        .then(Commands.literal("mutate")
                                .then(Commands.argument("text", StringArgumentType.string())
                                        .then(Commands.argument("mutatorUuid", UuidArgument.uuid())
                                                .executes(NpcDebugCommand::handleKnowledgeMutate)))))

                // ── /npc mood <uuid> ─────────────────────────────────────────
                // ── /npc mood trigger <uuid> <triggerName> ───────────────────
                // ── /npc mood decay <uuid> <days> ────────────────────────────
                .then(Commands.literal("mood")
                        .then(Commands.argument("uuid", UuidArgument.uuid())
                                .executes(NpcDebugCommand::handleMoodList))
                        .then(Commands.literal("trigger")
                                .then(Commands.argument("uuid", UuidArgument.uuid())
                                        .then(Commands.argument("trigger", StringArgumentType.word())
                                                .suggests((c, b) -> {
                                                    for (MoodTrigger t : MoodTrigger.values()) b.suggest(t.name());
                                                    return b.buildFuture();
                                                })
                                                .executes(NpcDebugCommand::handleMoodTrigger))))
                        .then(Commands.literal("decay")
                                .then(Commands.argument("uuid", UuidArgument.uuid())
                                        .then(Commands.argument("days", FloatArgumentType.floatArg(0f, 3650f))
                                                .executes(NpcDebugCommand::handleMoodDecay)))))

                // ── /npc skills <uuid> ───────────────────────────────────────
                // ── /npc skills add <uuid> <skill> <xp> ──────────────────────
                // ── /npc skills set <uuid> <skill> <xp> ──────────────────────
                .then(Commands.literal("skills")
                        .then(Commands.argument("uuid", UuidArgument.uuid())
                                .executes(NpcDebugCommand::handleSkillsList))
                        .then(Commands.literal("add")
                                .then(Commands.argument("uuid", UuidArgument.uuid())
                                        .then(Commands.argument("skill", StringArgumentType.word())
                                                .suggests((c, b) -> {
                                                    for (Skill s : Skill.values()) b.suggest(s.name());
                                                    return b.buildFuture();
                                                })
                                                .then(Commands.argument("xp", FloatArgumentType.floatArg(0f, 40000f))
                                                        .executes(NpcDebugCommand::handleSkillsAdd)))))
                        .then(Commands.literal("set")
                                .then(Commands.argument("uuid", UuidArgument.uuid())
                                        .then(Commands.argument("skill", StringArgumentType.word())
                                                .suggests((c, b) -> {
                                                    for (Skill s : Skill.values()) b.suggest(s.name());
                                                    return b.buildFuture();
                                                })
                                                .then(Commands.argument("xp", FloatArgumentType.floatArg(0f, 40000f))
                                                        .executes(NpcDebugCommand::handleSkillsSet)))))

                // ── /npc offices <uuid> ──────────────────────────────────────
                .then(Commands.literal("offices")
                        .then(Commands.argument("uuid", UuidArgument.uuid())
                                .executes(NpcDebugCommand::handleOfficesList)))
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

    // =========================================================================
    // Knowledge
    // =========================================================================

    private static int handleKnowledgeList(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        UUID id = UuidArgument.getUuid(ctx, "uuid");
        TownspersonMob npc = resolveOrFail(src, id);
        if (npc == null) return 0;

        NpcKnowledgeLedger ledger = npc.getKnowledge();
        long now = src.getLevel().getGameTime();

        StringBuilder sb = new StringBuilder();
        sb.append("§e=== Knowledge: ").append(displayName(npc))
                .append(" §7(").append(id).append(")§e  [")
                .append(ledger.size()).append("/").append(NpcKnowledgeLedger.MAX_ENTRIES).append("] ===");

        if (ledger.isEmpty()) {
            sb.append("\n§7(ledger is empty)");
            src.sendSuccess(() -> Component.literal(sb.toString()).withStyle(ChatFormatting.WHITE), false);
            return 1;
        }

        Map<KnowledgeCategory, List<KnowledgeEntry>> grouped = ledger.groupedByCategory();
        for (Map.Entry<KnowledgeCategory, List<KnowledgeEntry>> group : grouped.entrySet()) {
            List<KnowledgeEntry> list = group.getValue();
            if (list.isEmpty()) continue;
            sb.append("\n§6").append(group.getKey().name()).append(" §7(").append(list.size()).append(")");
            for (KnowledgeEntry e : list) {
                long daysSince = (now - e.acquiredTick()) / TICKS_PER_DAY;
                String contentSnippet = e.content().length() > 60
                        ? e.content().substring(0, 57) + "..."
                        : e.content();
                sb.append(String.format(Locale.ROOT,
                        "%n  %s §fφ=%.2f§7 %-14s  %4dd ago  §f%s §7[%s]",
                        fidelityTag(e.fidelity()),
                        e.fidelity(),
                        e.source().name(),
                        daysSince,
                        e.topic(),
                        contentSnippet));
                if (e.sensitive()) sb.append(" §c[sensitive]");
            }
        }

        src.sendSuccess(() -> Component.literal(sb.toString()).withStyle(ChatFormatting.WHITE), false);
        return 1;
    }

    private static String fidelityTag(float f) {
        if (f >= KnowledgeEntry.RELIABLE_THRESHOLD) return "§a[RELIABLE]";
        if (f <  KnowledgeEntry.VAGUE_THRESHOLD)    return "§8[vague]    ";
        return                                             "§e[middling] ";
    }

    private static int handleKnowledgeAdd(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        UUID id = UuidArgument.getUuid(ctx, "uuid");
        TownspersonMob npc = resolveOrFail(src, id);
        if (npc == null) return 0;

        String topic = StringArgumentType.getString(ctx, "topic");
        String categoryName = StringArgumentType.getString(ctx, "category").toUpperCase(Locale.ROOT);
        float fidelity = FloatArgumentType.getFloat(ctx, "fidelity");
        String sourceName = StringArgumentType.getString(ctx, "source").toUpperCase(Locale.ROOT);
        String content = StringArgumentType.getString(ctx, "content");

        KnowledgeCategory category;
        KnowledgeSource source;
        try {
            category = KnowledgeCategory.valueOf(categoryName);
        } catch (IllegalArgumentException e) {
            src.sendFailure(Component.literal("Unknown KnowledgeCategory: " + categoryName));
            return 0;
        }
        try {
            source = KnowledgeSource.valueOf(sourceName);
        } catch (IllegalArgumentException e) {
            src.sendFailure(Component.literal("Unknown KnowledgeSource: " + sourceName));
            return 0;
        }

        KnowledgeEntry entry = KnowledgeEntry.create(
                topic, category, fidelity, source,
                src.getLevel().getGameTime(), content, null);
        boolean stored = npc.getKnowledge().add(entry);
        src.sendSuccess(() -> Component.literal(stored
                        ? "Added/upgraded knowledge of '" + entry.topic() + "' (φ=" + entry.fidelity() + ")"
                        : "Existing knowledge had equal-or-higher fidelity — new entry dropped."),
                false);
        return stored ? 1 : 0;
    }

    /**
     * Test harness for {@link RumorMutator}. Uses stable test defaults for
     * the other seed inputs (topic="debug", acquiredTick=0) so that the
     * output depends only on the supplied text and mutator UUID — running
     * the same command twice must produce identical output.
     */
    private static int handleKnowledgeMutate(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        String text = StringArgumentType.getString(ctx, "text");
        UUID mutatorUuid = UuidArgument.getUuid(ctx, "mutatorUuid");

        // Mutation probability maxes out when fidelity is low; use a fixed
        // low value in the test harness so operations actually fire.
        float testFidelity = 0.30f;
        String mutated = RumorMutator.mutate(text, testFidelity, "debug", 0L, mutatorUuid);
        long seed = RumorMutator.deriveSeed("debug", 0L, mutatorUuid);

        src.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "§eseed=§f%016x §7(topic=debug, acquiredTick=0, mutator=%s)%n§eIn: §f%s%n§eOut:§f%s",
                seed, mutatorUuid, text, mutated)),
                false);
        return 1;
    }

    // =========================================================================
    // Mood
    // =========================================================================

    private static int handleMoodList(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        UUID id = UuidArgument.getUuid(ctx, "uuid");
        TownspersonMob npc = resolveOrFail(src, id);
        if (npc == null) return 0;

        NpcMoodState mood = npc.getMood();
        long now = src.getLevel().getGameTime();
        long daysSinceUpdate = mood.lastUpdateTick() == 0L
                ? -1L
                : (now - mood.lastUpdateTick()) / TICKS_PER_DAY;

        StringBuilder sb = new StringBuilder();
        sb.append("§e=== Mood: ").append(displayName(npc))
                .append(" §7(").append(id).append(")§e ===");
        sb.append(String.format(Locale.ROOT,
                "%n  Value:    §f%+6.2f §7%s",
                mood.value(), categoryTag(mood.category())));
        sb.append(String.format(Locale.ROOT,
                "%n  Baseline: §f%+6.2f",
                mood.baseline()));
        sb.append(String.format(Locale.ROOT,
                "%n  Last update: §f%s",
                daysSinceUpdate < 0 ? "(never)" : daysSinceUpdate + "d ago"));
        if (mood.isGrieving(now)) {
            sb.append("\n  §c[GRIEVING — decay halved]");
        }

        List<MoodEvent> events = mood.recentEvents();
        if (events.isEmpty()) {
            sb.append("\n  §7(no recent events)");
        } else {
            sb.append("\n  §6Recent events:");
            for (MoodEvent e : events) {
                long days = (now - e.tick()) / TICKS_PER_DAY;
                sb.append(String.format(Locale.ROOT,
                        "%n    %s §f%-26s %+4d  §7%dd ago",
                        e.magnitude() >= 0 ? "§a+" : "§c−",
                        e.trigger().name(),
                        e.magnitude(),
                        days));
            }
        }

        src.sendSuccess(() -> Component.literal(sb.toString()).withStyle(ChatFormatting.WHITE), false);
        return 1;
    }

    private static String categoryTag(tterrag1112.life_in_the_village.Npc.Mood.MoodCategory c) {
        return switch (c) {
            case ELATED     -> "§a[ELATED]";
            case CONTENT    -> "§a[CONTENT]";
            case NEUTRAL    -> "§7[NEUTRAL]";
            case TROUBLED   -> "§e[TROUBLED]";
            case DISTRESSED -> "§c[DISTRESSED]";
        };
    }

    private static int handleMoodTrigger(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        UUID id = UuidArgument.getUuid(ctx, "uuid");
        TownspersonMob npc = resolveOrFail(src, id);
        if (npc == null) return 0;

        String triggerName = StringArgumentType.getString(ctx, "trigger").toUpperCase(Locale.ROOT);
        MoodTrigger trigger;
        try {
            trigger = MoodTrigger.valueOf(triggerName);
        } catch (IllegalArgumentException e) {
            src.sendFailure(Component.literal("Unknown MoodTrigger: " + triggerName));
            return 0;
        }

        NpcMoodState mood = npc.getMood();
        float before = mood.value();
        int applied = mood.apply(trigger, npc.getTraitVector(), src.getLevel().getGameTime());
        float after = mood.value();

        src.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "Applied §f%s§r to §f%s§r: §7before=§f%+.2f §7after=§f%+.2f §7Δ=§f%+d§7 (raw=%+d × multiplier)",
                trigger.name(),
                displayName(npc),
                before, after, applied, trigger.defaultMagnitude())),
                false);
        return 1;
    }

    private static int handleMoodDecay(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        UUID id = UuidArgument.getUuid(ctx, "uuid");
        TownspersonMob npc = resolveOrFail(src, id);
        if (npc == null) return 0;

        float days = FloatArgumentType.getFloat(ctx, "days");
        NpcMoodState mood = npc.getMood();
        float before = mood.value();
        mood.decay(days, src.getLevel().getGameTime());
        float after = mood.value();
        src.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "Decayed §f%.2f§r day(s): §7before=§f%+.2f §7after=§f%+.2f §7baseline=§f%+.2f",
                days, before, after, mood.baseline())),
                false);
        return 1;
    }

    // =========================================================================
    // Skills
    // =========================================================================

    private static int handleSkillsList(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        UUID id = UuidArgument.getUuid(ctx, "uuid");
        TownspersonMob npc = resolveOrFail(src, id);
        if (npc == null) return 0;

        SkillComponent sk = npc.getSkills();
        var profSkills = ProfessionSkills.of(npc.getProfession()).orElse(null);

        StringBuilder sb = new StringBuilder();
        sb.append("§e=== Skills: ").append(displayName(npc))
                .append(" §7(").append(id).append(") §6profession=§f")
                .append(npc.getProfession().name()).append("§e ===");
        for (Skill s : Skill.values()) {
            int level = sk.getLevel(s);
            float xp = sk.getXp(s);
            String tag;
            if (profSkills != null && profSkills.primary() == s)        tag = "§a[PRIMARY]  ";
            else if (profSkills != null && profSkills.secondary() == s) tag = "§e[SECONDARY]";
            else                                                         tag = "§7           ";
            sb.append(String.format(Locale.ROOT,
                    "%n  %s §f%-9s §7lv=§f%3d §8(%s) §7xp=§f%7.1f§7 / %.0f",
                    tag, s.name(), level, SkillComponent.tierFor(level),
                    xp, SkillComponent.xpRequiredFor(SkillComponent.MAX_LEVEL)));
        }
        src.sendSuccess(() -> Component.literal(sb.toString()).withStyle(ChatFormatting.WHITE), false);
        return 1;
    }

    private static int handleSkillsAdd(CommandContext<CommandSourceStack> ctx) {
        return handleSkillsMutation(ctx, true);
    }

    private static int handleSkillsSet(CommandContext<CommandSourceStack> ctx) {
        return handleSkillsMutation(ctx, false);
    }

    // =========================================================================
    // Offices (cross-entity walker)
    // =========================================================================

    private static int handleOfficesList(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        UUID uuid = UuidArgument.getUuid(ctx, "uuid");
        long now = src.getLevel().getGameTime();

        List<OfficeRegistry.Match> matches = OfficeRegistry.findOfficesHeldBy(uuid, src.getLevel());

        StringBuilder sb = new StringBuilder();
        sb.append("§e=== Offices held by §f").append(uuid).append("§e ===");
        if (matches.isEmpty()) {
            sb.append("\n§7(none)");
        } else {
            for (OfficeRegistry.Match m : matches) {
                long days = m.holding().daysRemaining(now);
                String termDisplay = days < 0 ? "indefinite" : days + "d remaining";
                sb.append(String.format(Locale.ROOT,
                        "%n  §6%s §f%s §7(%s) §e%s§7 — %s",
                        m.orgType().name().toLowerCase(Locale.ROOT),
                        m.orgDisplayName(),
                        m.orgId(),
                        m.holding().officeId(),
                        termDisplay));
            }
        }
        src.sendSuccess(() -> Component.literal(sb.toString()).withStyle(ChatFormatting.WHITE), false);
        return 1;
    }

    private static int handleSkillsMutation(CommandContext<CommandSourceStack> ctx, boolean add) {
        CommandSourceStack src = ctx.getSource();
        UUID id = UuidArgument.getUuid(ctx, "uuid");
        TownspersonMob npc = resolveOrFail(src, id);
        if (npc == null) return 0;

        String skillName = StringArgumentType.getString(ctx, "skill").toUpperCase(Locale.ROOT);
        Skill skill;
        try {
            skill = Skill.valueOf(skillName);
        } catch (IllegalArgumentException e) {
            src.sendFailure(Component.literal("Unknown Skill: " + skillName));
            return 0;
        }
        float xp = FloatArgumentType.getFloat(ctx, "xp");
        long now = src.getLevel().getGameTime();
        SkillComponent sk = npc.getSkills();
        float beforeXp = sk.getXp(skill);
        int beforeLv  = sk.getLevel(skill);

        if (add) sk.addXp(skill, xp, now);
        else     sk.setXp(skill, xp, now);

        float afterXp = sk.getXp(skill);
        int afterLv   = sk.getLevel(skill);
        src.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "%s §f%s§r: §7xp §f%.1f§7→§f%.1f §7(lv §f%d§7→§f%d§7, %s)",
                add ? "Added XP to" : "Set XP for",
                skill.name(), beforeXp, afterXp, beforeLv, afterLv,
                SkillComponent.tierFor(afterLv))),
                false);
        return 1;
    }
}
