package tterrag1112.life_in_the_village.Npc.Tasks.Priest;

import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Tasks.Fulfillment;
import tterrag1112.life_in_the_village.Npc.Tasks.Objective;
import tterrag1112.life_in_the_village.Npc.Tasks.Task;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskActor;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskContext;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskExecutor;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;

/**
 * G4 — fulfillment strategy for {@code officiate_rite} tasks.
 *
 * <h3>canFulfill criteria</h3>
 * <ul>
 *   <li>Objective is {@link Objective.PerformService} with kind
 *       {@link PriestVerb#OFFICIATE_RITE}.</li>
 *   <li>Actor is a {@link Profession#PRIEST} with an assigned TEMPLE,
 *       CHAPEL, or SHRINE building.</li>
 * </ul>
 *
 * <h3>Kind exclusivity</h3>
 * {@code officiate_rite} is disjoint from all existing PERFORM_SERVICE kinds:
 * {@code farm_harvest}, {@code farm_replant}, {@code farm_till},
 * {@code farm_compost}, {@code animal_tend}, {@code shear}, {@code collect_honey},
 * and the SCRIBE scribal-commission (self-filtered by the ScribeWriteFulfillment).
 * Registered under PERFORM_SERVICE in {@code Fulfillments.install()}.
 *
 * <h3>score</h3>
 * Constant 10.0 — the priority tier on the task already ranks rites against
 * each other. A single score avoids accidentally beating farm tasks that share
 * the same profession-scoped board (they live on different boards in practice
 * since FARMER and PRIEST don't share a BUSINESS board, but future multi-
 * profession households might).
 *
 * <h3>Executor lifecycle</h3>
 * A fresh {@link OfficiateRiteExecutor} per claimed task (same pattern as
 * {@code FarmCropFulfillment} and {@code ScribeWriteFulfillment}) keeps per-run
 * phase state isolated. The executor's {@link OfficiateRiteExecutor#onFailed}
 * method handles presider cleanup on FAILED-while-PENDING; it is called by the
 * thin wrapper below.
 */
public final class PriestFulfillment implements Fulfillment {

    @Override
    public boolean canFulfill(Task task, TaskActor actor, TaskContext ctx) {
        if (!(task.objective() instanceof Objective.PerformService ps)) return false;
        if (!PriestVerb.OFFICIATE_RITE.equals(ps.kind())) return false;

        TownspersonMob npc = ctx.npc().orElse(null);
        if (npc == null || npc.getProfession() != Profession.PRIEST) return false;

        // Must have an assigned TEMPLE / CHAPEL / SHRINE
        ServerLevel level = ctx.level();
        return npc.getAssignedBuildingId()
                .flatMap(id -> tterrag1112.life_in_the_village.Networking.VillageSavedData
                        .get(level).getBuildingById(id))
                .map(PriestTaskSource::isReligiousBuilding)
                .orElse(false);
    }

    @Override
    public double score(Task task, TaskActor actor, TaskContext ctx) {
        return 10.0;
    }

    @Override
    public TaskExecutor executor() {
        // Wrap OfficiateRiteExecutor so we can route FAILED → onFailed for
        // presider cleanup without modifying the TaskExecutor contract.
        return new WrappedOfficiateExecutor();
    }

    /**
     * Thin wrapper that delegates all ticks to a fresh {@link OfficiateRiteExecutor}
     * and calls {@link OfficiateRiteExecutor#onFailed} on FAILED so the presider
     * field is cleared (allowing another priest or the abstract fallback to take
     * the rite).
     */
    private static final class WrappedOfficiateExecutor implements TaskExecutor {

        private final OfficiateRiteExecutor inner = new OfficiateRiteExecutor();

        @Override
        public Result tick(Task task, TaskActor actor, TaskContext ctx) {
            Result result = inner.tick(task, actor, ctx);
            if (result == Result.FAILED) {
                TownspersonMob npc = ctx.npc().orElse(null);
                if (npc != null) {
                    inner.onFailed(ctx.level(), npc);
                }
            }
            return result;
        }
    }
}
