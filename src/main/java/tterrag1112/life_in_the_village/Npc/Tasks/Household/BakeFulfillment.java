package tterrag1112.life_in_the_village.Npc.Tasks.Household;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Homestead.ContextProductionBehavior.Plan;
import tterrag1112.life_in_the_village.Npc.Tasks.Execution.ContextPlanExecutor;
import tterrag1112.life_in_the_village.Npc.Tasks.Fulfillment;
import tterrag1112.life_in_the_village.Npc.Tasks.Objective;
import tterrag1112.life_in_the_village.Npc.Tasks.Producer.AcquireObjectives;
import tterrag1112.life_in_the_village.Npc.Tasks.Task;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskActor;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskContext;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskExecutor;
import tterrag1112.life_in_the_village.Village.AmenityType;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;

import java.util.Optional;

/**
 * T2 — the BAKE strategy for a household {@code MaintainStock(BREAD)} food task
 * (the "bake" half of bake-vs-buy). The household analogue of the blacksmith's
 * smelt-own-ore strategy: it exercises the acting member's own BAKING skill,
 * consuming the house's wheat into bread deposited back into the house.
 *
 * <h3>canFulfill</h3>
 * The acting NPC is a household member (resolved via its {@code getHouseId()}),
 * has BAKING &ge; 1, the house has a baking amenity (SMOKER/FURNACE), and the
 * house has enough wheat for &ge; 1 batch. (The legacy BAKING-row gate, now
 * driven by the task instead of the IDLE behavior.)
 *
 * <h3>Execution</h3>
 * Reuses {@link ContextPlanExecutor} (the shared produce state machine) with a
 * {@link Plan} built from {@link HouseholdFood#BREAD_RECIPE}; the deposit
 * target is the HOUSE building (the Plan's {@code building}), so wheat is
 * consumed from and bread deposited into the house — exactly as the legacy
 * {@code HomeProductionBehavior} did. BAKING XP is awarded by the shared
 * machine.
 *
 * <h3>Score</h3>
 * Flat {@code 10.0} — when baking is viable it outscores
 * {@link BuyFoodFulfillment} (2.0), so a household with a baker + wheat bakes
 * rather than spending coin (Garrett's exact example), mirroring the smith's
 * smelt(10)-over-buy(2) preference.
 */
public final class BakeFulfillment implements Fulfillment {

    @Override
    public boolean canFulfill(Task task, TaskActor actor, TaskContext ctx) {
        if (!isFoodTask(task.objective())) return false;
        TownspersonMob npc = ctx.npc().orElse(null);
        if (npc == null) return false;
        ServerLevel level = ctx.level();
        Building house = house(level, npc).orElse(null);
        if (house == null) return false;
        return bakePlan(level, npc, house).isPresent();
    }

    @Override
    public double score(Task task, TaskActor actor, TaskContext ctx) {
        return 10.0;
    }

    @Override
    public TaskExecutor executor() {
        return new ContextPlanExecutor((level, npc, t) -> {
            Building house = house(level, npc).orElse(null);
            if (house == null) return Optional.empty();
            return bakePlan(level, npc, house);
        });
    }

    // ── Plan + gating ─────────────────────────────────────────────────────────

    /** A runnable bake plan (skill + amenity + wheat present), or empty. */
    private Optional<Plan> bakePlan(ServerLevel level, TownspersonMob npc, Building house) {
        if (npc.getSkills().getLevel(HouseholdFood.BAKE_SKILL) < HouseholdFood.BAKE_MIN_LEVEL) {
            return Optional.empty();
        }
        BlockPos station = AmenityType.firstPresent(level, house, HouseholdFood.BAKE_AMENITIES);
        if (station == null) return Optional.empty();
        // Inputs available for >= 1 batch (the HOME recipe is single-input wheat).
        for (var e : HouseholdFood.BREAD_RECIPE.inputs().entrySet()) {
            if (BuildingStorageAccess.countItem(level, house, e.getKey()) < e.getValue()) {
                return Optional.empty();
            }
        }
        // Legacy 6-arg (HOME) Plan shape: batch 1, no multipliers, no ledger, no
        // fuel — byte-identical to how HomeProductionBehavior built it. The HOUSE
        // building is both the input source and (via the default outputBuilding
        // hook) the deposit target.
        return Optional.of(new Plan(house, station, HouseholdFood.BREAD_RECIPE,
                HouseholdFood.BAKE_SKILL, HouseholdFood.BAKE_XP_PER_BATCH, HouseholdFood.BAKE_LABEL));
    }

    private Optional<Building> house(ServerLevel level, TownspersonMob npc) {
        return npc.getHouseId().flatMap(id -> VillageSavedData.get(level).getBuildingById(id));
    }

    /** True for a household food MaintainStock(BREAD) task. */
    private static boolean isFoodTask(Objective obj) {
        Item item = AcquireObjectives.itemOf(obj).orElse(null);
        return item == HouseholdFood.FOOD_ITEM
                && obj instanceof Objective.MaintainStock;
    }
}
