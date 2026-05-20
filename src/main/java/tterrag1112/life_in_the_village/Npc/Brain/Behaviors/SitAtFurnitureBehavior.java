package tterrag1112.life_in_the_village.Npc.Brain.Behaviors;

import com.google.common.collect.ImmutableMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Brain.BrainNavGuard;
import tterrag1112.life_in_the_village.Npc.Brain.Memories.NpcMemoryTypes;
import tterrag1112.life_in_the_village.Npc.Brain.SeatOccupancyRegistry;

/**
 * Walks to NEAREST_FREE_SEAT, sits for 200-600 ticks, stands up.
 * Cool-down (1200 ticks) prevents immediate re-sit.
 *
 * <p>Coexistence: WALK_TARGET write is gated by
 * {@link BrainNavGuard#canSteerNavigation}. The {@code Pose.SITTING}
 * pose change does not fight Goals — no Goal sets pose.
 */
public class SitAtFurnitureBehavior extends Behavior<TownspersonMob> {

    private static final float WALK_SPEED = 0.5f;
    private static final int CLOSE_ENOUGH = 1;
    private static final double ARRIVAL_DIST_SQ = 1.5 * 1.5;
    private static final int SIT_DURATION_MIN = 200;
    private static final int SIT_DURATION_MAX = 600;
    private static final long COOLDOWN_TICKS = 1200L;
    private static final int MAX_RUN = 1200; // walk + sit budget

    private BlockPos seat;
    private long sitUntilTick = -1L; // -1 = not seated yet
    private boolean claimed = false;

    public SitAtFurnitureBehavior() {
        super(ImmutableMap.of(
                NpcMemoryTypes.NEAREST_FREE_SEAT.get(), MemoryStatus.VALUE_PRESENT,
                NpcMemoryTypes.SIT_COOLDOWN.get(), MemoryStatus.VALUE_ABSENT,
                MemoryModuleType.WALK_TARGET, MemoryStatus.VALUE_ABSENT
        ), MAX_RUN);
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, TownspersonMob entity) {
        if (entity.getPose() == Pose.SITTING) return false;
        if (!BrainNavGuard.canSteerNavigation(entity)) return false;
        return true;
    }

    @Override
    protected void start(ServerLevel level, TownspersonMob entity, long gameTime) {
        seat = entity.getBrain().getMemory(NpcMemoryTypes.NEAREST_FREE_SEAT.get()).orElse(null);
        if (seat == null) return;
        if (!SeatOccupancyRegistry.tryClaim(seat, entity.getUUID())) {
            seat = null;
            return;
        }
        claimed = true;
        sitUntilTick = -1L;
        entity.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                new WalkTarget(seat, WALK_SPEED, CLOSE_ENOUGH));
    }

    @Override
    protected boolean canStillUse(ServerLevel level, TownspersonMob entity, long gameTime) {
        if (seat == null) return false;
        if (sitUntilTick > 0 && gameTime >= sitUntilTick) return false;
        return true;
    }

    @Override
    protected void tick(ServerLevel level, TownspersonMob entity, long gameTime) {
        if (seat == null) return;
        if (sitUntilTick > 0) return; // already seated, wait for canStillUse

        if (entity.blockPosition().distSqr(seat) <= ARRIVAL_DIST_SQ
                && entity.getNavigation().isDone()) {
            entity.setPose(Pose.SITTING);
            entity.triggerSitDown();
            int dur = SIT_DURATION_MIN
                    + entity.getRandom().nextInt(SIT_DURATION_MAX - SIT_DURATION_MIN + 1);
            sitUntilTick = gameTime + dur;
            entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        }
    }

    @Override
    protected void stop(ServerLevel level, TownspersonMob entity, long gameTime) {
        if (entity.getPose() == Pose.SITTING) {
            entity.setPose(Pose.STANDING);
            entity.triggerStandUp();
        }
        if (claimed && seat != null) {
            SeatOccupancyRegistry.release(seat, entity.getUUID());
        }
        Brain<TownspersonMob> brain = entity.getBrain();
        brain.eraseMemory(MemoryModuleType.WALK_TARGET);
        brain.setMemoryWithExpiry(NpcMemoryTypes.SIT_COOLDOWN.get(), gameTime, COOLDOWN_TICKS);
        seat = null;
        claimed = false;
        sitUntilTick = -1L;
    }
}
