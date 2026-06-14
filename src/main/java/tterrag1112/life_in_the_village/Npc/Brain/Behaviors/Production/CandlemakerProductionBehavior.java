package tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production;

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
import tterrag1112.life_in_the_village.Npc.Skills.Skill;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Economy.Resources.ProductionHelpers;

import java.util.Map;
import java.util.Optional;

/**
 * E-S2 — the CANDLEMAKER workshop as a <i>context</i> over the shared
 * {@link ContextProductionBehavior} primitive (the third context after HOME and
 * MONASTERY). Replaces the former {@code AbstractProductionBehavior}-based
 * Candlemaker (convert-then-delete): candlemaker is the simplest profession —
 * no fuel, no JSON recipe feeder, no output redirect, no workstation (it works
 * at its building origin).
 *
 * <h3>Parity with the former class</h3>
 * <ul>
 *   <li><b>Crafts</b> — the three candle recipes + quotas live in
 *       {@link CandleCrafts}; {@link CandleCrafts#chooseCraft} reproduces the
 *       lowest-stock-ratio target selection + skill gate + input check.</li>
 *   <li><b>Batch + multipliers</b> — the {@link Plan} opts into batch sizing
 *       ({@link CandleCrafts#batchSize}, cap 8) and the industry × role ÷
 *       crafting tick scaling + quality bonus.</li>
 *   <li><b>Buy</b> — {@link #resourcesToBuy} returns the same basket; the
 *       primitive routes it through {@link WorkshopProcurement} on the no-plan
 *       tick.</li>
 *   <li><b>Sell</b> — {@link #vendingIntent} hands surplus off to the universal
 *       {@code SellToMarketBehavior} after each deposit.</li>
 *   <li><b>Ledger</b> — the plan fires
 *       {@code WorkplaceAssignmentManager.onWorkplaceProduction}.</li>
 *   <li><b>Gates + liveliness</b> — {@link #checkContextGate} reproduces the
 *       role / work-time / work-blocked / greet gate and records the same
 *       {@code NO_ACTIONABLE_WORK} liveliness signal.</li>
 *   <li><b>XP</b> — CANDLEMAKING, 3 per cycle (the profession's primary skill,
 *       matching APB's {@code awardProductionXp}).</li>
 * </ul>
 */
public class CandlemakerProductionBehavior extends ContextProductionBehavior {

    // Liveliness L0 — pre-built "why idle" reasons (parity with APB's set).
    private static final ActivityState BLOCKED_ROLE       = ActivityState.IDLE.withBlocking("workshop role gate");
    private static final ActivityState BLOCKED_OFF_WORK   = ActivityState.IDLE.withBlocking("off work hours");
    private static final ActivityState BLOCKED_WORK_BLOCK = ActivityState.IDLE.withBlocking("injured / asleep / overridden");
    private static final ActivityState BLOCKED_NO_BUILDING = ActivityState.IDLE.withBlocking("no assigned work building");

    private static final long ROLE_CHECK_INTERVAL = 24000L;
    private long lastRoleCheckTick = -ROLE_CHECK_INTERVAL;

    /** Resolved at gate time; reused by selectPlan / onNoPlan in the same tick. */
    private Building workBuilding;

    @Override
    protected boolean checkContextGate(ServerLevel level, TownspersonMob entity) {
        // Order mirrors AbstractProductionBehavior.checkExtraStartConditions
        // (role → work-time → work-blocked → greet → building); the not-child
        // and nav gates already ran in the primitive.
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
        // Customer-to-greet preempts production (parity with APB).
        if (GreetPlayerBehavior.isGreetPending(entity)) return false;

        workBuilding = ProductionHelpers
                .findAssignedBuilding(entity, level, BuildingType.CANDLEMAKER)
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
        // workBuilding was resolved in checkContextGate (same tick).
        if (workBuilding == null) return Optional.empty();
        CandleCrafts.CandleCraft craft = CandleCrafts.chooseCraft(level, workBuilding, entity);
        if (craft == null) return Optional.empty();
        int batch = CandleCrafts.batchSize(level, workBuilding, craft.recipe());
        if (batch <= 0) return Optional.empty();

        // Real work starting: clear the work-satisfied liveliness signal (parity
        // with APB's analyze() clear on the GATHERING transition).
        entity.getBrain().eraseMemory(NpcMemoryTypes.NO_ACTIONABLE_WORK.get());

        return Optional.of(new Plan(workBuilding, /*workstationPos*/ null,
                craft.recipe(), Skill.CANDLEMAKING, craft.xpPerBatch(),
                craft.activityLabel(),
                /*batchSize*/ batch, /*applyMultipliers*/ true, /*recordLedger*/ true));
    }

    @Override
    protected void onNoPlan(ServerLevel level, TownspersonMob entity) {
        // No runnable craft this tick. If inputs are short, attempt procurement
        // through the shared ChannelRouter pipeline (parity with APB's analyze()
        // buy attempt), then hold the work-satisfied signal so the WORK idle
        // director fills the gap (parity with APB's goIdle("...")).
        if (workBuilding != null) {
            Map<Item, Integer> toBuy = resourcesToBuy(level, entity, workBuilding);
            procure(level, entity, workBuilding, toBuy);
        }
        entity.setActivityState(ActivityState.IDLE.withBlocking("no viable candle craft"));
        entity.getBrain().setMemory(NpcMemoryTypes.NO_ACTIONABLE_WORK.get(), Boolean.TRUE);
    }

    @Override
    protected Map<Item, Integer> resourcesToBuy(ServerLevel level, TownspersonMob entity,
                                                Building workBuilding) {
        return CandleCrafts.resourcesToBuy(level, workBuilding);
    }

    @Override
    protected Optional<VendingIntent> vendingIntent(ServerLevel level, TownspersonMob entity,
                                                    Plan plan) {
        Building market = ProductionHelpers.findMarketInVillage(entity, level).orElse(null);
        if (market == null) return Optional.empty();
        return Optional.of(new VendingIntent(market, CandleCrafts.sellableOutputs(),
                CandleCrafts.quotas(), DEFAULT_SURPLUS_THRESHOLD,
                CandleCrafts.SELL_WINDOW_DAY_TICK));
    }

    @Override
    protected float roleSpeedMultiplier(TownspersonMob entity) {
        // Mirrors AbstractProductionBehavior.roleSpeedMultiplier: APPRENTICE
        // works slower (ticks ÷ the role's <1 productionSpeedMultiplier).
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
