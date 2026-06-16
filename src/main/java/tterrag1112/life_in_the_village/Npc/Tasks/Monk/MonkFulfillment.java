package tterrag1112.life_in_the_village.Npc.Tasks.Monk;

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
 * G5a — fulfillment strategy for {@code monastic_craft} tasks.
 *
 * <h3>canFulfill criteria</h3>
 * <ul>
 *   <li>Objective is {@link Objective.PerformService} with kind
 *       {@link MonkVerb#MONASTIC_CRAFT}.</li>
 *   <li>Actor is a {@link Profession#MONK} with an assigned MONASTERY
 *       building.</li>
 * </ul>
 *
 * <h3>Kind exclusivity</h3>
 * {@code monastic_craft} is disjoint from all existing PERFORM_SERVICE
 * kinds: {@code farm_harvest}, {@code farm_replant}, {@code farm_till},
 * {@code farm_compost}, {@code animal_tend}, {@code shear},
 * {@code collect_honey}, {@code officiate_rite}, and {@code mine}.
 * Registered under PERFORM_SERVICE in {@code Fulfillments.install()}.
 *
 * <h3>Executor lifecycle</h3>
 * A fresh {@link MonkCraftExecutor} per claimed task keeps per-run
 * phase state (the lazily-initialised {@code ContextPlanExecutor} delegate)
 * isolated — same pattern as {@link tterrag1112.life_in_the_village.Npc.Tasks.Priest.PriestFulfillment}.
 */
public final class MonkFulfillment implements Fulfillment {

    @Override
    public boolean canFulfill(Task task, TaskActor actor, TaskContext ctx) {
        if (!(task.objective() instanceof Objective.PerformService ps)) return false;
        if (!MonkVerb.MONASTIC_CRAFT.equals(ps.kind())) return false;

        TownspersonMob npc = ctx.npc().orElse(null);
        if (npc == null || npc.getProfession() != Profession.MONK) return false;

        // Must have an assigned MONASTERY
        ServerLevel level = ctx.level();
        return npc.getAssignedBuildingId()
                .flatMap(id -> VillageSavedData.get(level).getBuildingById(id))
                .map(b -> b.getType() == BuildingType.MONASTERY)
                .orElse(false);
    }

    @Override
    public double score(Task task, TaskActor actor, TaskContext ctx) {
        return 10.0;
    }

    @Override
    public TaskExecutor executor() {
        return new MonkCraftExecutor();
    }
}
