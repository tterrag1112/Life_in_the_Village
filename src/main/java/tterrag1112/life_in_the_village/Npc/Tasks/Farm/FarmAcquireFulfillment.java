package tterrag1112.life_in_the_village.Npc.Tasks.Farm;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production.WorkshopProcurement;
import tterrag1112.life_in_the_village.Npc.Tasks.Fulfillment;
import tterrag1112.life_in_the_village.Npc.Tasks.Objective;
import tterrag1112.life_in_the_village.Npc.Tasks.Producer.AcquireObjectives;
import tterrag1112.life_in_the_village.Npc.Tasks.Task;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskActor;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskContext;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskExecutor;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Buildings.FarmPlot;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * G1b — seed-buying fulfillment for FARMER NPCs.
 *
 * <p>Handles {@link Objective.Acquire} tasks emitted by {@link FarmTaskSource}
 * when a plot has empty farmland but no seeds. Purchases seeds via the shared
 * {@link WorkshopProcurement#buy} pipeline (farmhouse treasury pays, seeds
 * deposit into the farmhouse) — the same economic path the legacy
 * {@code FarmerBehavior.buySeeds()} used, just routed through the shared helper
 * instead of open-coded.</p>
 *
 * <p>Guarded by a seed-item whitelist (all {@link FarmPlot.CropType} seed
 * items) so this fulfillment never intercepts {@code Acquire} tasks from other
 * professions (e.g. the blacksmith's ingot acquisition).</p>
 *
 * <p>Coexists with
 * {@link tterrag1112.life_in_the_village.Npc.Tasks.Producer.BuyFulfillment}
 * and
 * {@link tterrag1112.life_in_the_village.Npc.Tasks.Blacksmith.BuyIngotFulfillment}
 * under {@link Objective.Type#ACQUIRE}: profession + seed-item guard
 * discriminates.</p>
 */
public final class FarmAcquireFulfillment implements Fulfillment {

    /**
     * The complete set of items that can be farming seeds — derived once from
     * all {@link FarmPlot.CropType} values so it stays in sync automatically
     * when new crop types are added.
     */
    private static final Set<Item> FARMING_SEEDS = Arrays.stream(FarmPlot.CropType.values())
            .map(FarmPlot.CropType::resolveSeedItem)
            .collect(Collectors.toUnmodifiableSet());

    private final WorkshopProcurement.DiagFlags diag = new WorkshopProcurement.DiagFlags();

    @Override
    public boolean canFulfill(Task task, TaskActor actor, TaskContext ctx) {
        // Only Acquire objectives
        Item target = AcquireObjectives.itemOf(task.objective()).orElse(null);
        if (target == null) return false;

        // Only FARMER NPCs with an assigned farmhouse
        TownspersonMob npc = ctx.npc().orElse(null);
        if (npc == null || npc.getProfession() != Profession.FARMER) return false;

        // Guard: only intercept known farming seed items
        if (!FARMING_SEEDS.contains(target)) return false;

        // Must have an assigned farmhouse
        ServerLevel level = ctx.level();
        return npc.getAssignedBuildingId()
                .flatMap(id -> tterrag1112.life_in_the_village.Networking.VillageSavedData
                        .get(level).getBuildingById(id))
                .map(b -> b.getType() == BuildingType.FARMHOUSE)
                .orElse(false);
    }

    @Override
    public double score(Task task, TaskActor actor, TaskContext ctx) {
        return 2.0; // Mirrors BuyFulfillment; sole acquisition strategy for seeds
    }

    @Override
    public TaskExecutor executor() {
        return new OrderExecutor(diag);
    }

    /**
     * One-shot buy-order executor: places a single procurement order for the
     * seed shortfall, then returns DONE. Bought seeds land in the farmhouse
     * via {@link WorkshopProcurement#buy}.
     */
    private static final class OrderExecutor implements TaskExecutor {
        private final WorkshopProcurement.DiagFlags diag;
        private boolean placed;

        OrderExecutor(WorkshopProcurement.DiagFlags diag) {
            this.diag = diag;
        }

        @Override
        public Result tick(Task task, TaskActor actor, TaskContext ctx) {
            if (placed) return Result.DONE;

            TownspersonMob npc = ctx.npc().orElse(null);
            if (npc == null) return Result.FAILED;

            ServerLevel level = ctx.level();
            Building farmhouse = npc.getAssignedBuildingId()
                    .flatMap(id -> tterrag1112.life_in_the_village.Networking.VillageSavedData
                            .get(level).getBuildingById(id))
                    .filter(b -> b.getType() == BuildingType.FARMHOUSE)
                    .orElse(null);
            if (farmhouse == null) return Result.FAILED;

            Item seed = AcquireObjectives.itemOf(task.objective()).orElse(null);
            if (seed == null) return Result.FAILED;
            int want = AcquireObjectives.qtyOf(task.objective());
            if (want <= 0) return Result.FAILED;

            int stock    = BuildingStorageAccess.countItem(level, farmhouse, seed);
            int shortfall = Math.max(1, want - stock);

            WorkshopProcurement.buy(
                    npc, farmhouse, Map.of(seed, shortfall),
                    level, diag, "FarmSeedBuy");

            placed = true;
            return Result.DONE;
        }
    }
}
