package tterrag1112.life_in_the_village.Npc.Brain.Behaviors;

import com.google.common.collect.ImmutableMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Brain.BrainNavGuard;
import tterrag1112.life_in_the_village.Npc.Brain.Memories.NpcMemoryTypes;

/**
 * Child→parent escort. When ESCORT_LEADER memory is set and the
 * leader is &gt;3 blocks away, walks to a position near the leader.
 * Low priority — never preempts shelter, conversation, sit, or
 * wander.
 */
public class FollowEscortLeaderBehavior extends Behavior<TownspersonMob> {

    private static final float WALK_SPEED = 0.5f;
    private static final int CLOSE_ENOUGH = 1;
    private static final double FOLLOW_DIST_SQ = 3.0 * 3.0;
    private static final double MAX_RANGE_SQ = 24.0 * 24.0;
    private static final int MAX_RUN = 400;

    public FollowEscortLeaderBehavior() {
        super(ImmutableMap.of(
                NpcMemoryTypes.ESCORT_LEADER.get(), MemoryStatus.VALUE_PRESENT,
                NpcMemoryTypes.CONVERSATION_PARTNER.get(), MemoryStatus.VALUE_ABSENT
        ), MAX_RUN);
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, TownspersonMob entity) {
        if (!BrainNavGuard.canSteerNavigation(entity)) return false;
        LivingEntity leader = entity.getBrain()
                .getMemory(NpcMemoryTypes.ESCORT_LEADER.get()).orElse(null);
        if (leader == null || !leader.isAlive()) return false;
        double dsq = leader.distanceToSqr(entity);
        return dsq > FOLLOW_DIST_SQ && dsq < MAX_RANGE_SQ;
    }

    @Override
    protected void start(ServerLevel level, TownspersonMob entity, long gameTime) {
        LivingEntity leader = entity.getBrain()
                .getMemory(NpcMemoryTypes.ESCORT_LEADER.get()).orElse(null);
        if (leader == null) return;
        BlockPos target = leader.blockPosition();
        entity.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                new WalkTarget(target, WALK_SPEED, CLOSE_ENOUGH));
    }

    @Override
    protected boolean canStillUse(ServerLevel level, TownspersonMob entity, long gameTime) {
        LivingEntity leader = entity.getBrain()
                .getMemory(NpcMemoryTypes.ESCORT_LEADER.get()).orElse(null);
        if (leader == null || !leader.isAlive()) return false;
        double dsq = leader.distanceToSqr(entity);
        return dsq > FOLLOW_DIST_SQ && dsq < MAX_RANGE_SQ;
    }
}
