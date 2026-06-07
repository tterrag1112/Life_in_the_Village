package tterrag1112.life_in_the_village.Commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.schedule.Activity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.Farmer.FarmRole;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.ProfessionRoleManager;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Life_in_the_village;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production.FarmerBehavior;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Building;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Phase 6.3.3.o.2 — {@code /liv npc info <uuid_or_nearest>} debug
 * command. Dumps an NPC's profession / role / assignment state in
 * a single chat block so the operator can diagnose
 * "why isn't this farmer working?" without an attach-debugger pass.
 *
 * <p>Two argument forms:</p>
 * <ul>
 *   <li>{@code /liv npc info <uuid-prefix-or-full>} — resolves by
 *       UUID; 8-char prefix accepted, ambiguity fails with the
 *       candidate list.</li>
 *   <li>{@code /liv npc info nearest} — picks the closest
 *       {@link TownspersonMob} to the command source within 64
 *       blocks. Convenient when standing next to the stuck NPC.</li>
 * </ul>
 *
 * <p>Output is multi-line, indented, and groups fields into
 * "identity", "assignment", "farm-specific" (only when the NPC is
 * a FARMER / FARMHAND), and "skills" sections.</p>
 */
@EventBusSubscriber(modid = Life_in_the_village.MODID)
public final class NpcInfoCommand {

    private static final double NEAREST_RADIUS = 64.0;

