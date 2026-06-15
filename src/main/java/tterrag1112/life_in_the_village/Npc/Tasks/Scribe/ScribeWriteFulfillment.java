package tterrag1112.life_in_the_village.Npc.Tasks.Scribe;

import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Scribal.CommissionQueue;
import tterrag1112.life_in_the_village.Npc.Scribal.CommissionStatus;
import tterrag1112.life_in_the_village.Npc.Scribal.ScribeCommission;
import tterrag1112.life_in_the_village.Npc.Tasks.Fulfillment;
import tterrag1112.life_in_the_village.Npc.Tasks.Objective;
import tterrag1112.life_in_the_village.Npc.Tasks.Task;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskActor;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskContext;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskExecutor;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;

import java.util.Optional;
import java.util.UUID;

/**
 * T3 — the single strategy for a scribal {@code PerformService} task. It
 * self-filters tightly so it never grabs a non-scribal service or acts for a
 * non-scribe:
 * <ul>
 *   <li>the objective is {@code PerformService} with kind
 *       {@link ScribeService#KIND} and a parseable commission ref;</li>
 *   <li>the acting NPC is a {@link Profession#SCRIBE} with an assigned
 *       SCRIBE_WORKSHOP (the workstation);</li>
 *   <li>the referenced commission is still PENDING in that workshop's
 *       {@link CommissionQueue} (so a commission already in flight / delivered
 *       isn't re-picked).</li>
 * </ul>
 *
 * <p>Registered under {@code Objective.Type.PERFORM_SERVICE}. Because no other
 * fulfillment is registered for that type, and this one declines any
 * non-scribal {@code PerformService}, the scribe path and any future service
 * never collide. The executor ({@link ScribeWriteExecutor}) is created fresh
 * per claimed task so its per-run phase state is isolated.</p>
 */
public final class ScribeWriteFulfillment implements Fulfillment {

    @Override
    public boolean canFulfill(Task task, TaskActor actor, TaskContext ctx) {
        UUID commissionId = ScribeWriteExecutor.commissionIdOf(task).orElse(null);
        if (commissionId == null) return false;

        TownspersonMob npc = ctx.npc().orElse(null);
        if (npc == null || npc.getProfession() != Profession.SCRIBE) return false;

        ServerLevel level = ctx.level();
        Building workshop = workshop(level, npc).orElse(null);
        if (workshop == null) return false;

        CommissionQueue queue = VillageSavedData.get(level)
                .getOrCreateCommissionQueue(workshop.getId());
        ScribeCommission c = queue.get(commissionId).orElse(null);
        return c != null && c.status() == CommissionStatus.PENDING;
    }

    @Override
    public double score(Task task, TaskActor actor, TaskContext ctx) {
        // Writing a commission is the scribe's primary act; flat high score.
        // Inter-task ordering is Priority on the board, not here.
        return 10.0;
    }

    @Override
    public TaskExecutor executor() {
        return new ScribeWriteExecutor();
    }

    private Optional<Building> workshop(ServerLevel level, TownspersonMob npc) {
        return npc.getAssignedBuildingId()
                .flatMap(id -> VillageSavedData.get(level).getBuildingById(id))
                .filter(b -> b.getType() == BuildingType.SCRIBE_WORKSHOP);
    }
}
