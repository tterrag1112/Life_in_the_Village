package tterrag1112.life_in_the_village.Npc.Brain;

import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
 * <p><b>Phase 6.1.b update — structural exclusion lifted.</b>
 * WALK_TARGET is now part of the brain memory list and
 * {@code MoveToTargetSink} consumes it from CORE. Every WALK_TARGET
 * writer MUST call {@link #canSteerNavigation} first; there is no
 * passive guarantee anymore. Same rule applies to LOOK_TARGET writers
 * and {@link #canRotateHead}.
 *
 * <p>This is a best-effort check, not a hard lock. A Goal may claim
 * the channel on the very next tick after the guard returns true; the
 * resulting visual is a brief snap, not a crash. The point is to
 * prevent the common case (Brain stomping a Goal that's actively
 * driving the entity in the same tick).
 */
public final class BrainNavGuard {

    /**
     * Phase 6.3.4.5.2 — stale-nav escape hatch.
     *
     * <p>Vanilla {@code PathNavigation.isInProgress()} stays {@code true}
     * when a path is active but the entity has stopped advancing
     * (target unreachable, blocked by a colliding entity, path
     * succeeded but the navigation state wasn't cleared, etc.). This
     * left production behaviors permanently denied because the guard
     * had no way to detect "in progress but stuck."</p>
     *
     * <p>We track the first tick at which each entity was observed
     * with {@code isInProgress=true}; when that interval exceeds
     * {@link #STALE_NAV_TICKS} the guard force-stops navigation and
     * allows steering. Cleared when navigation goes idle on its own.</p>
     */
    private static final ConcurrentHashMap<UUID, Long> NAV_BUSY_SINCE =
            new ConcurrentHashMap<>();
    private static final long STALE_NAV_TICKS = 200L;

    private BrainNavGuard() {}

    /**
     * True if no Goal is currently steering navigation, so the Brain
     * may safely set WALK_TARGET in a navigation-triggering way.
     * Phase 6.1.a has no behavior that needs this, but the helper
     * ships now so 6.1.b's navigating behaviors land on stable ground.
     */
    public static boolean canSteerNavigation(TownspersonMob entity) {
        var nav = entity.getNavigation();
        if (nav.isInProgress()) {
            long now = entity.level().getGameTime();
            UUID id = entity.getUUID();
            Long since = NAV_BUSY_SINCE.putIfAbsent(id, now);
            long started = since == null ? now : since;
            if (now - started >= STALE_NAV_TICKS) {
                nav.stop();
                NAV_BUSY_SINCE.remove(id);
                return !hasRunningGoalWithFlag(entity, Goal.Flag.MOVE);
            }
            return false;
        }
        NAV_BUSY_SINCE.remove(entity.getUUID());
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

    /**
     * Phase 6.3.4.4.1 — diagnostic accessor returning a human-readable
     * description of why {@link #canSteerNavigation} would currently
     * return false, or {@code "available"} when navigation is free.
     *
     * <p>The pre-6.3.4.4 deny message at gated behaviors named only the
     * category ("combat / sit / lock / other steering claim") — not
     * actionable. This method surfaces the concrete claimant so the
     * call site can log it.</p>
     */
    public static String describeNavClaim(TownspersonMob entity) {
        if (entity.getNavigation().isInProgress()) {
            var target = entity.getNavigation().getTargetPos();
            return "navigation in progress"
                    + (target != null ? " (target=" + target + ")" : "");
        }
        for (WrappedGoal w : entity.goalSelector.getAvailableGoals()) {
            if (!w.isRunning()) continue;
            if (w.getGoal().getFlags().contains(Goal.Flag.MOVE)) {
                return "running Goal holds MOVE flag: "
                        + w.getGoal().getClass().getSimpleName();
            }
        }
        return "available";
    }
}
