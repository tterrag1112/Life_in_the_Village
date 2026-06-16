package tterrag1112.life_in_the_village.Commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
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
import tterrag1112.life_in_the_village.Npc.Tasks.Household.HouseholdFood;
import tterrag1112.life_in_the_village.Npc.Tasks.Household.HouseholdTaskSource;
import tterrag1112.life_in_the_village.Npc.Tasks.Producer.ProducerSpecs;
import tterrag1112.life_in_the_village.Npc.Tasks.Producer.ProductionTaskSource;
import tterrag1112.life_in_the_village.Npc.Tasks.Producer.ProductionTaskSpec;
import tterrag1112.life_in_the_village.Npc.Tasks.Scribe.ScribeCommissionTaskSource;
import tterrag1112.life_in_the_village.Npc.Tasks.Farm.FarmTaskSource;
import tterrag1112.life_in_the_village.Npc.Tasks.Farm.VillageFarmingDemand;
import tterrag1112.life_in_the_village.Village.Needs.NeedLevel;
import tterrag1112.life_in_the_village.Npc.Tasks.Farm.AnimalTaskSource;
import tterrag1112.life_in_the_village.Npc.Tasks.Priest.PriestTaskSource;
import tterrag1112.life_in_the_village.Npc.Religion.RiteSavedData;
import tterrag1112.life_in_the_village.Npc.Religion.RiteExecutor;
import tterrag1112.life_in_the_village.Npc.Religion.RiteCapability;
import tterrag1112.life_in_the_village.Npc.Religion.RiteTier;
import tterrag1112.life_in_the_village.Npc.Religion.RiteOutcome;
import tterrag1112.life_in_the_village.Village.Event.EventCategory;
import tterrag1112.life_in_the_village.Village.Buildings.FarmPlot;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.Farmer.FarmRole;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.ProfessionRoleManager;
import tterrag1112.life_in_the_village.Village.Economy.Resources.ProductionHelpers;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import net.minecraft.world.entity.schedule.Activity;
import tterrag1112.life_in_the_village.Npc.Brain.NpcActivities;
import tterrag1112.life_in_the_village.Npc.Brain.NpcSchedules;
import tterrag1112.life_in_the_village.Npc.Schedule.DayPhase;
import tterrag1112.life_in_the_village.Npc.Schedule.ScheduleResolver;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskScope;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * {@code /litv tasks ...} — visibility and diagnostics for the Task System.
 *
 * <ul>
 *   <li>{@code /litv tasks status} — prints the migrated profession set and
 *       confirms households are always task-owned.</li>
 *   <li>{@code /litv tasks inspect} — finds the nearest {@link TownspersonMob}
 *       within 16 blocks of the source; prints its profession, migration
 *       status, claimed task (if any), and the ranked eligible tasks from
 *       all its boards. Folds the board dump into the inspect output — a
 *       separate {@code board} subcommand is not needed because the NPC's
 *       membership refs give direct board access.</li>
 *   <li>{@code /litv tasks gen} — force-runs the task sources for the
 *       nearest NPC RIGHT NOW, bypassing the work-schedule activity and the
 *       100-tick refresh throttle. Prints pre-gen diagnostics (why zero tasks
 *       might be produced), executes the generation, then dumps the resulting
 *       board state. Useful for definitively separating "generation is broken"
 *       from "the brain or schedule is never letting the dispatcher run".</li>
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
                        .then(Commands.literal("status")
                                .executes(TaskDebugCommand::handleStatus))
                        .then(Commands.literal("inspect")
                                .executes(TaskDebugCommand::handleInspect))
                        .then(Commands.literal("gen")
                                .executes(TaskDebugCommand::handleGen))
                        .then(Commands.literal("why")
                                .executes(TaskDebugCommand::handleWhy))
                        .then(Commands.literal("storage")
                                .executes(TaskDebugCommand::handleStorage))));
    }

    // ── /litv tasks status ────────────────────────────────────────────────────

    private static int handleStatus(CommandContext<CommandSourceStack> ctx) {
        Set<tterrag1112.life_in_the_village.Profession.Profession> migrated =
                TaskMigration.migrated();
        StringBuilder sb = new StringBuilder();
        sb.append("§e=== Task System Status ===\n");
        sb.append("  migrated   = ").append(migrated).append("\n");
        sb.append("  households = §atask-owned (always)§r");
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
        boolean householdMigrated = npc.getHouseId().isPresent();

        sb.append("§e=== Task inspect: §f").append(npc.getNpcName())
          .append("§e (").append(shortId(npc.getUUID())).append(")§e ===\n");
        sb.append("  profession = §f").append(prof.name()).append("§r\n");
        sb.append("  migrated   = ").append(migrated ? "§ayes§r" : "§cno§r").append("\n");

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
        } else {
            sb.append("§7(none — DoTaskBehavior not running this tick)§r\n");
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
        TaskContext ctx  = new TaskContext(level, npc);

        // ── Header ────────────────────────────────────────────────────────────
        sb.append("§e=== /litv tasks gen: §f").append(npc.getNpcName())
          .append("§e (").append(shortId(npc.getUUID())).append(")§e ===\n");
        sb.append("  profession = §f").append(prof.name()).append("§r\n");
        sb.append("  uuid       = §f").append(npc.getUUID()).append("§r\n");
        sb.append("  migrated?  = ").append(migrated ? "§ayes§r" : "§cno§r").append("\n");

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

        // Farm
        if (prof == Profession.FARMER) {
            anySource = true;
            sb.append("§7[Farm]§r\n");
            // Resolve assigned building to explain any forNpc failure
            Optional<UUID> assignedId = npc.getAssignedBuildingId();
            Building assignedBuilding = assignedId
                    .flatMap(id -> VillageSavedData.get(level).getBuildingById(id))
                    .orElse(null);
            if (assignedId.isEmpty()) {
                sb.append("  §cassigned building id: (none) — FARMHOUSE not assigned§r\n");
            } else if (assignedBuilding == null) {
                sb.append("  §cassigned building id: ").append(shortId(assignedId.get()))
                  .append(" — NOT FOUND in VillageSavedData§r\n");
            } else if (assignedBuilding.getType() != BuildingType.FARMHOUSE) {
                sb.append("  §cassigned building: ").append(shortId(assignedBuilding.getId()))
                  .append(" type=").append(assignedBuilding.getType().name())
                  .append(" — expected FARMHOUSE§r\n");
            }
            Optional<FarmTaskSource> farmSrc = FarmTaskSource.forNpc(level, npc);
            if (farmSrc.isEmpty()) {
                sb.append("  §cFarmTaskSource.forNpc → empty§r "
                        + "(not a FARMER or no assigned FARMHOUSE)\n");
            } else {
                sb.append("  farmhouse §afound§r (")
                  .append(assignedBuilding != null ? shortId(assignedBuilding.getId()) : "?")
                  .append(")\n");

                // Plot breakdown
                VillageSavedData vsd = VillageSavedData.get(level);
                java.util.UUID fhId = assignedBuilding.getId();
                java.util.List<FarmPlot> allFarmPlots = vsd.getFarmPlotsForFarmhouse(fhId);
                java.util.List<FarmPlot> cropPlots    = allFarmPlots.stream()
                        .filter(p -> p.getSubtype() == FarmPlot.PlotSubtype.CROP_FIELD)
                        .collect(java.util.stream.Collectors.toList());
                long now2 = level.getGameTime();
                java.util.List<FarmPlot> activePlots = cropPlots.stream()
                        .filter(p -> !p.isFallow())
                        .collect(java.util.stream.Collectors.toList());
                sb.append("  plots: total=").append(allFarmPlots.size())
                  .append(" CROP_FIELD=").append(cropPlots.size())
                  .append(" non-fallow=").append(activePlots.size()).append("\n");

                // Role
                FarmRole role = ProfessionRoleManager.getRole(npc, FarmRole.class);
                boolean specBiasAnimal = (role == FarmRole.GENERALIST)
                        && npc.getSpecializationComponent().currentId()
                                .map(id -> id.equals(
                                        tterrag1112.life_in_the_village.Npc.Specialization
                                                .NpcSpecializationTypes.FARMER_ANIMAL_FOCUS.name()))
                                .orElse(false);
                // Matches AnimalTaskSource.generate() exactly:
                // ANIMAL_SPECIALIST | ANIMAL_TENDER | FERTILIZER | specBiasAnimal → animal_tend
                // SHEPHERD → shear task; BEEKEEPER → collect_honey task (G2b)
                boolean animalRole = role == FarmRole.ANIMAL_SPECIALIST
                        || role == FarmRole.ANIMAL_TENDER
                        || role == FarmRole.FERTILIZER
                        || specBiasAnimal;
                boolean isShepherd  = role == FarmRole.SHEPHERD;
                boolean isBeekeeper = role == FarmRole.BEEKEEPER;
                boolean shepherdOrBeekeeper = isShepherd || isBeekeeper;
                boolean isFertilizer = role == FarmRole.FERTILIZER;
                // Mirrors canHarvest(role) / canPlant(role) in generate() exactly.
                // APPRENTICE is NOT in either set — generate() never included it.
                boolean canHarvest2 = !animalRole && !shepherdOrBeekeeper
                        && (role == null || role == FarmRole.GENERALIST
                        || role == FarmRole.CROP_SPECIALIST || role == FarmRole.HARVESTER);
                // FERTILIZER override: generate() forces canHarvest=false, canPlant=false.
                if (isFertilizer) canHarvest2 = false;
                boolean canPlant2 = !animalRole && !shepherdOrBeekeeper && !isFertilizer
                        && (role == null || role == FarmRole.GENERALIST
                        || role == FarmRole.CROP_SPECIALIST || role == FarmRole.PLANTER);
                String roleStr = role != null ? role.name() : "(null=GENERALIST-equivalent)";
                String roleVerdict;
                if (animalRole) {
                    roleVerdict = "§c→ ANIMAL role — zero crop tasks§r";
                } else if (isShepherd) {
                    roleVerdict = "§b→ SHEPHERD — shear task (G2b)§r";
                } else if (isBeekeeper) {
                    roleVerdict = "§b→ BEEKEEPER — collect_honey task (G2b)§r";
                } else if (isFertilizer) {
                    roleVerdict = "§7→ FERTILIZER — compost only (no harvest/plant/till)§r";
                } else {
                    roleVerdict = "§a→ harvest=" + canHarvest2 + " plant/till=" + canPlant2 + "§r";
                }
                sb.append("  role=").append(roleStr).append(" ").append(roleVerdict).append("\n");

                // Apprentice plot restriction — detected via EmploymentTier, NOT FarmRole.APPRENTICE.
                boolean isApprentice2 = FarmTaskSource.isApprenticeTierFor(
                        level, assignedBuilding, npc);
                if (isApprentice2) {
                    Optional<java.util.UUID> assignedPlot = npc.getAssignedPlotId();
                    if (assignedPlot.isEmpty()) {
                        sb.append("  §cAPPRENTICE with no assignedPlotId — zero tasks§r\n");
                    } else {
                        final java.util.UUID pid2 = assignedPlot.get();
                        activePlots = activePlots.stream()
                                .filter(p -> p.getId().equals(pid2))
                                .collect(java.util.stream.Collectors.toList());
                        sb.append("  apprentice assignedPlot=").append(shortId(pid2))
                          .append(" → ").append(activePlots.size())
                          .append(" matching active plot(s)\n");
                    }
                }

                // Per-plot eligibility (capped at 8)
                int plotCap = 8;
                if (!activePlots.isEmpty() && !animalRole && !shepherdOrBeekeeper) {
                    sb.append("  per-plot eligibility (mature/emptyFarmland/seeds/tillable/compost):\n");
                    int harvest2 = 0, replant2 = 0, till2 = 0, compost2 = 0;
                    int shown2 = 0;
                    for (FarmPlot fp : activePlots) {
                        FarmTaskSource.PlotEligibility elig =
                                FarmTaskSource.plotEligibilityFor(
                                        level, assignedBuilding, npc, fp, now2);
                        if (shown2 < plotCap) {
                            sb.append("    plot ").append(shortId(fp.getId()))
                              .append(" mature=").append(elig.mature() ? "§ay§r" : "§7n§r")
                              .append(" empty=").append(elig.emptyFarmland() ? "§ay§r" : "§7n§r")
                              .append(" seeds=").append(elig.seedsAvailable() ? "§ay§r" : "§7n§r")
                              .append(" tillable=").append(elig.tillable() ? "§ay§r" : "§7n§r")
                              .append(" compost=").append(elig.compostEligible() ? "§ay§r" : "§7n§r")
                              .append("\n");
                        }
                        shown2++;
                        if (canHarvest2 && elig.mature())                          harvest2++;
                        if (canPlant2   && elig.emptyFarmland() && elig.seedsAvailable()) replant2++;
                        if (canPlant2   && elig.tillable())                        till2++;
                        if (isFertilizer && !isApprentice2 && elig.compostEligible()) compost2++;
                    }
                    if (shown2 > plotCap) {
                        sb.append("    ... (+").append(shown2 - plotCap).append(" more)\n");
                    }
                    // G1b: compute would-emit counts for sell + seed tasks
                    int sellSurplus2 = 0;
                    java.util.Map<net.minecraft.world.item.Item, Integer> sellFloors2 =
                            java.util.Map.of(
                                Items.WHEAT, 32, Items.CARROT, 16,
                                Items.POTATO, 16, Items.BEETROOT, 16,
                                Items.WHEAT_SEEDS, 8, Items.BEETROOT_SEEDS, 8);
                    tterrag1112.life_in_the_village.Village.Building fh2 =
                            vsd.getBuildingById(fhId).orElse(null);
                    if (fh2 != null) {
                        for (var e2 : sellFloors2.entrySet()) {
                            int stock2 = BuildingStorageAccess.countItem(level, fh2, e2.getKey());
                            if (stock2 > e2.getValue()) sellSurplus2++;
                        }
                    }
                    int seedAcquire2 = 0;
                    for (tterrag1112.life_in_the_village.Village.Buildings.FarmPlot fp3 : activePlots) {
                        boolean ef = false;
                        for (net.minecraft.core.BlockPos fl : fp3.getFarmlandBlocks(level)) {
                            if (level.getBlockState(fl.above()).isAir()) { ef = true; break; }
                        }
                        if (!ef) continue;
                        net.minecraft.world.item.Item si2 = fp3.getCropType().resolveSeedItem();
                        int storedSi = fh2 != null
                                ? BuildingStorageAccess.countItem(level, fh2, si2) : 0;
                        int personalSi = 0;
                        net.minecraft.world.SimpleContainer pi2 = npc.getPersonalInventory();
                        for (int pii = 0; pii < pi2.getContainerSize(); pii++) {
                            net.minecraft.world.item.ItemStack pis = pi2.getItem(pii);
                            if (!pis.isEmpty() && pis.getItem() == si2) personalSi += pis.getCount();
                        }
                        if ((storedSi + personalSi) == 0) seedAcquire2++;
                    }
                    // G3a: show resolved food need + derived priority tiers
                    NeedLevel foodNeedDbg = VillageFarmingDemand.foodLevel(level, npc);
                    tterrag1112.life_in_the_village.Profession.Tasks.TaskPriority hTier =
                            VillageFarmingDemand.harvestTier(foodNeedDbg);
                    tterrag1112.life_in_the_village.Profession.Tasks.TaskPriority rtTier =
                            VillageFarmingDemand.replantTillTier(foodNeedDbg);
                    sb.append("  food need=§e").append(foodNeedDbg.name())
                      .append("§r → harvest tier=§e").append(hTier.name())
                      .append("§r replant/till tier=§e").append(rtTier.name()).append("§r\n");
                    sb.append("  FarmTaskSource WOULD emit: harvest=").append(harvest2)
                      .append(" replant=").append(replant2)
                      .append(" till=").append(till2)
                      .append(" compost=").append(compost2)
                      .append(" sell=").append(sellSurplus2)
                      .append(" seedAcquire=").append(seedAcquire2)
                      .append(" tasks\n");
                } else if (activePlots.isEmpty() && !animalRole && !shepherdOrBeekeeper) {
                    sb.append("  §7(no non-fallow CROP_FIELD plots — zero farm tasks)§r\n");
                }
                // Animal/species role: show WOULD-emit for the applicable task
                if (fh2 != null) {
                    tterrag1112.life_in_the_village.Village.Roster.RosterSavedData rsd2 =
                            tterrag1112.life_in_the_village.Village.Roster.RosterSavedData.get(level);
                    if (animalRole) {
                        boolean hasRoster = !rsd2.getRostersForBuilding(fhId).isEmpty();
                        sb.append("  AnimalTaskSource WOULD emit: animal_tend=")
                          .append(hasRoster ? "§a1§r" : "§c0 (no roster)§r")
                          .append(" task\n");
                    } else if (isShepherd) {
                        // G2b: SHEPHERD — shear task
                        boolean hasSheepRoster = rsd2
                                .getRoster(fhId, tterrag1112.life_in_the_village.Village.Roster.AnimalRosterDefinitions.SHEEP)
                                .map(r -> r.countAdults() > 0).orElse(false);
                        boolean hasShears2 = false;
                        net.minecraft.world.SimpleContainer pi3 = npc.getPersonalInventory();
                        for (int pi = 0; pi < pi3.getContainerSize(); pi++) {
                            if (pi3.getItem(pi).getItem() == Items.SHEARS) { hasShears2 = true; break; }
                        }
                        boolean wouldShear = hasSheepRoster && hasShears2;
                        sb.append("  AnimalTaskSource WOULD emit: shear=")
                          .append(wouldShear ? "§a1§r" : "§c0§r")
                          .append(hasSheepRoster ? "" : " §7(no SHEEP roster with adults)§r")
                          .append(hasSheepRoster && !hasShears2 ? " §7(no shears)§r" : "")
                          .append(" task\n");
                    } else if (isBeekeeper) {
                        // G2b: BEEKEEPER — collect_honey task
                        boolean hasBeeRoster = rsd2
                                .getRoster(fhId, tterrag1112.life_in_the_village.Village.Roster.AnimalRosterDefinitions.BEE)
                                .map(r -> r.countAdults() > 0).orElse(false);
                        int honeycombStock = BuildingStorageAccess.countItem(level, fh2, Items.HONEYCOMB);
                        int bottleStock    = BuildingStorageAccess.countItem(level, fh2, Items.HONEY_BOTTLE);
                        boolean hasShears3 = false, hasBottle3 = false;
                        net.minecraft.world.SimpleContainer pi4 = npc.getPersonalInventory();
                        for (int pi = 0; pi < pi4.getContainerSize(); pi++) {
                            net.minecraft.world.item.Item pii = pi4.getItem(pi).getItem();
                            if (pii == Items.SHEARS)       hasShears3 = true;
                            if (pii == Items.GLASS_BOTTLE) hasBottle3 = true;
                        }
                        boolean canComb3   = hasBeeRoster && hasShears3 && honeycombStock < 16;
                        boolean canBottle3 = hasBeeRoster && hasBottle3 && bottleStock    < 8;
                        sb.append("  AnimalTaskSource WOULD emit: collect_honey=")
                          .append((canComb3 || canBottle3) ? "§a1§r" : "§c0§r")
                          .append(" (comb=").append(canComb3 ? "§ay§r" : "§cn§r")
                          .append(" bottle=").append(canBottle3 ? "§ay§r" : "§cn§r").append(")\n");
                    }
                }
            }
        }

        // Priest
        if (prof == Profession.PRIEST) {
            anySource = true;
            sb.append("§7[Priest]§r\n");
            Optional<UUID> assignedId = npc.getAssignedBuildingId();
            Building assignedBuilding = assignedId
                    .flatMap(id -> VillageSavedData.get(level).getBuildingById(id))
                    .orElse(null);
            boolean hasSacredBuilding = assignedBuilding != null
                    && PriestTaskSource.isReligiousBuilding(assignedBuilding.getType());
            if (assignedId.isEmpty()) {
                sb.append("  §cassigned building id: (none) — sacred building not assigned§r\n");
            } else if (assignedBuilding == null) {
                sb.append("  §cassigned building id: ").append(shortId(assignedId.get()))
                  .append(" — NOT FOUND in VillageSavedData§r\n");
            } else if (!hasSacredBuilding) {
                sb.append("  §cassigned building: ").append(shortId(assignedBuilding.getId()))
                  .append(" type=").append(assignedBuilding.getType().name())
                  .append(" — expected TEMPLE/CHAPEL/SHRINE§r\n");
            } else {
                sb.append("  sacred building §afound§r (type=")
                  .append(assignedBuilding.getType().name())
                  .append(", id=").append(shortId(assignedBuilding.getId())).append(")\n");
            }
            if (hasSacredBuilding) {
                java.util.Optional<tterrag1112.life_in_the_village.Village.Village> villageOpt =
                        npc.getAssignedVillageName()
                                .flatMap(n -> VillageSavedData.get(level).getVillageByName(n));
                if (villageOpt.isEmpty()) {
                    sb.append("  §c(no assigned village — cannot scan rites)§r\n");
                } else {
                    java.util.UUID villageId3 = villageOpt.get().getId();
                    long now3 = level.getGameTime();
                    java.util.UUID me3 = npc.getUUID();

                    // Collect fronted rite ids (disjoint from task-source rites)
                    java.util.Set<java.util.UUID> frontedRiteIds3 = new java.util.HashSet<>();
                    for (tterrag1112.life_in_the_village.Village.Event.VillageEvent ve3
                            : VillageSavedData.get(level).getAllEvents()) {
                        if (!villageId3.equals(ve3.getVillageId())) continue;
                        if (ve3.getType().category() != EventCategory.RELIGIOUS_RITE) continue;
                        if (!ve3.isActiveAt(now3)) continue;
                        String riteIdStr3 = ve3.getEventData().get(
                                tterrag1112.life_in_the_village.Village.Event.CeremonyBlessings.RITE_ID_KEY);
                        if (riteIdStr3 == null) continue;
                        try { frontedRiteIds3.add(java.util.UUID.fromString(riteIdStr3)); }
                        catch (IllegalArgumentException ignored3) {}
                    }

                    int dueTotal3 = 0, claimable3 = 0, fronted3 = 0;
                    for (tterrag1112.life_in_the_village.Npc.Religion.RiteExecution r3
                            : RiteSavedData.get(level).dueRites(now3)) {
                        if (!villageId3.equals(r3.villageId())) continue;
                        dueTotal3++;
                        java.util.UUID presider3 = r3.presidingPriestId().orElse(null);
                        boolean vacantOrMe = (presider3 == null || presider3.equals(me3));
                        boolean capable3 = RiteCapability.canOfficiate(npc, r3.type());
                        if (!vacantOrMe || !capable3) continue;
                        if (frontedRiteIds3.contains(r3.riteId())) { fronted3++; continue; }
                        claimable3++;
                    }
                    sb.append("  village rites: due=").append(dueTotal3)
                      .append(" claimable(this priest)=§a").append(claimable3).append("§r")
                      .append(" fronted(festival-excluded)=").append(fronted3).append("\n");
                    sb.append("  PriestTaskSource WOULD emit: officiate_rite=")
                      .append(claimable3 > 0 ? "§a" : "§c").append(claimable3).append("§r tasks\n");
                }
            }
        }

        if (!anySource) {
            sb.append("  §7NPC is not a producer/scribe/farm and has no household"
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
        FarmTaskSource.generateFor(level, npc, ctx);
        AnimalTaskSource.generateFor(level, npc, ctx);
        PriestTaskSource.generateFor(level, npc, ctx);

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

        // Farmer with no farmhouse or no eligible plots?
        if (prof == Profession.FARMER) {
            if (FarmTaskSource.forNpc(level, npc).isEmpty()) {
                return "FARMHOUSE not assigned (or assigned building is wrong type)";
            }
            FarmRole role = ProfessionRoleManager.getRole(npc, FarmRole.class);
            if (role == FarmRole.ANIMAL_SPECIALIST || role == FarmRole.ANIMAL_TENDER
                    || role == FarmRole.SHEPHERD || role == FarmRole.BEEKEEPER) {
                return "role=" + role.name() + " → animal worker, no crop tasks";
            }
            return "no eligible crop plots (all fallow, or wrong role, or no mature/empty/tillable blocks)";
        }

        // Household with missing building?
        if (npc.getHouseId().isPresent()) {
            UUID houseId = npc.getHouseId().get();
            if (VillageSavedData.get(level).getBuildingById(houseId).isEmpty()) {
                return "house building not found in VillageSavedData";
            }
            return "household bread target already met";
        }

        // Priest with no sacred building?
        if (prof == Profession.PRIEST) {
            if (PriestTaskSource.forNpc(level, npc).isEmpty()) {
                return "TEMPLE/CHAPEL/SHRINE not assigned (or assigned building is wrong type)";
            }
            return "no claimable due rites in this village (all claimed by others, incapable, or fronted by festival)";
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



    // ── /litv tasks why ────────────────────────────────────────────

    private static int handleWhy(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        if (!(src.getLevel() instanceof ServerLevel level)) {
            src.sendFailure(Component.literal("/litv tasks why must be run server-side."));
            return 0;
        }
        TownspersonMob npc = findNearestNpc(src, level);
        if (npc == null) return 0;

        StringBuilder sb = new StringBuilder();
        appendWhy(sb, level, npc);
        src.sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
    }

    /**
     * Diagnostic that answers: (a) is the DoTaskBehavior dispatcher even being
     * polled right now? and (b) if polled, why is it declining every task?
     *
     * <p><b>Side-effect caveat (also printed in output):</b>
     * {@link tterrag1112.life_in_the_village.Npc.Tasks.Producer.CraftOutputFulfillment#canFulfill}
     * lazily spawns an {@code Acquire(intermediate)} task when a final output is
     * blocked on a missing intermediate (idempotent — skips if one already exists).
     * This mirrors what the live dispatcher does; the command does not amplify it.</p>
     */
    private static void appendWhy(StringBuilder sb, ServerLevel level, TownspersonMob npc) {
        Profession prof       = npc.getProfession();
        boolean migrated      = TaskMigration.isMigrated(prof);
        boolean hasHousehold  = npc.getHouseId().isPresent();

        // ── 1. Header ──────────────────────────────────────────────────────────
        sb.append("§e=== /litv tasks why: §f").append(npc.getNpcName())
          .append("§e (§f").append(npc.getUUID()).append("§e) ===\n");
        sb.append("  profession = §f").append(prof.name()).append("§r\n");
        sb.append("  migrated?  = ").append(migrated ? "§ayes§r" : "§cno§r").append("\n");
        sb.append("  household? = ");
        if (hasHousehold) {
            sb.append("§ayes§r (houseId=").append(shortId(npc.getHouseId().get())).append(")");
        } else {
            sb.append("§7no§r");
        }
        sb.append("\n");

        // ── 2. Activity state ───────────────────────────────────────────────
        sb.append("\n§e--- Activity state ---§r\n");
        var brain = npc.getBrain();
        long gameTick = level.getGameTime();

        Activity[] probeActivities = {
            Activity.CORE,
            Activity.IDLE,
            NpcActivities.WORK.get(),
            NpcActivities.SOCIAL.get(),
            NpcActivities.REST.get()
        };
        for (Activity act : probeActivities) {
            boolean active = brain.isActive(act);
            sb.append("  ").append(activityLabel(act))
              .append(" = ").append(active ? "§aACTIVE§r" : "§7inactive§r").append("\n");
        }

        boolean workActive = brain.isActive(NpcActivities.WORK.get());
        sb.append("  isWorkTime() = ")
          .append(npc.isWorkTime() ? "§atrue§r" : "§cfalse§r").append("\n");

        DayPhase currentPhase = ScheduleResolver.phaseAt(npc, gameTick);
        Activity expectedAct  = NpcSchedules.activityFor(currentPhase);
        sb.append("  schedule phase = §f").append(currentPhase.name())
          .append("§r → expected activity: §f").append(activityLabel(expectedAct)).append("§r\n");

        sb.append("  §7[interpretation]§r ");
        if (workActive) {
            sb.append("§aWORK active → WORK_PROFESSION dispatcher SHOULD be polled§r\n");
        } else {
            sb.append("§cWORK not active → WORK_PROFESSION dispatcher will NOT run now\n");
            sb.append("    (phase=").append(currentPhase.name())
              .append(", active=").append(currentActivityName(brain, probeActivities))
              .append(")§r\n");
        }

        // ── 3. Scope gates ────────────────────────────────────────────────────────────
        sb.append("\n§e--- Scope gates ---§r\n");
        sb.append("  WORK_PROFESSION (isMigrated):   ")
          .append(migrated ? "§aOPEN§r" : "§cCLOSED§r").append("\n");
        sb.append("  HOUSEHOLD       (ownsHousehold): ")
          .append(TaskMigration.ownsHousehold() ? "§aOPEN§r" : "§cCLOSED§r").append("\n");

        // ── 4. Selection dry-run ──────────────────────────────────────────────────────
        sb.append("\n§e--- Selection dry-run ---§r\n");
        sb.append("§7NOTE: CraftOutputFulfillment.canFulfill may lazily spawn an Acquire task")
          .append(" (idempotent — mirrors live dispatcher).§r\n");

        TaskContext ctx2   = new TaskContext(level, npc);
        TaskActor actor    = new NpcActor(npc.getUUID());
        TaskSavedData data = TaskSavedData.get(level);
        FulfillmentRegistry reg = Fulfillments.shared();

        appendScopeWhy(sb, level, actor, ctx2, data, reg,
                TaskScope.WORK_PROFESSION, "WORK scope (producer/scribe/farm)", migrated);
        if (hasHousehold) {
            appendScopeWhy(sb, level, actor, ctx2, data, reg,
                    TaskScope.HOUSEHOLD, "HOUSEHOLD scope", true);
        }

        // ── 5. Bottom line ───────────────────────────────────────────────────────────────
        sb.append("\n§e--- Bottom line ---§r\n");
        appendBottomLine(sb, level, actor, ctx2, data, reg, workActive, migrated, hasHousehold);
    }

    private static void appendScopeWhy(StringBuilder sb, ServerLevel level,
                                        TaskActor actor, TaskContext ctx,
                                        TaskSavedData data, FulfillmentRegistry reg,
                                        TaskScope scope, String scopeLabel, boolean gateOpen) {
        sb.append("\n  §e[").append(scopeLabel).append("]§r\n");
        if (!gateOpen) {
            sb.append("    §cScope gate CLOSED — skipping dry-run§r\n");
            return;
        }

        java.util.List<Task> candidates = new java.util.ArrayList<>();
        for (IssuerRef ref : ctx.memberships()) {
            if (!scope.includes(ref)) continue;
            data.boardIfPresent(ref).ifPresent(board ->
                    candidates.addAll(board.rankedEligibleFor(actor, ctx)));
        }
        if (candidates.isEmpty()) {
            sb.append("    §7(no eligible tasks on any board in this scope)§r\n");
            return;
        }

        candidates.sort(TaskBoard.RANKING);

        Task wouldClaim = null;
        for (Task task : candidates) {
            ServerLevel lvl = ctx.level();
            boolean depsSatisfied = DoTaskBehavior.checkDependenciesSatisfied(lvl, ctx, scope, task);
            sb.append("    [").append(task.priority().tier().name().charAt(0)).append("] ")
              .append(objectiveSummary(task.objective()))
              .append(" urgency=").append(String.format(Locale.ROOT, "%.2f", task.priority().urgency()))
              .append(" status=").append(task.assignment().status().name())
              .append(" claimants=").append(task.assignment().claimants().size())
              .append("/").append(task.assignment().maxClaimants())
              .append(" deps=").append(depsSatisfied ? "§aOK§r" : "§cunsatisfied§r")
              .append("\n");

            List<Fulfillment> strategies = reg.strategiesFor(task.objective());
            if (strategies.isEmpty()) {
                sb.append("      §7(no fulfillments registered for ")
                  .append(task.objective().type().name()).append(")§r\n");
            } else {
                for (Fulfillment f : strategies) {
                    boolean can = f.canFulfill(task, actor, ctx);
                    sb.append("      ").append(f.getClass().getSimpleName())
                      .append(": canFulfill=").append(can ? "§atrue§r" : "§cfalse§r");
                    if (can) {
                        double sc = f.score(task, actor, ctx);
                        sb.append(" score=").append(String.format(Locale.ROOT, "%.2f", sc));
                    }
                    sb.append("\n");
                }
            }

            if (wouldClaim == null && depsSatisfied) {
                boolean anyCanFulfill = strategies.stream()
                        .anyMatch(f -> f.canFulfill(task, actor, ctx));
                if (anyCanFulfill) {
                    wouldClaim = task;
                }
            }
        }

        if (wouldClaim != null) {
            sb.append("    §apickTop → WOULD claim: ")
              .append(objectiveSummary(wouldClaim.objective())).append("§r\n");
        } else {
            String reason = inferPickTopFailure(ctx, reg, actor, scope, candidates);
            sb.append("    §cpickTop → none§r (").append(reason).append(")\n");
        }
    }

    private static String inferPickTopFailure(TaskContext ctx,
                                               FulfillmentRegistry reg,
                                               TaskActor actor, TaskScope scope,
                                               java.util.List<Task> candidates) {
        if (candidates.isEmpty()) return "no tasks on boards";
        ServerLevel level = ctx.level();
        int depsUnsatisfied = 0;
        int noFulfillment   = 0;
        for (Task task : candidates) {
            if (!DoTaskBehavior.checkDependenciesSatisfied(level, ctx, scope, task)) {
                depsUnsatisfied++;
                continue;
            }
            boolean anyCanFulfill = reg.strategiesFor(task.objective()).stream()
                    .anyMatch(f -> f.canFulfill(task, actor, ctx));
            if (!anyCanFulfill) noFulfillment++;
        }
        int total = candidates.size();
        if (depsUnsatisfied == total) {
            return "all " + total + " task(s) have unsatisfied deps";
        }
        if (noFulfillment > 0 && depsUnsatisfied + noFulfillment == total) {
            return "all dep-satisfied task(s) declined by all fulfillments "
                    + "(check building/amenity/inputs)";
        }
        return depsUnsatisfied + " unsatisfied deps, " + noFulfillment + " fulfillment(s) decline";
    }

    private static void appendBottomLine(StringBuilder sb, ServerLevel level,
                                          TaskActor actor, TaskContext ctx, TaskSavedData data,
                                          FulfillmentRegistry reg, boolean workActive,
                                          boolean migrated, boolean hasHousehold) {
        if (!workActive && !migrated && !hasHousehold) {
            sb.append("§cWORK not active + not migrated + no household "
                    + "→ task dispatcher never runs§r\n");
            return;
        }
        if (!workActive) {
            sb.append("§cWORK not active → it is simply not work time for this NPC.\n");
            sb.append("  Wait for the work phase, or re-run /litv tasks why then.§r\n");
            return;
        }
        if (!migrated) {
            sb.append("§cWORK_PROFESSION gate CLOSED (profession not migrated)\n");
            sb.append("  → legacy WORK behavior runs instead; task dispatcher is skipped.§r\n");
            return;
        }
        // WORK active + migrated: replicate pickTop to give a single verdict
        java.util.List<Task> workCandidates = new java.util.ArrayList<>();
        for (IssuerRef ref : ctx.memberships()) {
            if (!TaskScope.WORK_PROFESSION.includes(ref)) continue;
            data.boardIfPresent(ref).ifPresent(board ->
                    workCandidates.addAll(board.rankedEligibleFor(actor, ctx)));
        }
        workCandidates.sort(TaskBoard.RANKING);
        Task pick = null;
        for (Task task : workCandidates) {
            if (!DoTaskBehavior.checkDependenciesSatisfied(level, ctx, TaskScope.WORK_PROFESSION, task)) continue;
            boolean any = reg.strategiesFor(task.objective()).stream()
                    .anyMatch(f -> f.canFulfill(task, actor, ctx));
            if (any) { pick = task; break; }
        }
        if (pick != null) {
            sb.append("§aWORK active + pickTop WOULD claim '§f")
              .append(objectiveSummary(pick.objective())).append("§a'§r\n");
            sb.append("  → If still not running, investigate makeBrain:\n");
            sb.append("    confirm DoTaskBehavior() is registered in WORK at priority 1\n");
            sb.append("    and the legacy WORK@0 behavior is yielding for migrated professions.\n");
        } else if (workCandidates.isEmpty()) {
            sb.append("§cpickTop → none: no eligible tasks on WORK-scope boards§r\n");
            sb.append("  → Run /litv tasks gen to force-generate, then re-run /litv tasks why.\n");
        } else {
            sb.append("§cpickTop → none: tasks exist but all fulfillments decline§r\n");
            sb.append("  Common causes: FURNACE/ANVIL amenity missing, inputs absent from\n");
            sb.append("  building storage, wrong building type assigned, or an intermediate\n");
            sb.append("  Acquire dep unsatisfied. See fulfillment detail in dry-run above.\n");
        }
    }


    // ── /litv tasks storage ───────────────────────────────────────────────────

    private static final int STORAGE_SCAN_RADIUS = 12;
    /** Interesting items to summarise explicitly. */
    private static final List<Item> KEY_ITEMS = List.of(
            Items.RAW_IRON, Items.RAW_COPPER, Items.COAL, Items.CHARCOAL, Items.IRON_INGOT);
    private static final int MAX_DISTINCT_ITEMS = 30;

    private static int handleStorage(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        if (!(src.getLevel() instanceof ServerLevel level)) {
            src.sendFailure(Component.literal("/litv tasks storage must be run server-side."));
            return 0;
        }
        TownspersonMob npc = findNearestNpc(src, level);
        if (npc == null) return 0;

        StringBuilder sb = new StringBuilder();
        appendStorage(sb, level, npc);
        src.sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
    }

    private static void appendStorage(StringBuilder sb, ServerLevel level, TownspersonMob npc) {
        // ── 1. Header ──────────────────────────────────────────────────────────
        sb.append("§e=== /litv tasks storage: §f").append(npc.getNpcName())
          .append("§e (§f").append(npc.getUUID()).append("§e)\n");
        sb.append("  profession = §f").append(npc.getProfession().name()).append("§r\n");

        // ── 2. Resolve building (exactly as fulfillments do) ──────────────────
        Building building = null;
        BuildingType resolvedType = null;

        // Producer path: find matching spec → use spec.buildingType()
        for (ProductionTaskSpec spec : ProducerSpecs.ALL) {
            if (npc.getProfession() != spec.profession()) continue;
            resolvedType = spec.buildingType();
            building = ProductionHelpers.findAssignedBuilding(npc, level, resolvedType)
                    .orElse(null);
            break;
        }

        // Non-producer fallback: raw assigned building (no type filter)
        if (resolvedType == null) {
            building = npc.getAssignedBuildingId()
                    .flatMap(id -> VillageSavedData.get(level).getBuildingById(id))
                    .orElse(null);
            if (building != null) resolvedType = building.getType();
        }

        if (building == null) {
            String typeLabel = resolvedType != null ? resolvedType.name() : "(none)";
            sb.append("§cno production building resolved");
            if (resolvedType != null) sb.append(" (findAssignedBuilding(").append(typeLabel).append(") empty)");
            sb.append("§r\n");
            sb.append("  → This itself is the finding: the NPC has no assigned building of the expected type.\n");
            return;
        }

        Building.BuildingShape shape = building.getShape();
        BlockPos origin = shape.getOrigin();
        BlockPos min    = shape.getMin();
        BlockPos max    = shape.getMax();
        int dx = max.getX() - min.getX() + 1;
        int dy = max.getY() - min.getY() + 1;
        int dz = max.getZ() - min.getZ() + 1;

        sb.append("§e--- Resolved building ---§r\n");
        sb.append("  type   = §f").append(building.getType().name()).append("§r\n");
        sb.append("  id     = §f").append(shortId(building.getId())).append("§r\n");
        sb.append("  origin = §f").append(fmtPos(origin)).append("§r\n");
        sb.append("  min    = §f").append(fmtPos(min)).append("§r\n");
        sb.append("  max    = §f").append(fmtPos(max)).append("§r\n");
        sb.append("  size   = §f").append(dx).append("×").append(dy).append("×").append(dz).append("§r\n");

        // ── 3. In-bounds containers (exactly as findInventories does) ─────────
        List<Container> inBound = BuildingStorageAccess.findInventories(level, building);

        sb.append("\n§e--- In-bounds containers (building box) ---§r\n");
        sb.append("  count = §f").append(inBound.size()).append("§r\n");

        // Key-item totals across all in-bound containers
        java.util.Map<Item, Integer> inKeyTotals = new java.util.LinkedHashMap<>();
        for (Item key : KEY_ITEMS) inKeyTotals.put(key, 0);
        java.util.Map<Item, Integer> inAllTotals = new java.util.LinkedHashMap<>();

        for (Container inv : inBound) {
            // Position: Container block entities implement BlockEntity; cast when possible
            String posLabel = (inv instanceof BlockEntity be)
                    ? fmtPos(be.getBlockPos()) : "(non-BE container)";
            sb.append("  ").append(posLabel)
              .append(" slots=").append(inv.getContainerSize()).append("\n");

            for (int s = 0; s < inv.getContainerSize(); s++) {
                ItemStack stack = inv.getItem(s);
                if (stack.isEmpty()) continue;
                Item item = stack.getItem();
                int cnt  = stack.getCount();
                inAllTotals.merge(item, cnt, Integer::sum);
                if (inKeyTotals.containsKey(item)) {
                    inKeyTotals.merge(item, cnt, Integer::sum);
                }
            }
        }

        sb.append("  key-item totals (in-bounds):\n");
        for (Item key : KEY_ITEMS) {
            int cnt = inKeyTotals.getOrDefault(key, 0);
            sb.append("    ").append(itemName(key)).append(" = ")
              .append(cnt > 0 ? "§a" : "§7").append(cnt).append("§r\n");
        }

        if (!inAllTotals.isEmpty()) {
            sb.append("  all distinct items (in-bounds, capped at ").append(MAX_DISTINCT_ITEMS).append("):\n");
            int shown = 0;
            for (var entry : inAllTotals.entrySet()) {
                if (shown >= MAX_DISTINCT_ITEMS) {
                    sb.append("    ... (").append(inAllTotals.size() - shown).append(" more)\n");
                    break;
                }
                sb.append("    ").append(itemName(entry.getKey()))
                  .append(" ×").append(entry.getValue()).append("\n");
                shown++;
            }
        } else {
            sb.append("  §7(no items found in-bounds)§r\n");
        }

        // ── 4. Out-of-bounds nearby containers ───────────────────────────────
        sb.append("\n§e--- Out-of-bounds nearby containers (radius ").append(STORAGE_SCAN_RADIUS).append(") ---§r\n");
        BlockPos npcPos = npc.blockPosition();
        BlockPos scanMin = npcPos.offset(-STORAGE_SCAN_RADIUS, -STORAGE_SCAN_RADIUS, -STORAGE_SCAN_RADIUS);
        BlockPos scanMax = npcPos.offset( STORAGE_SCAN_RADIUS,  STORAGE_SCAN_RADIUS,  STORAGE_SCAN_RADIUS);

        int outCount = 0;
        int outWithMaterials = 0;
        StringBuilder outSb = new StringBuilder();

        for (BlockPos pos : BlockPos.betweenClosed(scanMin, scanMax)) {
            if (shape.contains(pos)) continue;          // in-bounds → skip
            BlockEntity be = level.getBlockEntity(pos);
            if (!(be instanceof Container inv)) continue;

            BlockPos immPos = pos.immutable();
            int rawIron  = countInContainer(inv, Items.RAW_IRON);
            int rawCopper= countInContainer(inv, Items.RAW_COPPER);
            int coal     = countInContainer(inv, Items.COAL);
            int charcoal = countInContainer(inv, Items.CHARCOAL);
            int ingot    = countInContainer(inv, Items.IRON_INGOT);

            boolean hasMaterials = rawIron > 0 || rawCopper > 0 || coal > 0 || charcoal > 0 || ingot > 0;
            if (hasMaterials) outWithMaterials++;
            outCount++;

            outSb.append("  §c").append(fmtPos(immPos)).append("§r")
              .append(" [").append(be.getClass().getSimpleName()).append("]")
              .append(" raw_iron=").append(rawIron)
              .append(" raw_copper=").append(rawCopper)
              .append(" coal=").append(coal)
              .append(" charcoal=").append(charcoal)
              .append(" iron_ingot=").append(ingot);
            if (hasMaterials) outSb.append(" §c← HAS MATERIALS§r");
            outSb.append("\n");
        }

        if (outCount == 0) {
            sb.append("  §a(none — all nearby containers are inside the box)§r\n");
        } else {
            sb.append("  ").append(outCount).append(" out-of-bounds container(s) found;")
              .append(" ").append(outWithMaterials).append(" with key materials:\n");
            sb.append(outSb);
        }

        // ── 5. Bottom line ───────────────────────────────────────────────────
        sb.append("\n§e--- Bottom line ---§r\n");
        int inRawIron = inKeyTotals.getOrDefault(Items.RAW_IRON, 0);
        int inCoal    = inKeyTotals.getOrDefault(Items.COAL, 0)
                      + inKeyTotals.getOrDefault(Items.CHARCOAL, 0);
        int inIngot   = inKeyTotals.getOrDefault(Items.IRON_INGOT, 0);

        sb.append("  Building box ").append(dx).append("×").append(dy).append("×").append(dz)
          .append(" at ").append(fmtPos(origin)).append("; findInventories sees §f")
          .append(inBound.size()).append("§r container(s).\n");
        sb.append("  raw_iron=").append(inRawIron)
          .append(" fuel(coal+charcoal)=").append(inCoal)
          .append(" iron_ingot=").append(inIngot).append(" in-bounds.\n");

        if (outWithMaterials > 0) {
            sb.append("§c  ").append(outWithMaterials)
              .append(" container(s) with key materials are OUTSIDE the box\n");
            sb.append("  → footprint doesn't cover them — this is the likely bug.§r\n");
            sb.append("  Fix: rebuild/re-place the building so its footprint covers the chests,\n");
            sb.append("  or move the chests inside the recorded building bounds.\n");
        } else if (inBound.isEmpty()) {
            sb.append("§c  No containers inside the box at all.\n");
            sb.append("  → Storage system sees nothing; fulfillments will decline.§r\n");
        } else if (inRawIron == 0 && inCoal == 0 && inIngot == 0) {
            sb.append("§7  Storage IS visible (").append(inBound.size()).append(" container(s) in-bounds)\n");
            sb.append("  but key smelting/crafting items are absent.\n");
            sb.append("  → Stock the workshop chests with raw_iron + coal, or\n");
            sb.append("    place an iron_ingot purchase via the market.§r\n");
        } else {
            sb.append("§a  raw_iron/coal/ingot ARE visible in-bounds.\n");
            sb.append("  → Storage access is fine; fulfillment decline is elsewhere.\n");
            sb.append("  → Check: amenity present? (FURNACE/ANVIL), skill XP, market for buying.§r\n");
        }
    }

    /** Count occurrences of {@code item} in a single {@link Container}. */
    private static int countInContainer(Container inv, Item item) {
        int total = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.is(item)) total += s.getCount();
        }
        return total;
    }

    private static String fmtPos(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    // ── Activity label helpers ───────────────────────────────────────────────────────────────

    private static String activityLabel(Activity act) {
        return act.getName().toUpperCase(Locale.ROOT);
    }

    private static String currentActivityName(net.minecraft.world.entity.ai.Brain<?> brain,
                                               Activity[] candidates) {
        for (Activity act : candidates) {
            if (brain.isActive(act)) return activityLabel(act);
        }
        return "unknown";
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
