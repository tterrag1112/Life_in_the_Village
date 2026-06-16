package tterrag1112.life_in_the_village.Npc.Tasks.Farm;

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
 * G1 — the single fulfillment strategy for all four farm crop verbs
 * (harvest / replant / till / compost). Registered once under
 * {@link Objective.Type#PERFORM_SERVICE}; self-filters tightly so it
 * only claims farm-crop {@code PerformService} tasks for FARMER NPCs
 * with an assigned FARMHOUSE.
 *
 * <h3>canFulfill criteria</h3>
 * <ul>
 *   <li>Objective is {@link Objective.PerformService} with a
 *       {@link FarmVerb#isCropVerb crop verb}.</li>
 *   <li>Actor is a {@link Profession#FARMER} with an assigned building
 *       of type {@link BuildingType#FARMHOUSE}.</li>
 * </ul>
 *
 * <h3>score per verb</h3>
 * Harvest scores highest (10) since it is the primary production act;
 * replant/till/compost score lower (5) since they are LOW-tier and
 * tier ordering on the board already de-prioritises them relative to
 * harvest. Simple constant scores are sufficient — intra-verb ordering
 * is already handled by {@link tterrag1112.life_in_the_village.Npc.Tasks.TaskBoard#RANKING}
 * (tier desc → urgency desc).
 *
 * <h3>executor</h3>
 * A fresh executor instance per claimed task keeps per-run phase state
 * isolated (exactly like {@code ScribeWriteFulfillment}).
 */
public final class FarmCropFulfillment implements Fulfillment {

    @Override
    public boolean canFulfill(Task task, TaskActor actor, TaskContext ctx) {
        if (!(task.objective() instanceof Objective.PerformService ps)) return false;
        if (!FarmVerb.isCropVerb(ps.kind())) return false;

        TownspersonMob npc = ctx.npc().orElse(null);
        if (npc == null || npc.getProfession() != Profession.FARMER) return false;

        // Must have an assigned farmhouse
        ServerLevel level = ctx.level();
        boolean hasFarmhouse = npc.getAssignedBuildingId()
                .flatMap(id -> tterrag1112.life_in_the_village.Networking.VillageSavedData
                        .get(level).getBuildingById(id))
                .map(b -> b.getType() == BuildingType.FARMHOUSE)
                .orElse(false);
        if (!hasFarmhouse) return false;

        return true;
    }

    @Override
    public double score(Task task, TaskActor actor, TaskContext ctx) {
        if (!(task.objective() instanceof Objective.PerformService ps)) return 0.0;
        return switch (ps.kind()) {
            case FarmVerb.HARVEST -> 10.0;
            // replant/till/compost are all LOW tier; flat parity is fine
            default               ->  5.0;
        };
    }

    @Override
    public TaskExecutor executor() {
        // Dispatch to the right executor based on the verb stored in the task.
        // We return a thin dispatch executor rather than one concrete executor
        // so a single Fulfillment registration covers all four verbs.
        return new DispatchingFarmExecutor();
    }

    /**
     * Thin dispatcher: peeks at the task's verb on first tick and delegates
     * all subsequent ticks to the appropriate concrete executor. Created fresh
     * per claimed task so the concrete executor's phase state is isolated.
     */
    static final class DispatchingFarmExecutor implements TaskExecutor {

        private TaskExecutor delegate;

        @Override
        public Result tick(Task task, TaskActor actor, TaskContext ctx) {
            if (delegate == null) {
                delegate = resolveDelegate(task);
                if (delegate == null) return Result.FAILED;
            }
            return delegate.tick(task, actor, ctx);
        }

        private static TaskExecutor resolveDelegate(Task task) {
            if (!(task.objective() instanceof Objective.PerformService ps)) return null;
            return switch (ps.kind()) {
                case FarmVerb.HARVEST -> new HarvestExecutor();
                case FarmVerb.REPLANT -> new ReplantExecutor();
                case FarmVerb.TILL    -> new TillExecutor();
                case FarmVerb.COMPOST -> new CompostExecutor();
                default               -> null;
            };
        }
    }
}
