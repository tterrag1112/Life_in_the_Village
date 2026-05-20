package tterrag1112.life_in_the_village.Npc.Brain.Behaviors;

import com.google.common.collect.ImmutableMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Brain.BrainNavGuard;
import tterrag1112.life_in_the_village.Npc.Brain.Memories.NpcMemoryTypes;

/**
 * Walks toward SHELTER_TARGET when set. Urgent — registered at the
 * top of IDLE and SOCIAL so it preempts other nav-using behaviors.
 *
 * <p>The sensor maintains SHELTER_TARGET while it rains; when the
 * sensor erases the memory (rain stopped or entity is sheltered),
 * this behavior naturally cannot start, and any in-flight walk
 * completes on its own.
 */
public class SeekShelterBehavior extends Behavior<TownspersonMob> {

    private static final float WALK_SPEED = 0.55f;
    private static final int CLOSE_ENOUGH = 1;
    private static final double ARRIVAL_DIST_SQ = 2.0 * 2.0;
    private static final int MAX_RUN = 600;

    public SeekShelterBehavior() {
        super(ImmutableMap.of(
                NpcMemoryTypes.SHELTER_TARGET.get(), MemoryStatus.VALUE_PRESENT
        ), MAX_RUN);
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, TownspersonMob entity) {
        if (!BrainNavGuard.canSteerNavigation(entity)) return false;
        BlockPos shelter = entity.getBrain()
                .getMemory(NpcMemoryTypes.SHELTER_TARGET.get()).orElse(null);
        if (shelter == null) return false;
        return entity.blockPosition().distSqr(shelter) > ARRIVAL_DIST_SQ;
    }

    @Override
    protected void start(ServerLevel level, TownspersonMob entity, long gameTime) {
        BlockPos shelter = entity.getBrain()
                .getMemory(NpcMemoryTypes.SHELTER_TARGET.get()).orElse(null);
        if (shelter == null) return;
        entity.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                new WalkTarget(shelter, WALK_SPEED, CLOSE_ENOUGH));
    }

    @Override
    protected boolean canStillUse(ServerLevel level, TownspersonMob entity, long gameTime) {
        BlockPos shelter = entity.getBrain()
                .getMemory(NpcMemoryTypes.SHELTER_TARGET.get()).orElse(null);
        if (shelter == null) return false;
        return entity.blockPosition().distSqr(shelter) > ARRIVAL_DIST_SQ;
    }
}
