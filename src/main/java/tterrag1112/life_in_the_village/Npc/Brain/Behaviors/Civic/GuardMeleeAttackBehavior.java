package tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Civic;

import com.google.common.collect.ImmutableMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;

/**
 * Phase 6.2.e — port of vanilla {@code MeleeAttackGoal}. Consumes
 * ATTACK_TARGET memory: walks to the target via WALK_TARGET memory
 * (CORE's MoveToTargetSink does the actual nav) and swings on
 * cooldown when within reach. Replaces both the vanilla
 * {@code MeleeAttackGoal} and the {@code SetWalkTargetFromAttack
 * TargetIfTargetOutOfReach} pattern in one minimal behavior.
 *
 * <p>Used in FIGHT activity for GUARD. ATTACK_COOLING_DOWN memory
 * (Unit) is set after each swing to gate the next.
 */
public class GuardMeleeAttackBehavior extends Behavior<TownspersonMob> {

    private static final float WALK_SPEED = 1.2f;
    private static final int CLOSE_ENOUGH = 1;
    private static final double REACH_SQ = 4.0; // 2-block reach squared
    private static final long COOLDOWN_TICKS = 20L;
    private static final int MAX_RUN = Integer.MAX_VALUE;

    public GuardMeleeAttackBehavior() {
        super(ImmutableMap.of(
                MemoryModuleType.ATTACK_TARGET, MemoryStatus.VALUE_PRESENT,
                MemoryModuleType.ATTACK_COOLING_DOWN, MemoryStatus.VALUE_ABSENT,
                MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED
        ), MAX_RUN);
    }

    @Override
    protected boolean canStillUse(ServerLevel level, TownspersonMob entity, long gameTime) {
        LivingEntity target = entity.getBrain()
                .getMemory(MemoryModuleType.ATTACK_TARGET).orElse(null);
        return target != null && target.isAlive();
    }

    @Override
    protected void tick(ServerLevel level, TownspersonMob entity, long gameTime) {
        Brain<TownspersonMob> brain = entity.getBrain();
        LivingEntity target = brain.getMemory(MemoryModuleType.ATTACK_TARGET).orElse(null);
        if (target == null || !target.isAlive()) return;

        double distSq = entity.distanceToSqr(target);
        if (distSq > REACH_SQ) {
            // Chase: refresh WALK_TARGET each tick so MoveToTargetSink stays
            // current with the moving target.
            brain.setMemory(MemoryModuleType.WALK_TARGET,
                    new WalkTarget(target.blockPosition(), WALK_SPEED, CLOSE_ENOUGH));
            return;
        }

        // In reach — strike.
        brain.eraseMemory(MemoryModuleType.WALK_TARGET);
        entity.swing(InteractionHand.MAIN_HAND);
        entity.doHurtTarget(level, target);
        brain.setMemoryWithExpiry(
                MemoryModuleType.ATTACK_COOLING_DOWN,
                net.minecraft.util.Unit.INSTANCE, COOLDOWN_TICKS);
    }
}
