package tterrag1112.life_in_the_village.Npc.Tasks.Producer;

import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Tasks.Fulfillment;
import tterrag1112.life_in_the_village.Npc.Tasks.Objective;
import tterrag1112.life_in_the_village.Npc.Tasks.Task;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskActor;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskContext;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskExecutor;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;

/**
 * T5b-3 — the strategy for a {@link Objective.Deliver} task. The producing NPC
 * carries its crafted share to the delivery destination (guild hall or
 * stockpile) the cascade resolved. No separate courier profession: whoever
 * crafts also delivers.
 *
 * <p>{@code canFulfill} only succeeds once the NPC's work building actually
 * holds at least one unit of the item — i.e. after the dependent craft task
 * produced a batch (the dependency gate plus this stock check together ensure
 * "craft, then deliver"). It does NOT require the whole share on hand: it
 * delivers whatever is available up to the remaining leg amount, and the
 * cascade re-arms the craft/deliver pair each daily pass until the request is
 * complete.</p>
 *
 * <p>Registered under {@code Objective.Type.DELIVER}; nothing else handles that
 * type, so this never collides with another strategy.</p>
 */
public final class DeliverFulfillment implements Fulfillment {

    @Override
    public boolean canFulfill(Task task, TaskActor actor, TaskContext ctx) {
        if (!(task.objective() instanceof Objective.Deliver deliver)) return false;
        TownspersonMob npc = ctx.npc().orElse(null);
        if (npc == null) return false;

        ServerLevel level = ctx.level();
        Building source = sourceBuilding(level, npc).orElse(null);
        if (source == null) return false;

        // Some stock to carry (>=1). The exact carried amount is min(stock,
        // remaining) resolved at execution time.
        int stock = BuildingStorageAccess.countItem(level, source, deliver.item());
        if (stock <= 0) return false;

        // Destination must be loaded + resolvable to a building.
        return DeliverExecutor.destinationBuilding(level, deliver.destination()).isPresent();
    }

    @Override
    public double score(Task task, TaskActor actor, TaskContext ctx) {
        // Delivering a fulfilled request is high-value work; flat high score so
        // it wins over routine surplus-selling but ranking across tasks is the
        // board's Priority, not this number.
        return 12.0;
    }

    @Override
    public TaskExecutor executor() {
        return new DeliverExecutor();
    }

    /** The producing NPC's assigned work building — where the crafted goods sit. */
    static java.util.Optional<Building> sourceBuilding(ServerLevel level, TownspersonMob npc) {
        return npc.getAssignedBuildingId()
                .flatMap(id -> VillageSavedData.get(level).getBuildingById(id));
    }
}
