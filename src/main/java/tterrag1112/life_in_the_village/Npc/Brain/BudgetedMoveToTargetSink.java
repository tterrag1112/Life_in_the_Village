package tterrag1112.life_in_the_village.Npc.Brain;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.MoveToTargetSink;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * D1-minimal (city perf) — drop-in replacement for the vanilla CORE
 * {@link MoveToTargetSink} that adds two brakes in front of the path
 * computation, leaving everything else (start/tick/stop, PATH memory,
 * CANT_REACH bookkeeping) to vanilla:
 *
 * <ol>
 *   <li><b>Global budget.</b> {@link PathComputeBudget} caps NEW path
 *       computations per server tick. When the budget is exhausted this
 *       sink declines to start — WITHOUT erasing WALK_TARGET (vanilla's
 *       checkExtraStartConditions erases it on failure, which would turn
 *       "defer a tick" into "cancel the walk"). The Brain retries the
 *       behavior on the next executed brain tick, so the request simply
 *       starts a few ticks later.</li>
 *   <li><b>Leg clamp.</b> FOLLOW_RANGE is 32, so the pathfinder's search
 *       region and node cap (~512 visited nodes) are tuned for ≤32-block
 *       trips; a cross-city WALK_TARGET is outside the region, can never
 *       be reached, and burns the full node cap on every compute. Targets
 *       beyond {@link #CLAMP_TRIGGER} are rewritten to an intermediate
 *       waypoint {@link #LEG_LENGTH} blocks toward the destination
 *       (heightmap-snapped) — a cheap, reachable leg. Behaviors already
 *       re-issue WALK_TARGET while not at their destination (the standing
 *       "write WALK_TARGET and let CORE MoveToTargetSink steer" idiom +
 *       BrainNavGuard serializing steering per NPC), so the NPC hops
 *       leg-by-leg to the true target. This is the crude precursor of
 *       roadmap D5 sim-nudge pathing.</li>
 * </ol>
 *
 * <p>Why here: MoveToTargetSink is the single consumer of WALK_TARGET, and
 * ~90 behavior sites write that memory directly (only a handful route
 * through {@code NpcBehaviorHelpers.walkTo}). Gating the sink covers every
 * Brain-driven walk with one seam. Caravan / pilgrim / road-traveller
 * behaviors are unaffected beyond extra waypoint hops: they re-write
 * WALK_TARGET every behavior tick toward road-graph points, so a clamped
 * leg is transparently re-issued.</p>
 */
public class BudgetedMoveToTargetSink extends MoveToTargetSink {

    /** Targets farther than this (blocks) get leg-clamped. Matches
     *  FOLLOW_RANGE (32) — beyond it the pathfinder can't reach anyway. */
    private static final int CLAMP_TRIGGER = 32;
    /** Clamped leg length (blocks) — comfortably inside the pathfinder's
     *  follow-range region so legs resolve as cheap reachable paths. */
    private static final int LEG_LENGTH = 24;
    /** Waypoints are transit points, not destinations — a loose arrival
     *  radius hands off to the next leg without corner-hunting. */
    private static final int WAYPOINT_CLOSE_ENOUGH = 2;

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, Mob mob) {
        Brain<?> brain = mob.getBrain();
        var maybeTarget = brain.getMemory(MemoryModuleType.WALK_TARGET);
        if (maybeTarget.isEmpty()) return false; // defensive; declared VALUE_PRESENT
        if (!PathComputeBudget.tryAcquire(level)) {
            // Defer: keep WALK_TARGET intact so the walk starts on a later
            // brain tick instead of being cancelled.
            return false;
        }
        clampLeg(level, mob, brain, maybeTarget.get());
        return super.checkExtraStartConditions(level, mob);
    }

    /** Rewrites WALK_TARGET to an intermediate waypoint when the current
     *  target is beyond {@link #CLAMP_TRIGGER}. No-op for near targets. */
    private static void clampLeg(ServerLevel level, Mob mob, Brain<?> brain,
                                 WalkTarget target) {
        BlockPos dest = target.getTarget().currentBlockPosition();
        double distSqr = dest.distSqr(mob.blockPosition());
        if (distSqr <= (double) CLAMP_TRIGGER * CLAMP_TRIGGER) return;
        Vec3 from = mob.position();
        Vec3 dir = Vec3.atBottomCenterOf(dest).subtract(from);
        if (dir.lengthSqr() < 1.0e-4) return;
        Vec3 wp = from.add(dir.normalize().scale(LEG_LENGTH));
        int x = Mth.floor(wp.x);
        int z = Mth.floor(wp.z);
        // Heightmap snap keeps the waypoint at walkable elevation; the
        // ground navigator additionally adjusts into/onto solid ground.
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        brain.setMemory(MemoryModuleType.WALK_TARGET,
                new WalkTarget(new BlockPos(x, y, z),
                        target.getSpeedModifier(), WAYPOINT_CLOSE_ENOUGH));
    }
}
