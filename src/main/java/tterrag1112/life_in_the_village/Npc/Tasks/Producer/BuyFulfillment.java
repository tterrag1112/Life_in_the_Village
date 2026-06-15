package tterrag1112.life_in_the_village.Npc.Tasks.Producer;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production.WorkshopProcurement;
import tterrag1112.life_in_the_village.Npc.Tasks.Fulfillment;
import tterrag1112.life_in_the_village.Npc.Tasks.Task;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskActor;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskContext;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskExecutor;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Economy.Resources.ProductionHelpers;

import java.util.Map;
import java.util.Optional;

/**
 * Generic buy fulfillment for {@code Acquire(item)} / {@code MaintainStock(item)}: satisfies
 * the acquisition of <em>any</em> item by purchasing it through the shared
 * {@link WorkshopProcurement#buy} pipeline, parameterized by a
 * {@link ProductionTaskSpec}.
 *
 * <p>This is the generic counterpart to the blacksmith-specific
 * {@code BuyIngotFulfillment}. Professions whose intermediates are all
 * PURCHASED (Candlemaker, Weaver, Carpenter, Stonemason) register one instance
 * of this per spec; it handles any {@code Acquire} task their
 * {@link CraftOutputFulfillment} lazily spawns when a recipe input is short.</p>
 *
 * <p>The blacksmith continues to use its own {@code BuyIngotFulfillment} (with
 * bespoke smelt-vs-buy scoring); this class is never registered for it.</p>
 *
 * <h3>Score</h3>
 * Flat {@code 2.0} — always the sole acquisition strategy for the four
 * buy-only professions, so no competing fulfillment needs to out-score it.
 */
public final class BuyFulfillment implements Fulfillment {

    private final ProductionTaskSpec spec;
    private final WorkshopProcurement.DiagFlags diag = new WorkshopProcurement.DiagFlags();

    public BuyFulfillment(ProductionTaskSpec spec) {
        this.spec = spec;
    }

    @Override
    public boolean canFulfill(Task task, TaskActor actor, TaskContext ctx) {
        Item target = AcquireObjectives.itemOf(task.objective()).orElse(null);
        if (target == null) return false;
        TownspersonMob npc = ctx.npc().orElse(null);
        if (npc == null || npc.getProfession() != spec.profession()) return false;
        return building(ctx.level(), npc).isPresent();
    }

    @Override
    public double score(Task task, TaskActor actor, TaskContext ctx) {
        return 2.0;
    }

    @Override
    public TaskExecutor executor() {
        return new OrderExecutor(spec, diag);
    }

    private Optional<Building> building(ServerLevel level, TownspersonMob npc) {
        return ProductionHelpers.findAssignedBuilding(npc, level, spec.buildingType());
    }

    /** One-shot buy-order executor: places a single procurement order for the
     *  shortfall, then reports DONE. The bought items land in the work building
     *  via {@link WorkshopProcurement#buy}. */
    private static final class OrderExecutor implements TaskExecutor {
        private final ProductionTaskSpec spec;
        private final WorkshopProcurement.DiagFlags diag;
        private boolean placed;

        OrderExecutor(ProductionTaskSpec spec, WorkshopProcurement.DiagFlags diag) {
            this.spec = spec;
            this.diag = diag;
        }

        @Override
        public Result tick(Task task, TaskActor actor, TaskContext ctx) {
            if (placed) return Result.DONE;
            TownspersonMob npc = ctx.npc().orElse(null);
            if (npc == null) return Result.FAILED;
            ServerLevel level = ctx.level();
            Building building = ProductionHelpers
                    .findAssignedBuilding(npc, level, spec.buildingType()).orElse(null);
            if (building == null) return Result.FAILED;

            Item target = AcquireObjectives.itemOf(task.objective()).orElse(null);
            if (target == null) return Result.FAILED;
            int want = AcquireObjectives.qtyOf(task.objective());
            if (want <= 0) return Result.FAILED;

            int stock = BuildingStorageAccess.countItem(level, building, target);
            int shortfall = Math.max(1, want - stock);
            WorkshopProcurement.buy(npc, building, Map.of(target, shortfall),
                    level, diag, "TaskBuy:" + spec.profession().name());
            placed = true;
            return Result.DONE;
        }
    }
}