    private NpcInfoCommand() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("liv")
                        .then(Commands.literal("npc")
                                .then(Commands.literal("info")
                                        .then(Commands.argument("target",
                                                        StringArgumentType.string())
                                                .executes(ctx -> info(ctx,
                                                        StringArgumentType.getString(ctx, "target")))))
                                // Liveliness L0 — focused "why idle" readout:
                                // DayPhase + active Activity + running behaviors
                                // + the recorded blockingReason.
                                .then(Commands.literal("brain")
                                        .requires(src -> src.hasPermission(2))
                                        .then(Commands.argument("target",
                                                        StringArgumentType.string())
                                                .executes(ctx -> brain(ctx,
                                                        StringArgumentType.getString(ctx, "target"))))))
        );
    }

    /**
     * Liveliness L0 — {@code /liv npc brain <uuid_or_nearest>}. The
     * "current behaviour + why the others didn't fire" readout: resolved
     * DayPhase, active Activity, running Brain behaviors, and the blocking
     * reason the gated profession behaviors recorded on decline.
     */
    private static int brain(CommandContext<CommandSourceStack> ctx, String targetArg) {
        if (!(ctx.getSource().getLevel() instanceof ServerLevel level)) {
            ctx.getSource().sendFailure(Component.literal(
                    "/liv npc brain must be run on a server level."));
            return 0;
        }
        TownspersonMob npc = resolveNpc(ctx, level, targetArg);
        if (npc == null) return 0;

        StringBuilder sb = new StringBuilder();
        appendIdentity(sb, npc);
        appendSchedule(sb, level, npc);
        appendActivityState(sb, npc);
        appendBrain(sb, npc);
        ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
    }

    /** Resolved schedule context — the common idle cause at a glance. */
    private static void appendSchedule(StringBuilder sb, ServerLevel level,
                                       TownspersonMob npc) {
        sb.append("Schedule:\n");
        sb.append("  profession = ").append(npc.getProfession().name()).append("\n");
        sb.append("  isWorkTime = ").append(npc.isWorkTime()).append("\n");
        var phase = tterrag1112.life_in_the_village.Npc.Schedule
                .ScheduleResolver.phaseAt(npc, level.getDayTime());
        sb.append("  dayPhase   = ").append(phase).append("\n");
    }

    /** Current activity + the recorded "why not working" reason (debug-only). */
    private static void appendActivityState(StringBuilder sb, TownspersonMob npc) {
        var state = npc.getActivityState();
        sb.append("Activity:\n");
        sb.append("  current        = ")
                .append(state.toDisplayString().isBlank() ? "(idle)" : state.toDisplayString())
                .append("\n");
        sb.append("  blockingReason = ")
                .append(state.isBlocked() ? state.blockingReason()
                        : "(none — working or cleanly idle)")
                .append("\n");
    }

    private static int info(CommandContext<CommandSourceStack> ctx, String targetArg) {
        if (!(ctx.getSource().getLevel() instanceof ServerLevel level)) {
            ctx.getSource().sendFailure(Component.literal(
                    "/liv npc info must be run on a server level."));
            return 0;
        }
        TownspersonMob npc = resolveNpc(ctx, level, targetArg);
        if (npc == null) return 0;

        StringBuilder sb = new StringBuilder();
        appendIdentity(sb, npc);
        appendAssignment(sb, level, npc);
        if (isFarmerProfession(npc.getProfession())) {
            appendFarmFields(sb, npc);
        }
        appendBrain(sb, npc);
        appendSkills(sb, npc);
        ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
    }

    /**
     * Phase 6.3.3.p.1 — Brain-level diagnostic. Shows the active
     * non-CORE activity, the list of currently-running behaviors,
     * and a reflection-probed dump of the registered behaviors
     * keyed by activity. The reflection probe is the only way to
     * tell whether a profession-specific behavior is actually
     * REGISTERED vs. simply not running this tick — the symptom
     * that motivated p.1 is FarmerBehavior never being checked at
     * all, which means it's not in the Brain at all.
     *
     * <p>If reflection fails (Mojang renames the field, mod
     * conflict, etc.) the probe degrades gracefully: a single line
     * notes the failure and the rest of the output stays useful.</p>
     */
    private static void appendBrain(StringBuilder sb, TownspersonMob npc) {
        sb.append("Brain:\n");
        Brain<TownspersonMob> brain = npc.getBrain();
        sb.append("  activeActivity = ")
                .append(brain.getActiveNonCoreActivity()
                        .map(Activity::getName).orElse("(none)"))
                .append("\n");

        // Running behaviors — the live signal during this tick.
        var running = brain.getRunningBehaviors();
        sb.append("  runningBehaviors (").append(running.size()).append("):\n");
        if (running.isEmpty()) {
            sb.append("    (none — brain has no behavior running this tick)\n");
        } else {
            for (var bc : running) {
                sb.append("    - ").append(bc.getClass().getSimpleName()).append("\n");
            }
        }

        // Direct FarmerBehavior probe — answers the p.1 question
        // for FARMER NPCs without needing to scan the registered set.
        if (isFarmerProfession(npc.getProfession())) {
            FarmerBehavior fb = npc.getBehavior(FarmerBehavior.class);
            sb.append("  FarmerBehavior running? = ")
                    .append(fb != null ? "yes" : "no (not in runningBehaviors)")
                    .append("\n");
        }

        // Registered-behaviors dump via reflection. This is the
        // load-bearing diagnostic: if FarmerBehavior isn't in this
        // map at all for a FARMER, the Brain was never configured
        // for FARMER's profession (likely because makeBrain ran
        // when profession was still NONE).
        appendRegisteredBehaviors(sb, brain);
    }

    @SuppressWarnings("unchecked")
    private static void appendRegisteredBehaviors(StringBuilder sb,
                                                   Brain<TownspersonMob> brain) {
        try {
            Field f = Brain.class.getDeclaredField("availableBehaviorsByPriority");
            f.setAccessible(true);
            Object raw = f.get(brain);
            if (!(raw instanceof Map<?, ?> byPriority)) {
                sb.append("  registeredBehaviors: (reflection field unexpected type)\n");
                return;
            }
            sb.append("  registeredBehaviors (per priority → per activity):\n");
            if (byPriority.isEmpty()) {
                sb.append("    (none — Brain is empty)\n");
                return;
            }
            for (var prioEntry : byPriority.entrySet()) {
                Map<Activity, Set<?>> byActivity = (Map<Activity, Set<?>>) prioEntry.getValue();
                for (var actEntry : byActivity.entrySet()) {
                    sb.append("    P").append(prioEntry.getKey()).append(" ")
                            .append(actEntry.getKey().getName())
                            .append(": ");
                    boolean first = true;
                    for (Object bc : actEntry.getValue()) {
                        if (!first) sb.append(", ");
                        sb.append(bc.getClass().getSimpleName());
                        first = false;
                    }
                    sb.append("\n");
                }
            }
        } catch (Throwable t) {
            sb.append("  registeredBehaviors: (reflection failed: ")
                    .append(t.getClass().getSimpleName()).append(")\n");
        }
    }

    // ── Section builders ─────────────────────────────────────────────────────

    private static void appendIdentity(StringBuilder sb, TownspersonMob npc) {
        sb.append("NPC ").append(shortId(npc.getUUID()))
                .append(" (").append(npc.getNpcName()).append(")\n");
        sb.append("  profession     = ").append(npc.getProfession().name()).append("\n");
        var specComp = npc.getSpecializationComponent();
        String spec = specComp.currentId().map(id -> id.toString()).orElse("(none)");
        sb.append("  specialization = ").append(spec);
        if (specComp.isLocked()) sb.append(" [LOCKED]");
        sb.append("\n");
    }

    private static void appendAssignment(StringBuilder sb, ServerLevel level,
                                         TownspersonMob npc) {
        sb.append("Assignment:\n");
        VillageSavedData data = VillageSavedData.get(level);
        UUID buildingId = npc.getAssignedBuildingId().orElse(null);
        if (buildingId == null) {
            sb.append("  assignedBuildingId = (none — checkExtraStartConditions rejects)\n");
        } else {
            Building b = data.getBuildingById(buildingId).orElse(null);
            sb.append("  assignedBuildingId = ").append(buildingId);
            if (b == null) {
                sb.append("  (UUID resolves nothing — stale assignment)\n");
            } else {
                sb.append("\n    type    = ").append(b.getType().name())
                        .append("\n    name    = ").append(b.getName())
                        .append("\n    origin  = ").append(b.getShape().getOrigin().toShortString())
                        .append("\n");
            }
        }
        sb.append("  assignedVillage    = ")
                .append(npc.getAssignedVillageName().orElse("(none)"))
                .append("\n");
        sb.append("  businessId         = ")
                .append(npc.getBusinessId().map(NpcInfoCommand::shortId).orElse("(none)"))
                .append("\n");
        sb.append("  isWorkTime         = ").append(npc.isWorkTime()).append("\n");
    }

    private static void appendFarmFields(StringBuilder sb, TownspersonMob npc) {
        sb.append("Farm:\n");
        FarmRole role = ProfessionRoleManager.getRole(npc, FarmRole.class);
        sb.append("  farmRole       = ").append(role == null ? "(null)" : role.name()).append("\n");
        sb.append("  assignedPlotId = ")
                .append(npc.getAssignedPlotId().map(NpcInfoCommand::shortId).orElse("(none)"))
                .append("\n");
        npc.getRetirementState().mentorTargetId().ifPresent(id ->
                sb.append("  mentorTargetId = ").append(shortId(id)).append("\n"));
    }

    private static void appendSkills(StringBuilder sb, TownspersonMob npc) {
        sb.append("Skills:\n");
        appendSkillLine(sb, npc, Skill.FARMING);
        appendSkillLine(sb, npc, Skill.CROP_FARMING);
        appendSkillLine(sb, npc, Skill.ORCHARDING);
        appendSkillLine(sb, npc, Skill.ANIMAL_HUSBANDRY);
        appendSkillLine(sb, npc, Skill.BEEKEEPING);
        appendSkillLine(sb, npc, Skill.SHEPHERDING);
        appendSkillLine(sb, npc, Skill.SOCIAL);
        appendSkillLine(sb, npc, Skill.COMBAT);
        appendSkillLine(sb, npc, Skill.BAKING);
        appendSkillLine(sb, npc, Skill.CRAFTING);
        appendSkillLine(sb, npc, Skill.PASTRY);
        appendSkillLine(sb, npc, Skill.LITERACY);
        appendSkillLine(sb, npc, Skill.MILLING);
        appendSkillLine(sb, npc, Skill.MAGIC);
        appendSkillLine(sb, npc, Skill.MEDICINE);
        appendSkillLine(sb, npc, Skill.BLACKSMITHING);


    }

    private static void appendSkillLine(StringBuilder sb, TownspersonMob npc, Skill skill) {
        sb.append("  ").append(String.format("%-18s", skill.name()))
                .append(" = lvl ").append(npc.getSkills().getLevel(skill))
                .append(" (").append(String.format("%.1f", npc.getSkills().getXp(skill)))
                .append(" xp)\n");
    }

    // ── Resolution + helpers ─────────────────────────────────────────────────

    private static TownspersonMob resolveNpc(CommandContext<CommandSourceStack> ctx,
                                             ServerLevel level, String arg) {
        if ("nearest".equalsIgnoreCase(arg)) {
            var pos = ctx.getSource().getPosition();
            net.minecraft.world.phys.AABB area =
                    new net.minecraft.world.phys.AABB(pos, pos).inflate(NEAREST_RADIUS);
            List<TownspersonMob> hits = level.getEntitiesOfClass(
                    TownspersonMob.class, area, npc -> npc.isAlive());
            if (hits.isEmpty()) {
                ctx.getSource().sendFailure(Component.literal(
                        "No TownspersonMob within " + (int) NEAREST_RADIUS + " blocks."));
                return null;
            }
            TownspersonMob closest = hits.get(0);
            double bestSq = closest.distanceToSqr(pos);
            for (TownspersonMob h : hits) {
                double sq = h.distanceToSqr(pos);
                if (sq < bestSq) { bestSq = sq; closest = h; }
            }
            return closest;
        }
        try {
            UUID id = UUID.fromString(arg);
            var entity = level.getEntity(id);
            if (!(entity instanceof TownspersonMob t)) {
                ctx.getSource().sendFailure(Component.literal(
                        "UUID " + id + " is not a loaded TownspersonMob in this level."));
                return null;
            }
            return t;
        } catch (IllegalArgumentException ignore) { /* fall through to prefix */ }
        // Prefix path — scan loaded NPCs near the source for a UUID-prefix match.
        var pos = ctx.getSource().getPosition();
        net.minecraft.world.phys.AABB area =
                new net.minecraft.world.phys.AABB(pos, pos).inflate(1024.0);
        List<TownspersonMob> all = level.getEntitiesOfClass(
                TownspersonMob.class, area,
                npc -> npc.getUUID().toString().startsWith(arg));
        if (all.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal(
                    "No NPC UUID matches '" + arg + "' within 1024 blocks of source."));
            return null;
        }
        if (all.size() > 1) {
            StringBuilder m = new StringBuilder("Ambiguous prefix '" + arg + "' — matches:");
            for (TownspersonMob n : all) m.append("\n  ").append(n.getUUID());
            ctx.getSource().sendFailure(Component.literal(m.toString()));
            return null;
        }
        return all.get(0);
    }

    private static boolean isFarmerProfession(Profession p) {
        return p == Profession.FARMER || p == Profession.FARMHAND;
    }

    private static String shortId(UUID id) {
        return id == null ? "(null)" : id.toString().substring(0, 8);
    }
}
