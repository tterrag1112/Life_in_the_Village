package tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import tterrag1112.life_in_the_village.Entities.ActivityState;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.ProfessionRoleManager;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.Workshop.WorkshopRole;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.Workshop.WorkshopRoleAssigner;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Brain.Behaviors.GreetPlayerBehavior;
import tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Homestead.ContextProductionBehavior;
import tterrag1112.life_in_the_village.Npc.Brain.Memories.NpcMemoryTypes;
import tterrag1112.life_in_the_village.Village.AmenityType;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Economy.Resources.ProductionHelpers;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * E-S4a — the BLACKSMITH workshop as a <i>context</i> over the shared
 * {@link ContextProductionBehavior} primitive, replacing the former
 * {@code AbstractProductionBehavior}-based class (convert-then-delete). The
 * blacksmith is the hardest profession: two recipe flavors (smelt / craft) with
 * different workstations and fuel, a specialization that biases craft selection
 * and grants a +50% XP bonus, and (E-S4a) genuinely skill-owned recipes folded
 * into {@code SkillRecipes}.
 *
 * <h3>Parity with the former class</h3>
 * <ul>
 *   <li><b>Crafts + selection</b> — the smelt / craft bins and the
 *       commission/quota-target → opportunistic-smelt → spec-weighted-craft
 *       selection live in {@link BlacksmithCrafts}.</li>
 *   <li><b>Smelt vs craft workstation</b> — a {@code smelt} plan walks to a
 *       {@link AmenityType#FURNACE}; a craft plan walks to an
 *       {@link AmenityType#ANVIL} (set as the {@code Plan.workstationPos}).</li>
 *   <li><b>Fuel</b> — a smelt plan opts into the primitive's fuel hook
 *       ({@code COAL × 2} per batch), consumed during deposit from the
 *       workBuilding; craft plans pass no fuel.</li>
 *   <li><b>Specialty XP</b> — {@link BlacksmithCrafts#xpFor} routes base 3 to
 *       the output's category sub-skill, ×1.5 on a specialty match; the result
 *       is baked into {@code Plan.xpPerBatch} and the plan's {@code skill} is
 *       the category sub-skill, so the primitive's flat award reproduces the
 *       former {@code awardProductionXp} (the cascade still propagates to
 *       BLACKSMITHING / CRAFTING from there).</li>
 *   <li><b>Buy</b> — {@link BlacksmithCrafts#resourcesToBuy} buys ore to ingot
 *       quota; E-S3 buy-only convention (no mine/stockpile sourcing — deferred
 *       to the supply-chain pass; see "Deviations").</li>
 *   <li><b>Sell, ledger, gates, role</b> — identical to the Candlemaker /
 *       Stonemason E-S2/E-S3 structure.</li>
 * </ul>
 */
public class BlacksmithProductionBehavior extends ContextProductionBehavior {

    private static final ActivityState BLOCKED_ROLE        = ActivityState.IDLE.withBlocking("workshop role gate");
    private static final ActivityState BLOCKED_OFF_WORK    = ActivityState.IDLE.withBlocking("off work hours");
    private static final ActivityState BLOCKED_WORK_BLOCK  = ActivityState.IDLE.withBlocking("injured / asleep / overridden");
    private static final ActivityState BLOCKED_NO_BUILDING = ActivityState.IDLE.withBlocking("no assigned work building");

    private static final long ROLE_CHECK_INTERVAL = 24000L;
    private long lastRoleCheckTick = -ROLE_CHECK_INTERVAL;

    /** Resolved at gate time; reused by selectPlan / onNoPlan in the same tick. */
    private Building workBuilding;

    @Override
    protected boolean checkContextGate(ServerLevel level, TownspersonMob entity) {
        if (ProfessionRoleManager.isMarketSeller(entity)) {
            entity.setActivityState(BLOCKED_ROLE);
            return false;
        }
        if (!entity.isWorkTime()) {
            entity.setActivityState(BLOCKED_OFF_WORK);
            return false;
        }
        if (entity.isWorkingBlocked()) {
            entity.setActivityState(BLOCKED_WORK_BLOCK);
            return false;
        }
        if (GreetPlayerBehavior.isGreetPending(entity)) return false;

        workBuilding = ProductionHelpers
                .findAssignedBuilding(entity, level, BuildingType.BLACKSMITH)
                .orElse(null);
        if (workBuilding == null) {
            entity.setActivityState(BLOCKED_NO_BUILDING);
            return false;
        }
        tickRoleCheck(level, workBuilding);
        return true;
    }

    @Override
    protected Optional<Plan> selectPlan(ServerLevel level, TownspersonMob entity) {
        if (workBuilding == null) return Optional.empty();
        BlacksmithCrafts.BlacksmithCraft craft =
                BlacksmithCrafts.chooseCraft(level, workBuilding, entity);
        if (craft == null) return Optional.empty();
        // Fix C — smelt batches are bounded by available coal; craft batches
        // (no fuel) use the existing no-fuel overload via the empty Map.of().
        int batch = BlacksmithCrafts.batchSize(level, workBuilding, craft.recipe(),
                craft.fuelPerBatch());
        if (batch <= 0) return Optional.empty();

        // Furnace for smelt, anvil for craft (former isSmeltRecipe branch).
        BlockPos station = firstAmenityPos(level, workBuilding,
                craft.smelt() ? List.of(AmenityType.FURNACE) : List.of(AmenityType.ANVIL));
        // No workstation present ⇒ no runnable plan this tick (former buildSteps
        // returned an empty step list, aborting the cycle).
        if (station == null) return Optional.empty();

        entity.getBrain().eraseMemory(NpcMemoryTypes.NO_ACTIONABLE_WORK.get());

        // 11-arg Plan: batch + multipliers + ledger + fuel (coal for smelt only).
        // plan.skill() = the output's category sub-skill so the primitive's flat
        // SkillXp.award reproduces the former awardProductionXp routing; the
        // specialty ×1.5 bonus is already folded into craft.xpPerBatch().
        return Optional.of(new Plan(workBuilding, station,
                craft.recipe(), craft.skill(), craft.xpPerBatch(),
                craft.activityLabel(),
                batch, /*applyMultipliers*/ true, /*recordLedger*/ true,
                craft.fuelPerBatch(), /*fuelSource*/ workBuilding));
    }

    @Override
    protected void onNoPlan(ServerLevel level, TownspersonMob entity) {
        if (workBuilding != null) {
            Map<Item, Integer> toBuy = resourcesToBuy(level, entity, workBuilding);
            procure(level, entity, workBuilding, toBuy);
        }
        entity.setActivityState(ActivityState.IDLE.withBlocking("no viable smithing work"));
        entity.getBrain().setMemory(NpcMemoryTypes.NO_ACTIONABLE_WORK.get(), Boolean.TRUE);
    }

    @Override
    protected Map<Item, Integer> resourcesToBuy(ServerLevel level, TownspersonMob entity,
                                                Building workBuilding) {
        return BlacksmithCrafts.resourcesToBuy(level, workBuilding);
    }

    @Override
    protected Optional<VendingIntent> vendingIntent(ServerLevel level, TownspersonMob entity,
                                                    Plan plan) {
        Building market = ProductionHelpers.findMarketInVillage(entity, level).orElse(null);
        if (market == null) return Optional.empty();
        return Optional.of(new VendingIntent(market, BlacksmithCrafts.sellableOutputs(),
                BlacksmithCrafts.quotas(), DEFAULT_SURPLUS_THRESHOLD,
                BlacksmithCrafts.SELL_WINDOW_DAY_TICK));
    }

    @Override
    protected float roleSpeedMultiplier(TownspersonMob entity) {
        if (entity == null) return 1.0f;
        WorkshopRole role = ProfessionRoleManager.getRole(entity, WorkshopRole.class);
        if (role == WorkshopRole.APPRENTICE) {
            return 1.0f / WorkshopRole.APPRENTICE.productionSpeedMultiplier();
        }
        return 1.0f;
    }

    private void tickRoleCheck(ServerLevel level, Building building) {
        if (building == null) return;
        long tick = level.getGameTime();
        if (tick - lastRoleCheckTick >= ROLE_CHECK_INTERVAL) {
            lastRoleCheckTick = tick;
            WorkshopRoleAssigner.assignRoles(level, building);
        }
    }
}
