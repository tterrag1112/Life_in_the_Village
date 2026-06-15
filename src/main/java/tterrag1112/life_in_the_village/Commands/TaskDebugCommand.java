package tterrag1112.life_in_the_village.Commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.AABB;
import tterrag1112.life_in_the_village.Entities.HouseholdData;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Scribal.CommissionQueue;
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
import tterrag1112.life_in_the_village.Npc.Tasks.Household.HouseholdFood;
import tterrag1112.life_in_the_village.Npc.Tasks.Household.HouseholdTaskSource;
import tterrag1112.life_in_the_village.Npc.Tasks.Producer.ProducerSpecs;
import tterrag1112.life_in_the_village.Npc.Tasks.Producer.ProductionTaskSource;
import tterrag1112.life_in_the_village.Npc.Tasks.Producer.ProductionTaskSpec;
import tterrag1112.life_in_the_village.Npc.Tasks.Scribe.ScribeCommissionTaskSource;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

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
 *   <li>{@code /litv tasks gen} — force-runs the task sources for the
 *       nearest NPC RIGHT NOW, bypassing the flag, the work-schedule
 *       activity, and the 100-tick refresh throttle. Prints pre-gen
 *       diagnostics (why zero tasks might be produced), executes the
 *       generation, then dumps the resulting board state. Useful for
 *       definitively separating "generation is broken" from "the brain
 *       or schedule is never letting the dispatcher run".</li>
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
                                .executes(TaskDebugCommand::handleInspect))
                        .then(Commands.literal("gen")
                                .executes(TaskDebugCommand::handleGen))));
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

        TownspersonMob npc = findNearestNpc(src, level);
        if (npc == null) return 0;

        StringBuilder sb = new StringBuilder();
        appendInspect(sb, level, npc);
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

    // ── /litv tasks gen ───────────────────────────────────────────────────────

    private static int handleGen(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        if (!(src.getLevel() instanceof ServerLevel level)) {
            src.sendFailure(Component.literal("/litv tasks gen must be run server-side."));
            return 0;
        }

        TownspersonMob npc = findNearestNpc(src, level);
        if (npc == null) return 0;

        StringBuilder sb = new StringBuilder();
        appendGen(sb, level, npc);
        src.sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
    }

    private static void appendGen(StringBuilder sb, ServerLevel level, TownspersonMob npc) {
        Profession prof = npc.getProfession();
        boolean migrated = TaskMigration.isMigrated(prof);
        boolean flagOn   = TaskSystemConfig.isEnabled();
        TaskContext ctx  = new TaskContext(level, npc);

        // ── Header ────────────────────────────────────────────────────────────
        sb.append("§e=== /litv tasks gen: §f").append(npc.getNpcName())
          .append("§e (").append(shortId(npc.getUUID())).append(")§e ===\n");
        sb.append("  profession = §f").append(prof.name()).append("§r\n");
        sb.append("  uuid       = §f").append(npc.getUUID()).append("§r\n");
        sb.append("  migrated?  = ").append(migrated ? "§ayes§r" : "§cno§r").append("\n");
        sb.append("  flag       = ").append(flagOn ? "§aON§r" : "§cOFF§r");
        if (!flagOn) {
            sb.append(" §7(tasks will be written but dispatcher won't consume them until ON)§r");
        }
        sb.append("\n");

        // memberships
        sb.append("  memberships:\n");
        for (IssuerRef ref : ctx.memberships()) {
            sb.append("    ").append(ref.level().name()).append(":").append(ref.id()).append("\n");
        }

        // ── Pre-gen diagnostics ────────────────────────────────────────────────
        sb.append("\n§e--- Pre-gen diagnostics ---§r\n");
        boolean anySource = false;

        // Producer
        ProductionTaskSpec matchedSpec = null;
        for (ProductionTaskSpec spec : ProducerSpecs.ALL) {
            if (npc.getProfession() == spec.profession()) { matchedSpec = spec; break; }
        }
        if (matchedSpec != null) {
            anySource = true;
            sb.append("§7[Producer]§r profession=").append(prof.name())
              .append(" spec=").append(matchedSpec.getClass().getSimpleName()).append("\n");

            Optional<ProductionTaskSource> src = ProductionTaskSource.forNpc(level, npc, matchedSpec);
            if (src.isEmpty()) {
                sb.append("  §cassigned building of type §f").append(matchedSpec.buildingType().name())
                  .append("§c NOT FOUND§r — no producer tasks will generate\n");
            } else {
                sb.append("  building §a").append(matchedSpec.buildingType().name()).append(" found§r\n");

                // finals
                sb.append("  finalOutputs (ProvideItem gate: deficit > 0):\n");
                for (Item out : matchedSpec.finalOutputs()) {
                    int stock = BuildingStorageAccess.countItem(level,
                            getAssignedBuilding(level, npc, matchedSpec.buildingType()), out);
                    int quota = matchedSpec.quota(out);
                    int deficit = quota - stock;
                    String verdict = deficit > 0
                            ? "§aWILL emit§r (deficit=" + deficit + ")"
                            : "§7quota met — will NOT emit§r";
                    sb.append("    ").append(itemName(out))
                      .append("  stock=").append(stock)
                      .append(" quota=").append(quota)
                      .append("  ").append(verdict).append("\n");
                }

                // intermediates
                if (!matchedSpec.intermediateOutputs().isEmpty()) {
                    sb.append("  intermediateOutputs (MaintainStock gate: stock < quota-buffer):\n");
                    Building bldg = getAssignedBuilding(level, npc, matchedSpec.buildingType());
                    for (Item inter : matchedSpec.intermediateOutputs()) {
                        int stock = BuildingStorageAccess.countItem(level, bldg, inter);
                        int quota = matchedSpec.quota(inter);
                        int buffer = matchedSpec.buffer(inter);
                        int trigger = quota - buffer;
                        String verdict = stock < trigger
                                ? "§aWILL emit§r (below trigger=" + trigger + ")"
                                : "§7above trigger=" + trigger + " — will NOT emit§r";
                        sb.append("    ").append(itemName(inter))
                          .append("  stock=").append(stock)
                          .append(" quota=").append(quota).append(" buffer=").append(buffer)
                          .append("  ").append(verdict).append("\n");
                    }
                }
            }
        }

        // Scribe
        if (prof == Profession.SCRIBE) {
            anySource = true;
            sb.append("§7[Scribe]§r\n");
            // Replicate the two-line forNpc resolution to get the workshop building.
            Building workshop = npc.getAssignedBuildingId()
                    .flatMap(id -> VillageSavedData.get(level).getBuildingById(id))
                    .filter(b -> b.getType() == BuildingType.SCRIBE_WORKSHOP)
                    .orElse(null);
            if (workshop == null) {
                sb.append("  §cSCRIBE_WORKSHOP not found§r — no commission tasks will generate\n");
            } else {
                sb.append("  workshop §afound§r (").append(shortId(workshop.getId())).append(")\n");
                CommissionQueue queue = VillageSavedData.get(level)
                        .getOrCreateCommissionQueue(workshop.getId());
                int pending = queue.pendingCount();
                int total   = queue.all().size();
                String verdict = pending > 0
                        ? "§a" + pending + " PENDING§r → will emit " + pending + " task(s)"
                        : "§70 pending§r → no tasks will generate";
                sb.append("  CommissionQueue: total=").append(total)
                  .append(" pending=").append(pending).append("  ").append(verdict).append("\n");
            }
        }

        // Household
        boolean hasHouse = npc.getHouseId().isPresent();
        if (hasHouse) {
            anySource = true;
            sb.append("§7[Household]§r\n");
            UUID houseId = npc.getHouseId().get();
            VillageSavedData vsd = VillageSavedData.get(level);
            Building house = vsd.getBuildingById(houseId).orElse(null);
            if (house == null) {
                sb.append("  §chouse building not found in VillageSavedData§r\n");
            } else {
                HouseholdData hd = vsd.getHouseholdForBuilding(houseId).orElse(null);
                int familySize = hd != null ? Math.max(1, hd.getMemberNpcIds().size()) : 1;
                int target     = familySize * HouseholdFood.PER_MEMBER_THRESHOLD;
                int breadStock = BuildingStorageAccess.countItem(level, house, HouseholdFood.FOOD_ITEM);
                int deficit    = target - breadStock;
                String verdict = deficit > 0
                        ? "§aWILL emit§r MaintainStock(bread, target=" + target
                          + ") (deficit=" + deficit + ")"
                        : "§7bread target met (stock=" + breadStock + " >= target=" + target
                          + ") — will NOT emit§r";
                sb.append("  familySize=").append(familySize)
                  .append(" breadTarget=").append(target)
                  .append(" stock=").append(breadStock)
                  .append("\n  ").append(verdict).append("\n");
            }
        }

        if (!anySource) {
            sb.append("  §7NPC is not a producer/scribe and has no household"
                    + " — no task sources apply.§r\n");
        }

        // ── Force-generate ─────────────────────────────────────────────────────
        sb.append("\n§e--- Force-generating (bypassing flag/schedule/throttle) ---§r\n");

        // Count tasks on each board before generation to compute delta.
        TaskSavedData taskData = TaskSavedData.get(level);
        java.util.Map<IssuerRef, Integer> beforeCounts = new java.util.LinkedHashMap<>();
        for (IssuerRef ref : ctx.memberships()) {
            int count = taskData.boardIfPresent(ref).map(b -> b.all().size()).orElse(0);
            beforeCounts.put(ref, count);
        }

        ProducerSpecs.generateAll(level, npc, ctx);
        ScribeCommissionTaskSource.generateFor(level, npc, ctx);
        HouseholdTaskSource.generateFor(level, npc, ctx);

        // ── Post-gen board dump ───────────────────────────────────────────────
        sb.append("\n§e--- Post-gen board state ---§r\n");
        TaskActor actor  = new NpcActor(npc.getUUID());
        int totalGenerated = 0;
        int boardsWithTasks = 0;

        for (IssuerRef ref : ctx.memberships()) {
            Optional<TaskBoard> boardOpt = taskData.boardIfPresent(ref);
            int before = beforeCounts.getOrDefault(ref, 0);

            if (boardOpt.isEmpty()) {
                sb.append("  ").append(ref.level().name()).append(":")
                  .append(shortId(ref.id())).append(" = §7(no board — nothing generated)§r\n");
                continue;
            }
            TaskBoard board = boardOpt.get();
            List<Task> all  = board.all();
            List<Task> eligible = board.rankedEligibleFor(actor, ctx);
            int added = all.size() - before;
            totalGenerated += added;
            if (!all.isEmpty()) boardsWithTasks++;

            sb.append("  ").append(ref.level().name()).append(":")
              .append(shortId(ref.id()))
              .append(" — ").append(all.size()).append(" task(s) total")
              .append(added > 0 ? " §a(+" + added + " new)§r" : " §7(unchanged)§r")
              .append(", ").append(eligible.size()).append(" eligible:\n");

            for (Task t : all) {
                Assignment a = t.assignment();
                char tier = t.priority().tier().name().charAt(0);
                sb.append("    [").append(tier).append("] ")
                  .append(objectiveSummary(t.objective()))
                  .append(" urgency=")
                  .append(String.format(Locale.ROOT, "%.2f", t.priority().urgency()))
                  .append(" status=").append(a.status().name())
                  .append(" claimants=").append(a.claimants().size())
                  .append("/").append(a.maxClaimants()).append("\n");
            }
            if (all.isEmpty()) {
                sb.append("    §7(empty)§r\n");
            }
        }

        // ── Bottom line ───────────────────────────────────────────────────────
        sb.append("\n§e--- Result ---§r\n");
        if (totalGenerated > 0) {
            sb.append("§aGenerated ").append(totalGenerated).append(" new task(s) across ")
              .append(boardsWithTasks).append(" board(s).§r");
        } else if (boardsWithTasks > 0) {
            sb.append("§7No new tasks generated (").append(boardsWithTasks)
              .append(" board(s) already had tasks — see pre-gen diagnostics above).");
        } else {
            // Attempt to produce a short reason from pre-gen findings.
            String reason = inferReason(level, npc, matchedSpec);
            sb.append("§cNo tasks generated.§r See pre-gen diagnostics above.\n")
              .append("  Likely reason: §f").append(reason).append("§r");
        }
        if (!flagOn) {
            sb.append("\n§7  (flag OFF — dispatcher will not consume these tasks"
                    + " until /litv tasks enable is run)§r");
        }
    }

    /**
     * Best-guess short reason string for "no tasks at all" bottom-line.
     * Never throws — always returns something human-readable.
     */
    private static String inferReason(ServerLevel level, TownspersonMob npc,
                                      ProductionTaskSpec matchedSpec) {
        Profession prof = npc.getProfession();

        // Producer with no building?
        if (matchedSpec != null) {
            if (ProductionTaskSource.forNpc(level, npc, matchedSpec).isEmpty()) {
                return "building " + matchedSpec.buildingType().name() + " not assigned";
            }
            // building present but quota already met
            return "all final-output quotas already met";
        }

        // Scribe with no workshop?
        if (prof == Profession.SCRIBE) {
            boolean hasWorkshop = npc.getAssignedBuildingId()
                    .flatMap(id -> VillageSavedData.get(level).getBuildingById(id))
                    .filter(b -> b.getType() == BuildingType.SCRIBE_WORKSHOP)
                    .isPresent();
            if (!hasWorkshop) return "SCRIBE_WORKSHOP not assigned";
            return "CommissionQueue has no PENDING commissions";
        }

        // Household with missing building?
        if (npc.getHouseId().isPresent()) {
            UUID houseId = npc.getHouseId().get();
            if (VillageSavedData.get(level).getBuildingById(houseId).isEmpty()) {
                return "house building not found in VillageSavedData";
            }
            return "household bread target already met";
        }

        return "profession not migrated and no household (no task sources apply)";
    }

    /** Read-only helper: resolve the NPC's assigned building of the given type. */
    private static Building getAssignedBuilding(ServerLevel level, TownspersonMob npc,
                                                BuildingType type) {
        return npc.getAssignedBuildingId()
                .flatMap(id -> VillageSavedData.get(level).getBuildingById(id))
                .filter(b -> b.getType() == type)
                .orElse(null);
    }

    // ── Shared NPC resolution ─────────────────────────────────────────────────

    /**
     * Finds the nearest living {@link TownspersonMob} within
     * {@link #INSPECT_RADIUS} blocks of {@code src}. Sends a failure message
     * and returns {@code null} if none found or not server-side.
     */
    private static TownspersonMob findNearestNpc(CommandSourceStack src, ServerLevel level) {
        var pos  = src.getPosition();
        AABB area = new AABB(pos, pos).inflate(INSPECT_RADIUS);
        List<TownspersonMob> hits = level.getEntitiesOfClass(
                TownspersonMob.class, area, TownspersonMob::isAlive);
        if (hits.isEmpty()) {
            src.sendFailure(Component.literal(
                    "No TownspersonMob within " + (int) INSPECT_RADIUS
                    + " blocks. Stand next to an NPC."));
            return null;
        }
        TownspersonMob npc = hits.get(0);
        double bestSq = npc.distanceToSqr(pos);
        for (TownspersonMob h : hits) {
            double sq = h.distanceToSqr(pos);
            if (sq < bestSq) { bestSq = sq; npc = h; }
        }
        return npc;
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
                "service=" + ps.kind()
                    + ps.ref().map(r -> "(" + r + ")").orElse("");
        };
    }

    private static String itemName(Item item) {
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

    private static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }
}
