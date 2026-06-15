package tterrag1112.life_in_the_village.Npc.Tasks.Scribe;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Scribal.CommissionQueue;
import tterrag1112.life_in_the_village.Npc.Scribal.CommissionStatus;
import tterrag1112.life_in_the_village.Npc.Scribal.ScribeCommission;
import tterrag1112.life_in_the_village.Npc.Tasks.Assignment;
import tterrag1112.life_in_the_village.Npc.Tasks.IssuerRef;
import tterrag1112.life_in_the_village.Npc.Tasks.LevelKind;
import tterrag1112.life_in_the_village.Npc.Tasks.Objective;
import tterrag1112.life_in_the_village.Npc.Tasks.Priority;
import tterrag1112.life_in_the_village.Npc.Tasks.Producer.ProductionTaskIds;
import tterrag1112.life_in_the_village.Npc.Tasks.Task;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskBoard;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskContext;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskFilter;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskId;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskSavedData;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskSource;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Profession.Tasks.TaskPriority;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * T3 — the WORK-scope task source that mirrors a SCRIBE_WORKSHOP's
 * {@link CommissionQueue} onto the acting scribe's task board. The scribal
 * analogue of {@code ProductionTaskSource}: on the dispatcher's lazy refresh
 * cadence it emits one {@code PerformService("scribal_commission", commissionId)}
 * task per PENDING commission and removes board tasks whose commission has gone
 * terminal / vanished.
 *
 * <h3>The queue stays the source of truth</h3>
 * This source NEVER creates, mutates, or deletes a commission — it only mirrors
 * the queue's PENDING set onto the board. Commission creation stays with the GUI
 * / verbs / apprenticeship factory, and the lifecycle transitions
 * (PENDING&rarr;IN_PROGRESS&rarr;READY&rarr;DELIVERED, and revert-to-PENDING on abort)
 * are owned by {@link ScribeWriteFulfillment}'s executor exactly as the legacy
 * behavior owned them. A non-PENDING commission is removed from the board here
 * once it is no longer being actively written (its task is unclaimed), so the
 * READY/IN_PROGRESS states the executor holds mid-run are not yanked away.
 *
 * <h3>Stable, idempotent ids</h3>
 * Each task uses the STABLE id {@code stable(issuer, "commission:" + commissionId)}
 * so a refresh updates priority in place rather than piling duplicates.
 *
 * <h3>Issuer resolution</h3>
 * Identical to the producer source: the BUSINESS board when the scribe has a
 * business id, else its own personal NPC board. Both are WORK-scope (non-
 * HOUSEHOLD), so the WORK dispatcher scans them and the HOUSEHOLD dispatcher
 * never sees these tasks.
 */
public final class ScribeCommissionTaskSource implements TaskSource {

    private final IssuerRef issuer;
    private final Building workshop;

    private ScribeCommissionTaskSource(IssuerRef issuer, Building workshop) {
        this.issuer = issuer;
        this.workshop = workshop;
    }

    /**
     * Resolve the source for {@code npc}, or empty if the NPC is not a SCRIBE
     * or has no assigned SCRIBE_WORKSHOP.
     */
    public static Optional<ScribeCommissionTaskSource> forNpc(ServerLevel level, TownspersonMob npc) {
        if (npc.getProfession() != Profession.SCRIBE) return Optional.empty();
        Building workshop = npc.getAssignedBuildingId()
                .flatMap(id -> VillageSavedData.get(level).getBuildingById(id))
                .filter(b -> b.getType() == BuildingType.SCRIBE_WORKSHOP)
                .orElse(null);
        if (workshop == null) return Optional.empty();
        return Optional.of(new ScribeCommissionTaskSource(resolveIssuer(npc), workshop));
    }

    /** Convenience used by the dispatcher's refresh: resolve + generate. */
    public static void generateFor(ServerLevel level, TownspersonMob npc, TaskContext ctx) {
        forNpc(level, npc).ifPresent(src -> src.generate(ctx));
    }

    /** Same board resolution as the producer source: BUSINESS if present, else NPC. */
    static IssuerRef resolveIssuer(TownspersonMob npc) {
        UUID businessId = npc.getBusinessId().orElse(null);
        if (businessId != null) return new IssuerRef(LevelKind.BUSINESS, businessId);
        return new IssuerRef(LevelKind.NPC, npc.getUUID());
    }

    public IssuerRef issuer() { return issuer; }

    @Override
    public void generate(TaskContext ctx) {
        ServerLevel level = ctx.level();
        TaskSavedData data = TaskSavedData.get(level);
        TaskBoard board = data.board(issuer);

        CommissionQueue queue = VillageSavedData.get(level)
                .getOrCreateCommissionQueue(workshop.getId());

        // Eligibility: only a SCRIBE with a workstation may claim these.
        TaskFilter filter = new TaskFilter(
                Optional.empty(), 0, Optional.empty(),
                Optional.of(Profession.SCRIBE), Optional.empty(), true);

        // ── Upsert one task per PENDING commission ───────────────────────────
        Set<TaskId> live = new HashSet<>();
        for (ScribeCommission c : queue.all()) {
            if (c.status() != CommissionStatus.PENDING) continue;
            TaskId id = ProductionTaskIds.stable(issuer,
                    ScribeService.ID_PREFIX + c.commissionId());
            live.add(id);
            upsert(board, data, id, c, filter);
        }

        // ── Remove board tasks whose commission is gone / terminal / non-PENDING ─
        // Only unclaimed tasks are pruned; a task the executor holds mid-write
        // (commission flipped to IN_PROGRESS/READY) is claimed, so it survives.
        for (Task t : board.all()) {
            if (!(t.objective() instanceof Objective.PerformService ps)) continue;
            if (!ScribeService.KIND.equals(ps.kind())) continue;
            if (live.contains(t.id())) continue;
            if (!t.assignment().claimants().isEmpty()) continue;
            board.remove(t.id());
            data.markChanged();
        }
    }

    private void upsert(TaskBoard board, TaskSavedData data, TaskId id,
                        ScribeCommission c, TaskFilter filter) {
        Priority priority = priorityFor(c);
        Optional<Task> existing = board.get(id);
        if (existing.isPresent()) {
            existing.get().setPriority(priority);
            data.markChanged();
            return;
        }
        Objective obj = new Objective.PerformService(
                ScribeService.KIND, Optional.of(c.commissionId().toString()));
        Task t = new Task(id, issuer, obj, priority, filter,
                new Assignment(), List.of(), 0L, null);
        data.addTask(issuer, t);
    }

    /**
     * Map a commission's int {@code priority} to a task {@link Priority}.
     * Higher commission.priority &rarr; higher urgency; at/above
     * {@link ScribeService#HIGH_PRIORITY_THRESHOLD} the task issues at HIGH
     * (apprenticeship contracts = 15, letters = 10), otherwise NORMAL (book
     * copies = 3..5), so urgent legal/correspondence work sorts above routine
     * copying — mirroring the queue's own priority-desc ordering.
     */
    private static Priority priorityFor(ScribeCommission c) {
        TaskPriority tier = c.priority() >= ScribeService.HIGH_PRIORITY_THRESHOLD
                ? TaskPriority.HIGH : TaskPriority.NORMAL;
        float urgency = Mth.clamp(c.priority() / ScribeService.URGENCY_SCALE, 0f, 1f);
        return new Priority(tier, urgency);
    }
}
