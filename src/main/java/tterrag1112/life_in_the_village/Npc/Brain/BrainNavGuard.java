package tterrag1112.life_in_the_village.Npc.Brain;

import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;

/**
 * Coexistence guard for Brain behaviors that share the entity with the
 * vanilla GoalSelector during Phase 6.0+. Brain behaviors that would
 * steer any of {navigation, attack target, head rotation} MUST guard
 * with the corresponding method below; violations are coexistence-rule
 * failures and will fight with Goals.
 *
 * <p>Why these three: those are the three channels through which Goals
 * influence the entity. Memory writes, animation states, and transient
 * fields are all freely writable from Brain behaviors — they don't
 * compete with Goals.
 *
 * <p>This is a best-effort check, not a hard lock. A Goal may claim
 * head rotation on the very next tick after the guard returns true;
 * the resulting visual is a brief snap, not a crash. The point is to
 * prevent the common case (Brain stomping a Goal that's actively
 * driving the entity in the same tick).
 */
public final class BrainNavGuard {

    private BrainNavGuard() {}

    /**
     * True if no Goal is currently steering navigation, so the Brain
     * may safely set WALK_TARGET in a navigation-triggering way.
     * Phase 6.1.a has no behavior that needs this, but the helper
     * ships now so 6.1.b's navigating behaviors land on stable ground.
     */
    public static boolean canSteerNavigation(TownspersonMob entity) {
        if (entity.getNavigation().isInProgress()) return false;
        return !hasRunningGoalWithFlag(entity, Goal.Flag.MOVE);
    }

    /**
     * True if the entity has no current attack target and no Goal in
     * the targetSelector is actively engaged. Phase 6.1.a has no
     * combat-routing behavior; this ships so Phase 6.1.c+ has the
     * guard ready.
     */
    public static boolean canSetAttackTarget(TownspersonMob entity) {
        if (entity.getTarget() != null) return false;
        for (WrappedGoal w : entity.targetSelector.getAvailableGoals()) {
            if (w.isRunning()) return false;
        }
        return true;
    }

    /**
     * True if no running goal claims {@link Goal.Flag#LOOK}. A vanilla
     * {@code LookAtPlayerGoal} actively looking at a nearby player
     * wins; the Brain steps aside. Used by
     * {@code GreetingAcknowledgmentBehavior} to gate its LOOK_TARGET
     * write.
     */
    public static boolean canRotateHead(TownspersonMob entity) {
        return !hasRunningGoalWithFlag(entity, Goal.Flag.LOOK);
    }

    private static boolean hasRunningGoalWithFlag(TownspersonMob entity, Goal.Flag flag) {
        for (WrappedGoal w : entity.goalSelector.getAvailableGoals()) {
            if (!w.isRunning()) continue;
            if (w.getGoal().getFlags().contains(flag)) return true;
        }
        return false;
    }
}
