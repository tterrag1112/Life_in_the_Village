package tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production;

import com.google.common.collect.ImmutableMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.item.Item;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Brain.BrainNavGuard;
import tterrag1112.life_in_the_village.Npc.Brain.Memories.NpcMemoryTypes;
import tterrag1112.life_in_the_village.Npc.Brain.Memories.WorkPhase;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Phase 6.2.d.1 — universal WORK behavior triggered by
 * {@code CARGO_DESTINATION} memory presence. The workshop
 * {@link AbstractProductionBehavior} writes CARGO_DESTINATION +
 * WORK_PHASE=SELL when surplus accumulates; this behavior walks to
 * the market, executes the sell trip via
 * {@link AbstractProductionBehavior#executeSellForWorkshop}, erases
 * CARGO_DESTINATION + writes WORK_PHASE=IDLE so production resumes.
 *
 * <p>Dormant for NPCs whose Goal-driven work doesn't write
 * CARGO_DESTINATION (FARMER, FARMHAND, MINER still use
 * SellToMarketGoal directly until 6.2.d.2+).
 */
public class SellToMarketBehavior extends Behavior<TownspersonMob> {

    private enum Phase { WALKING, SELLING, DONE }

    private static final double ARRIVAL_DIST_SQ = 9.0;
    private static final float WALK_SPEED = 0.45f;
    private static final int CLOSE_ENOUGH = 1;
    private static final int MAX_RUN = 3600;
    /** Throttle re-trigger from the same NPC's production cycle. */
    private static final long COOLDOWN_TICKS = 600L;

    private Phase phase = Phase.WALKING;
    private BlockPos marketPos;

    public SellToMarketBehavior() {
        super(ImmutableMap.of(
                NpcMemoryTypes.CARGO_DESTINATION.get(), MemoryStatus.VALUE_PRESENT,
                MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED
        ), MAX_RUN);
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, TownspersonMob entity) {
        if (!BrainNavGuard.canSteerNavigation(entity)) return false;
        GlobalPos gp = entity.getBrain()
                .getMemory(NpcMemoryTypes.CARGO_DESTINATION.get()).orElse(null);
        if (gp == null) return false;
        marketPos = gp.pos();
        return true;
    }

    @Override
    protected boolean canStillUse(ServerLevel level, TownspersonMob entity, long gameTime) {
        return phase != Phase.DONE
                && entity.getBrain().hasMemoryValue(NpcMemoryTypes.CARGO_DESTINATION.get());
    }

    @Override
    protected void start(ServerLevel level, TownspersonMob entity, long gameTime) {
        phase = Phase.WALKING;
        entity.setCurrentActivity("Selling goods");
        if (marketPos != null) {
            entity.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                    new WalkTarget(marketPos, WALK_SPEED, CLOSE_ENOUGH));
        }
    }

    @Override
    protected void tick(ServerLevel level, TownspersonMob entity, long gameTime) {
        if (marketPos == null) { phase = Phase.DONE; return; }
        switch (phase) {
            case WALKING -> {
                double distSq = entity.distanceToSqr(
                        marketPos.getX(), entity.getY(), marketPos.getZ());
                if (distSq <= ARRIVAL_DIST_SQ) {
                    entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
                    phase = Phase.SELLING;
                } else {
                    entity.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                            new WalkTarget(marketPos, WALK_SPEED, CLOSE_ENOUGH));
                }
            }
            case SELLING -> {
                executeSell(level, entity);
                phase = Phase.DONE;
            }
            case DONE -> {}
        }
    }

    @Override
    protected void stop(ServerLevel level, TownspersonMob entity, long gameTime) {
        entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        entity.getBrain().eraseMemory(NpcMemoryTypes.CARGO_DESTINATION.get());
        entity.getBrain().setMemory(NpcMemoryTypes.WORK_PHASE.get(), WorkPhase.IDLE);
        entity.getBrain().setMemoryWithExpiry(
                NpcMemoryTypes.LAST_SELL_TICK.get(), gameTime, COOLDOWN_TICKS);
        entity.clearCurrentActivity();
        phase = Phase.DONE;
        marketPos = null;
    }

    /** Resolves the workshop building + market and runs the static sell helper. */
    private void executeSell(ServerLevel level, TownspersonMob entity) {
        VillageSavedData data = VillageSavedData.get(level);
        Building workBuilding = entity.getAssignedBuildingId()
                .flatMap(data::getBuildingById).orElse(null);
        if (workBuilding == null) return;
        // Resolve the market from the saved CARGO_DESTINATION (closest building
        // containing it). MARKET / WORKSHOP_MARKET would also be acceptable here;
        // we keep the lookup permissive.
        Building market = findBuildingAt(data, marketPos);
        if (market == null) return;

        Map<Item, Integer> toSell = computeSurplus(level, entity, workBuilding);
        AbstractProductionBehavior.executeSellForWorkshop(
                level, entity, workBuilding, market, toSell);
    }

    private static Building findBuildingAt(VillageSavedData data, BlockPos pos) {
        for (Building b : data.getAllBuildings()) {
            if (b.getType() == BuildingType.MARKET
                    && b.getShape().toAABB().inflate(1).contains(
                            pos.getX(), pos.getY(), pos.getZ())) {
                return b;
            }
        }
        return null;
    }

    /** Mirrors {@code AbstractProductionBehavior.computeSurplusToSell} but
     *  scoped to whatever's actually piled up at the workshop right now —
     *  we don't have access to the specific profession's quotas, so we
     *  sell everything beyond a fixed safety stock. */
    private static Map<Item, Integer> computeSurplus(ServerLevel level,
                                                     TownspersonMob entity,
                                                     Building workBuilding) {
        // Lightweight stand-in — the production behavior already wrote
        // CARGO_DESTINATION precisely because it detected surplus, so we
        // can trust that surplus exists. We let the static sell helper
        // re-walk the inventory; here we pass an empty map plus a sentinel.
        // Simpler: pull every item over a small floor (8) for sale.
        Map<Item, Integer> result = new LinkedHashMap<>();
        for (var container : tterrag1112.life_in_the_village.Village
                .BuildingStorageAccess.findInventories(level, workBuilding)) {
            for (int i = 0; i < container.getContainerSize(); i++) {
                var stack = container.getItem(i);
                if (stack.isEmpty()) continue;
                if (stack.getCount() > 8) {
                    result.merge(stack.getItem(), stack.getCount() - 8, Integer::sum);
                }
            }
        }
        return result;
    }

}
