package tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import tterrag1112.life_in_the_village.Entities.ActivityState;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.ProfessionRoleManager;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.Workshop.WorkshopRole;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.Workshop.WorkshopRoleAssigner;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Brain.Behaviors.GreetPlayerBehavior;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskMigration;
import tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Homestead.ContextProductionBehavior;
import tterrag1112.life_in_the_village.Npc.Brain.Memories.NpcMemoryTypes;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;
import tterrag1112.life_in_the_village.Village.AmenityType;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Economy.Resources.ProductionHelpers;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * E-S3 — STONEMASON workshop as a <i>context</i> over the shared
 * {@link ContextProductionBehavior} primitive. Replaces the former
 * {@code AbstractProductionBehavior}-based Stonemason (convert-then-delete).
 *
 * <p>Pattern follows the Candlemaker E-S2 pilot. Craft table lives in
 * {@link StonemasonCrafts}; workstation is a {@link AmenityType#STONECUTTER}
 * scanned inside the stonemason building.</p>
 *
 * <h3>Parity with the former class</h3>
 * <ul>
 *   <li><b>Crafts</b> — all MASONRY-skill recipes + quotas in
 *       {@link StonemasonCrafts}; {@link StonemasonCrafts#chooseCraft} reproduces
 *       the lowest-stock-ratio target selection + skill gate + input check.</li>
 *   <li><b>Workstation</b> — stonecutter block, resolved via
 *       {@link AmenityType#STONECUTTER}.</li>
 *   <li><b>Batch + multipliers</b> — plan opts into batch sizing (cap 8) and
 *       multiplier/quality-bonus scaling.</li>
 *   <li><b>Buy</b> — stone-type basket via {@link #resourcesToBuy}.</li>
 *   <li><b>Sell, ledger, gates, XP (MASONRY, 3/cycle)</b> — identical to
 *       the Candlemaker pilot structure.</li>
 * </ul>
 */
public class StonemasonProductionBehavior extends ContextProductionBehavior {

    private static final ActivityState BLOCKED_ROLE        = ActivityState.IDLE.withBlocking("workshop role gate");
    private static final ActivityState BLOCKED_OFF_WORK    = ActivityState.IDLE.withBlocking("off work hours");
    private static final ActivityState BLOCKED_WORK_BLOCK  = ActivityState.IDLE.withBlocking("injured / asleep / overridden");
    private static final ActivityState BLOCKED_NO_BUILDING = ActivityState.IDLE.withBlocking("no assigned work building");

    private static final long ROLE_CHECK_INTERVAL = 24000L;
    private long lastRoleCheckTick = -ROLE_CHECK_INTERVAL;

    private Building workBuilding;

    @Override
    protected boolean checkContextGate(ServerLevel level, TownspersonMob entity) {
        // T2 — when the Task System owns this profession (flag on + migrated),
        // yield so the brain falls through from WORK@0 to DoTaskBehavior at WORK@1.
        if (TaskMigration.ownsWork(entity.getProfession())) return false;
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
                .findAssignedBuilding(entity, level, BuildingType.STONEMASON)
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
        StonemasonCrafts.StonemasonCraft craft = StonemasonCrafts.chooseCraft(level, workBuilding, entity);
        if (craft == null) return Optional.empty();
        int batch = StonemasonCrafts.batchSize(level, workBuilding, craft.recipe());
        if (batch <= 0) return Optional.empty();

        net.minecraft.core.BlockPos cutter = firstAmenityPos(level, workBuilding,
                List.of(AmenityType.STONECUTTER));

        entity.getBrain().eraseMemory(NpcMemoryTypes.NO_ACTIONABLE_WORK.get());

        return Optional.of(new Plan(workBuilding, cutter,
                craft.recipe(), Skill.MASONRY, craft.xpPerBatch(),
                craft.activityLabel(),
                batch, /*applyMultipliers*/ true, /*recordLedger*/ true));
    }

    @Override
    protected void onNoPlan(ServerLevel level, TownspersonMob entity) {
        if (workBuilding != null) {
            Map<Item, Integer> toBuy = resourcesToBuy(level, entity, workBuilding);
            procure(level, entity, workBuilding, toBuy);
        }
        entity.setActivityState(ActivityState.IDLE.withBlocking("no viable mason craft"));
        entity.getBrain().setMemory(NpcMemoryTypes.NO_ACTIONABLE_WORK.get(), Boolean.TRUE);
    }

    @Override
    protected Map<Item, Integer> resourcesToBuy(ServerLevel level, TownspersonMob entity,
                                                Building workBuilding) {
        return StonemasonCrafts.resourcesToBuy(level, workBuilding);
    }

    @Override
    protected Optional<VendingIntent> vendingIntent(ServerLevel level, TownspersonMob entity,
                                                    Plan plan) {
        Building market = ProductionHelpers.findMarketInVillage(entity, level).orElse(null);
        if (market == null) return Optional.empty();
        return Optional.of(new VendingIntent(market, StonemasonCrafts.sellableOutputs(),
                StonemasonCrafts.quotas(), DEFAULT_SURPLUS_THRESHOLD,
                StonemasonCrafts.SELL_WINDOW_DAY_TICK));
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
