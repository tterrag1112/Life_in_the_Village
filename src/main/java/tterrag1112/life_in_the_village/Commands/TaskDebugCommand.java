package tterrag1112.life_in_the_village.Commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Tasks.Assignment;
import tterrag1112.life_in_the_village.Npc.Tasks.DoTaskBehavior;
import tterrag1112.life_in_the_village.Npc.Tasks.Fulfillment;
import tterrag1112.life_in_the_village.Npc.Tasks.FulfillmentRegistry;
import tterrag1112.life_in_the_village.Npc.Tasks.Fulfillments;
import tterrag1112.life_in_the_village.Npc.Tasks.IssuerRef;
import tterrag1112.life_in_the_village.Npc.Tasks.NpcActor;
import tterrag1112.life_in_the_village.Npc.Tasks.Objective;
import tterrag1112.life_in_the_village.Npc.Tasks.Task;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskActor;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskBoard;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskContext;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskMigration;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskSavedData;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskSystemConfig;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * {@code /litv tasks ...} — runtime toggle and visibility for the Task System.
 *
 * <ul>
 *   <li>{@code /litv tasks enable} / {@code disable} — calls
 *       {@link TaskSystemConfig#setEnabled} and reports the new state.
 *       Because {@link DoTaskBehavior} is added to an NPC's brain at
 *       construction time (gated by {@link TaskSystemConfig#ENABLED}),
 *       toggling at runtime only affects NPCs spawned <em>after</em> the
 *       toggle — already-loaded NPCs keep their current brain until they
 *       are reloaded (relog or kill+respawn).</li>
 *   <li>{@code /litv tasks status} — prints the current flag value and
 *       the set of migrated professions.</li>
 *   <li>{@code /litv tasks inspect} — finds the nearest {@link TownspersonMob}
 *       within 16 blocks of the source; prints its profession, migration
 *       status, claimed task (if any), and the ranked eligible tasks from
 *       all its boards. Folds the board dump into the inspect output — a
 *       separate {@code board} subcommand is not needed because the NPC's
 *       membership refs give direct board access.</li>
 * </ul>
 *
 * <p>Debug-only: no change to task selection, scoring, or execution logic.</p>
 */
public final class TaskDebugCommand {

    private static final double INSPECT_RADIUS = 16.0;
    private static final int    MAX_RANKED_TASKS = 10;

    private TaskDebugCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("litv")
                .then(Commands.literal("tasks")
                        .then(Commands.literal("enable")
                                .executes(TaskDebugCommand::handleEnable))
                        .then(Commands.literal("disable")
                                .executes(TaskDebugCommand::handleDisable))
                        .then(Commands.literal("status")
                                .executes(TaskDebugCommand::handleStatus))
                        .then(Commands.literal("inspect")
                                .executes(TaskDebugCommand::handleInspect))));
    }

    // ── /litv tasks enable ────────────────────────────────────────────────────

    private static int handleEnable(CommandContext<CommandSourceStack> ctx) {
        TaskSystemConfig.setEnabled(true);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "§aTask system ENABLED.§r\n"
                + "§7Note: already-loaded NPCs retain their current brain. "
                + "Relog or kill+respawn an NPC to pick up DoTaskBehavior."), false);
        return 1;
    }

    // ── /litv tasks disable ───────────────────────────────────────────────────

    private static int handleDisable(CommandContext<CommandSourceStack> ctx) {
        TaskSystemConfig.setEnabled(false);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "§cTask system DISABLED.§r\n"
                + "§7Note: already-loaded NPCs retain their current brain. "
                + "Relog or kill+respawn an NPC to remove DoTaskBehavior."), false);
        return 1;
    }

    // ── /litv tasks status ────────────────────────────────────────────────────

    private static int handleStatus(CommandContext<CommandSourceStack> ctx) {
        boolean on = TaskSystemConfig.isEnabled();
        String flag = on ? "§aENABLED§r" : "§cDISABLED§r";
        Set<tterrag1112.life_in_the_village.Profession.Profession> migrated =
                TaskMigration.migrated();
        StringBuilder sb = new StringBuilder();
        sb.append("§e=== Task System Status ===\n");
        sb.append("  flag      = ").append(flag).append("\n");
        sb.append("  migrated  = ").append(migrated).append("\n");
        sb.append("  JVM prop  = ").append(
                System.getProperty(TaskSystemConfig.SYSTEM_PROPERTY, "(not set)"));
        ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
    }

    // ── /litv tasks inspect ───────────────────────────────────────────────────

    private static int handleInspect(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        if (!(src.getLevel() instanceof ServerLevel level)) {
            src.sendFailure(Component.literal("/litv tasks inspect must be run server-side."));
            return 0;
        }

        // Find nearest TownspersonMob within INSPECT_RADIUS.
        var pos = src.getPosition();
        AABB area = new AABB(pos, pos).inflate(INSPECT_RADIUS);
        List<TownspersonMob> hits = level.getEntitiesOfClass(
                TownspersonMob.class, area, TownspersonMob::isAlive);
        if (hits.isEmpty()) {
            src.sendFailure(Component.literal(
                    "No TownspersonMob within " + (int) INSPECT_RADIUS
                    + " blocks. Stand next to an NPC."));
            return 0;
        }
        TownspersonMob npc = hits.get(0);
        double bestSq = npc.distanceToSqr(pos);
        for (TownspersonMob h : hits) {
            double sq = h.distanceToSqr(pos);
            if (sq < bestSq) { bestSq = sq; npc = h; }
        }

        StringBuilder sb = new StringBuilder();
        appendInspect(sb, level, npc);
        final TownspersonMob finalNpc = npc;
        src.sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
    }

    private static void appendInspect(StringBuilder sb, ServerLevel level, TownspersonMob npc) {
        var prof = npc.getProfession();
        boolean migrated = TaskMigration.isMigrated(prof);
        // T2 — a household member's food tasks live on its HOUSEHOLD board even
        // when its profession isn't migrated, so show boards for either path.
        boolean householdMigrated = TaskMigration.ownsHousehold() && npc.getHouseId().isPresent();
        boolean flagOn   = TaskSystemConfig.isEnabled();

        sb.append("§e=== Task inspect: §f").append(npc.getNpcName())
          .append("§e (").append(shortId(npc.getUUID())).append(")§e ===\n");
        sb.append("  profession = §f").append(prof.name()).append("§r\n");
        sb.append("  migrated   = ").append(migrated ? "§ayes§r" : "§cno§r").append("\n");
        sb.append("  flag       = ").append(flagOn ? "§aON§r" : "§cOFF§r");
        if (!flagOn) {
            sb.append(" §7(DoTaskBehavior inactive — enable with /litv tasks enable)§r");
        }
        sb.append("\n");

        sb.append("  household  = ")
          .append(householdMigrated ? "§atask-owned§r" : "§7n/a§r").append("\n");

        if (!migrated && !householdMigrated) {
            sb.append("§7  (profession not migrated and no task-owned household — "
                    + "no task boards)\n");
            return;
        }

        // ── Active task: scan running behaviors for DoTaskBehavior ──────────
        DoTaskBehavior running = null;
        for (var beh : npc.getBrain().getRunningBehaviors()) {
            if (beh instanceof DoTaskBehavior dtb) { running = dtb; break; }
        }

        sb.append("  activeTask = ");
        if (running != null && running.activeTask() != null) {
            Task t = running.activeTask();
            sb.append("§a").append(objectiveSummary(t.objective()))
              .append("§r (").append(t.priority().tier().name())
              .append("/").append(String.format(Locale.ROOT, "%.2f", t.priority().urgency()))
              .append(") status=").append(t.assignment().status().name())
              .append(" claimants=").append(t.assignment().claimants().size())
              .append("/").append(t.assignment().maxClaimants()).append("\n");
            sb.append("  fulfillment= §f").append(running.activeExecutorName()).append("§r\n");
            // best-scored fulfillment at current moment (for informational score):
            TaskContext ctx2 = new TaskContext(level, npc);
            TaskActor actor2 = new NpcActor(npc.getUUID());
            Fulfillment best = bestFulfillment(t, actor2, ctx2);
            if (best != null) {
                double score = best.score(t, actor2, ctx2);
                sb.append("  score      = §f")
                  .append(String.format(Locale.ROOT, "%.3f", score)).append("§r\n");
            }
        } else if (flagOn) {
            sb.append("§7(none — DoTaskBehavior not running this tick)§r\n");
        } else {
            sb.append("§7(flag off — DoTaskBehavior not in brain)§r\n");
        }

        // ── Board dump: ranked eligible tasks across all boards ──────────────
        TaskSavedData data = TaskSavedData.get(level);
        TaskContext ctx = new TaskContext(level, npc);
        TaskActor actor = new NpcActor(npc.getUUID());

        sb.append("  boards:\n");
        boolean anyBoard = false;
        for (IssuerRef ref : ctx.memberships()) {
            var boardOpt = data.boardIfPresent(ref);
            if (boardOpt.isEmpty()) {
                sb.append("    ").append(ref.level().name()).append(":")
                  .append(shortId(ref.id())).append(" = §7(no board)§r\n");
                continue;
            }
            anyBoard = true;
            TaskBoard board = boardOpt.get();
            List<Task> all = board.all();
            List<Task> eligible = board.rankedEligibleFor(actor, ctx);
            sb.append("    ").append(ref.level().name()).append(":")
              .append(shortId(ref.id()))
              .append(" (").append(all.size()).append(" tasks, ")
              .append(eligible.size()).append(" eligible):\n");

            // Show up to MAX_RANKED_TASKS eligible tasks in rank order.
            int shown = 0;
            for (Task t : eligible) {
                if (shown >= MAX_RANKED_TASKS) {
                    sb.append("      ... (").append(eligible.size() - shown).append(" more)\n");
                    break;
                }
                Assignment a = t.assignment();
                sb.append("      [").append(t.priority().tier().name().charAt(0)).append("] ")
                  .append(objectiveSummary(t.objective()))
                  .append(" urgency=").append(String.format(Locale.ROOT, "%.2f", t.priority().urgency()))
                  .append(" status=").append(a.status().name())
                  .append(" claimants=").append(a.claimants().size())
                  .append("/").append(a.maxClaimants()).append("\n");
                shown++;
            }
            if (eligible.isEmpty()) {
                sb.append("      §7(no eligible tasks)§r\n");
            }
        }
        if (!anyBoard) {
            sb.append("    §7(no boards found for this NPC's memberships)§r\n");
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Human-readable summary of an Objective variant. */
    static String objectiveSummary(Objective obj) {
        return switch (obj) {
            case Objective.ProvideItem p ->
                "provide " + itemName(p.item()) + "×" + p.qty();
            case Objective.MaintainStock m ->
                "stock " + itemName(m.item()) + " target=" + m.target();
            case Objective.Acquire a ->
                "acquire " + itemName(a.item()) + "×" + a.qty();
            case Objective.Deliver d ->
                "deliver " + itemName(d.item()) + "×" + d.qty();
            case Objective.SellSurplus s ->
                "sell surplus " + itemName(s.item());
            case Objective.Staff st ->
                "staff role=" + st.roleId();
            case Objective.PerformService ps ->
                "service=" + ps.kind();
        };
    }

    private static String itemName(net.minecraft.world.item.Item item) {
        var key = BuiltInRegistries.ITEM.getKey(item);
        // Strip the "minecraft:" prefix for readability.
        String path = key.getPath();
        return key.getNamespace().equals("minecraft") ? path : key.toString();
    }

    private static Fulfillment bestFulfillment(Task task, TaskActor actor, TaskContext ctx) {
        FulfillmentRegistry reg = Fulfillments.shared();
        Fulfillment best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (Fulfillment f : reg.strategiesFor(task.objective())) {
            if (!f.canFulfill(task, actor, ctx)) continue;
            double s = f.score(task, actor, ctx);
            if (s > bestScore) { bestScore = s; best = f; }
        }
        return best;
    }

    private static String shortId(java.util.UUID id) {
        return id.toString().substring(0, 8);
    }
}
