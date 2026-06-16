package tterrag1112.life_in_the_village.Npc.Tasks.Mine;

import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Tasks.Fulfillment;
import tterrag1112.life_in_the_village.Npc.Tasks.Objective;
import tterrag1112.life_in_the_village.Npc.Tasks.Task;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskActor;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskContext;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskExecutor;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;

/**
 * G5a — fulfillment strategy for {@code mine} tasks.
 *
 * <h3>canFulfill criteria</h3>
 * <ul>
 *   <li>Objective is {@link Objective.PerformService} with kind
 *       {@link MineVerb#MINE}.</li>
 *   <li>Actor is a {@link Profession#MINER} with an assigned MINE
 *       building.</li>
 * </ul>
 *
 * <h3>Kind exclusivity</h3>
 * {@code mine} is disjoint from all existing PERFORM_SERVICE kinds:
 * {@code farm_harvest}, {@code farm_replant}, {@code farm_till},
 * {@code farm_compost}, {@code animal_tend}, {@code shear},
 * {@code collect_honey}, {@code officiate_rite}, {@code monastic_craft}.
 * Registered under PERFORM_SERVICE in {@code Fulfillments.install()}.
 *
 * <h3>Executor lifecycle</h3>
 * A fresh {@link MineExecutor} per claimed task keeps per-run phase state
 * isolated — same pattern as other PERFORM_SERVICE fulfillments.
 */
public final class MineFulfillment implements Fulfillment {

    @Override
    public boolean canFulfill(Task task, TaskActor actor, TaskContext ctx) {
        if (!(task.objective() instanceof Objective.PerformService ps)) return false;
        if (!MineVerb.MINE.equals(ps.kind())) return false;

        TownspersonMob npc = ctx.npc().orElse(null);
        if (npc == null || npc.getProfession() != Profession.MINER) return false;

        // Must have an assigned MINE
        ServerLevel level = ctx.level();
        return npc.getAssignedBuildingId()
                .flatMap(id -> VillageSavedData.get(level).getBuildingById(id))
                .map(b -> b.getType() == BuildingType.MINE)
                .orElse(false);
    }

    @Override
    public double score(Task task, TaskActor actor, TaskContext ctx) {
        return 10.0;
    }

    @Override
    public TaskExecutor executor() {
        return new MineExecutor();
    }
}
