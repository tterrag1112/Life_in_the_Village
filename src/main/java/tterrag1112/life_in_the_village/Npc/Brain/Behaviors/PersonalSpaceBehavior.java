package tterrag1112.life_in_the_village.Npc.Brain.Behaviors;

import com.google.common.collect.ImmutableMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.phys.Vec3;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Brain.BrainNavGuard;
import tterrag1112.life_in_the_village.Npc.Brain.Memories.NpcMemoryTypes;

import java.util.List;
import java.util.Optional;

/**
 * Reactive nudge — when ≥1 other TownspersonMob is within 1.5 blocks,
 * write a WALK_TARGET ~2 blocks away from the crowd's centroid.
 *
 * <p>Registered at the LOWEST nav-using priority (IDLE @ 5, SOCIAL @ 2)
 * so the deliberate behaviors (sit, internal wander, greeting) win
 * contention. Cool-down 100 ticks avoids jitter.
 *
 * <p>Coexistence: WALK_TARGET write is gated by
 * {@link BrainNavGuard#canSteerNavigation}; entry also requires
 * CONVERSATION_PARTNER absent (forward-compat for Phase 6.1.c social
 * behaviors that take ownership of the entity).
 */
public class PersonalSpaceBehavior extends Behavior<TownspersonMob> {

    private static final double TRIGGER_RADIUS_SQ = 1.5 * 1.5;
    private static final double NUDGE_DISTANCE = 2.0;
    private static final float WALK_SPEED = 0.35f;
    private static final int CLOSE_ENOUGH = 1; // memory uses int blocks; 0 means "arrive exactly"
    private static final long COOLDOWN_TICKS = 100L;
    private static final int DURATION = 40;

    public PersonalSpaceBehavior() {
        super(ImmutableMap.of(
                MemoryModuleType.NEAREST_LIVING_ENTITIES, MemoryStatus.VALUE_PRESENT,
                NpcMemoryTypes.LAST_PERSONAL_SPACE_NUDGE.get(), MemoryStatus.VALUE_ABSENT,
                NpcMemoryTypes.CONVERSATION_PARTNER.get(), MemoryStatus.VALUE_ABSENT,
                MemoryModuleType.WALK_TARGET, MemoryStatus.VALUE_ABSENT
        ), DURATION);
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, TownspersonMob entity) {
        if (!BrainNavGuard.canSteerNavigation(entity)) return false;
        return countCrowders(entity) > 0;
    }

    @Override
    protected void start(ServerLevel level, TownspersonMob entity, long gameTime) {
        Vec3 nudge = computeNudgeTarget(entity);
        if (nudge == null) return;
        BlockPos target = BlockPos.containing(nudge);
        entity.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                new WalkTarget(target, WALK_SPEED, CLOSE_ENOUGH));
        entity.getBrain().setMemoryWithExpiry(
                NpcMemoryTypes.LAST_PERSONAL_SPACE_NUDGE.get(), gameTime, COOLDOWN_TICKS);
    }

    @Override
    protected boolean canStillUse(ServerLevel level, TownspersonMob entity, long gameTime) {
        return entity.getNavigation().isInProgress();
    }

    private static int countCrowders(TownspersonMob entity) {
        Optional<List<LivingEntity>> nearby = entity.getBrain()
                .getMemory(MemoryModuleType.NEAREST_LIVING_ENTITIES);
        if (nearby.isEmpty()) return 0;
        int n = 0;
        for (LivingEntity e : nearby.get()) {
            if (e == entity) continue;
            if (!(e instanceof TownspersonMob)) continue;
            if (e.distanceToSqr(entity) <= TRIGGER_RADIUS_SQ) n++;
        }
        return n;
    }

    /** Average position of crowding NPCs → vector from there to entity
     *  → step {@link #NUDGE_DISTANCE} along it. Null if no crowders. */
    private static Vec3 computeNudgeTarget(TownspersonMob entity) {
        Optional<List<LivingEntity>> nearby = entity.getBrain()
                .getMemory(MemoryModuleType.NEAREST_LIVING_ENTITIES);
        if (nearby.isEmpty()) return null;
        double sx = 0, sy = 0, sz = 0;
        int n = 0;
        for (LivingEntity e : nearby.get()) {
            if (e == entity) continue;
            if (!(e instanceof TownspersonMob)) continue;
            if (e.distanceToSqr(entity) > TRIGGER_RADIUS_SQ) continue;
            sx += e.getX();
            sy += e.getY();
            sz += e.getZ();
            n++;
        }
        if (n == 0) return null;
        Vec3 centroid = new Vec3(sx / n, sy / n, sz / n);
        Vec3 me = entity.position();
        Vec3 away = me.subtract(centroid);
        double len = away.length();
        if (len < 1.0e-4) return null; // perfectly overlapping — punt
        Vec3 dir = away.scale(1.0 / len);
        return me.add(dir.scale(NUDGE_DISTANCE));
    }
}
