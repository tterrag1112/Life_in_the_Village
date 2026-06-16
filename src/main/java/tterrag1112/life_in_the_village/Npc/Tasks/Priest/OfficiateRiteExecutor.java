package tterrag1112.life_in_the_village.Npc.Tasks.Priest;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Brain.NpcBehaviorHelpers;
import tterrag1112.life_in_the_village.Npc.Religion.RiteExecution;
import tterrag1112.life_in_the_village.Npc.Religion.RiteExecutor;
import tterrag1112.life_in_the_village.Npc.Religion.RiteOutcome;
import tterrag1112.life_in_the_village.Npc.Religion.RiteSavedData;
import tterrag1112.life_in_the_village.Npc.Tasks.Objective;
import tterrag1112.life_in_the_village.Npc.Tasks.Task;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskActor;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskContext;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskExecutor;

import java.util.UUID;

/**
 * G4 — executor for an {@code officiate_rite} task.
 *
 * <h3>Phase machine</h3>
 * <ol>
 *   <li><b>CLAIM</b> — first tick only: write {@code rite.withPresider(me)} so
 *       {@code RiteExecutor.runDue} defers (realized-priest gate) and other
 *       priests skip this rite. The task-assignment claim already prevents two
 *       priests claiming the same TASK; the presider write additionally keeps
 *       {@code runDue} from racing at the abstract-fallback path.</li>
 *   <li><b>WALK_TO_RITE</b> — navigate to the rite's location. Mirrors
 *       {@code PriestBehavior.tickWalkToRite} (ARRIVAL_SQ = 6.0, same WalkTarget
 *       logic via {@link NpcBehaviorHelpers#walkTo}).</li>
 *   <li><b>OFFICIATING</b> — timer ticks to {@code OFFICIATE_TICKS} (200) with
 *       look-at and swing gesture. Mirrors {@code PriestBehavior.tickOfficiating}.
 *       On completion: re-fetch + confirm still PENDING, then call
 *       {@code RiteExecutor.runImmediate}. If already resolved, skip silently.</li>
 *   <li><b>DONE</b> — return {@code Result.DONE}.</li>
 * </ol>
 *
 * <h3>Presider lifecycle</h3>
 * <ul>
 *   <li>Written on CLAIM (first tick).</li>
 *   <li>Cleared on FAILED-while-PENDING (pathfinding failure, rite disappeared):
 *       another priest or the abstract fallback can take it.</li>
 *   <li>NOT cleared on dusk-yield (DoTaskBehavior.stop via gracefulRelease):
 *       acceptable — the priest reclaims it next morning, or the abstract fallback
 *       fires after the grace window. This limitation is documented in PROGRESS.</li>
 * </ul>
 *
 * <h3>No rite-effect reimplementation</h3>
 * {@code RiteExecutor.runImmediate} is the sole perform call. This executor only
 * handles walk + perform; all rite handlers, piety, XP, sacred-ground refresh,
 * and history are untouched.
 */
public final class OfficiateRiteExecutor implements TaskExecutor {

    /** Mirrors PriestBehavior.OFFICIATE_TICKS. */
    private static final int    OFFICIATE_TICKS = 200;
    /** Mirrors PriestBehavior.ARRIVAL_SQ. */
    private static final double ARRIVAL_SQ      = 6.0;
    /** Mirrors PriestBehavior.WALK_SPEED. */
    private static final float  WALK_SPEED      = 1.0f;

    private enum Phase { CLAIM, WALK_TO_RITE, OFFICIATING, DONE }

    private Phase phase = Phase.CLAIM;
    private int timer;

    // Lazily resolved on CLAIM tick
    private UUID riteId;
    private BlockPos riteLoc;

    @Override
    public Result tick(Task task, TaskActor actor, TaskContext ctx) {
        ServerLevel level = ctx.level();
        TownspersonMob npc = ctx.npc().orElse(null);
        if (npc == null) return Result.FAILED;

        if (!(task.objective() instanceof Objective.PerformService ps)) return Result.FAILED;

        return switch (phase) {
            case CLAIM        -> tickClaim(level, npc, ps);
            case WALK_TO_RITE -> tickWalkToRite(level, npc);
            case OFFICIATING  -> tickOfficiating(level, npc, actor);
            case DONE         -> Result.DONE;
        };
    }

    // ── Phase: CLAIM ─────────────────────────────────────────────────────────

    private Result tickClaim(ServerLevel level, TownspersonMob npc, Objective.PerformService ps) {
        // Resolve riteId from the task ref
        riteId = ps.ref().map(s -> {
            try { return UUID.fromString(s); }
            catch (IllegalArgumentException e) { return null; }
        }).orElse(null);
        if (riteId == null) return Result.FAILED;

        RiteSavedData rdata = RiteSavedData.get(level);
        RiteExecution rite = rdata.getRite(riteId).orElse(null);
        if (rite == null || rite.outcome() != RiteOutcome.PENDING) return Result.FAILED;

        // Write presider=me so runDue defers (realized-priest gate) and other priests skip
        RiteExecution claimed = rite.withPresider(npc.getUUID());
        rdata.putRite(claimed);

        // Resolve walk target — guard BlockPos.ZERO (unset sentinel)
        if (BlockPos.ZERO.equals(rite.location())) {
            // Fall back to building origin via the task's at-position, or skip to OFFICIATING
            riteLoc = ps.at()
                    .map(gpos -> gpos.pos())
                    .orElse(null);
        } else {
            riteLoc = rite.location();
        }

        phase = Phase.WALK_TO_RITE;
        return Result.RUNNING;
    }

    // ── Phase: WALK_TO_RITE ───────────────────────────────────────────────────

    private Result tickWalkToRite(ServerLevel level, TownspersonMob npc) {
        if (riteLoc == null) {
            // No location available — go straight to officiating
            phase = Phase.OFFICIATING;
            timer = 0;
            return Result.RUNNING;
        }
        double d2 = npc.distanceToSqr(riteLoc.getX() + 0.5, riteLoc.getY(), riteLoc.getZ() + 0.5);
        if (d2 <= ARRIVAL_SQ) {
            npc.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            phase = Phase.OFFICIATING;
            timer = 0;
        } else {
            NpcBehaviorHelpers.walkTo(npc, riteLoc, WALK_SPEED);
        }
        return Result.RUNNING;
    }

    // ── Phase: OFFICIATING ────────────────────────────────────────────────────

    private Result tickOfficiating(ServerLevel level, TownspersonMob npc, TaskActor actor) {
        timer++;
        // Look-at + swing gesture, mirroring PriestBehavior.tickOfficiating
        if (riteLoc != null) {
            npc.getLookControl().setLookAt(riteLoc.getX() + 0.5, riteLoc.getY() + 1.0, riteLoc.getZ() + 0.5);
        }
        if (timer % 20 == 0) npc.swing(InteractionHand.MAIN_HAND);
        if (timer < OFFICIATE_TICKS) return Result.RUNNING;

        // Re-fetch + confirm still PENDING (guard against race with another priest
        // or the abstract grace fallback). Ports PriestBehavior.tickOfficiating:355-359.
        RiteSavedData rdata = RiteSavedData.get(level);
        RiteExecution current = rdata.getRite(riteId).orElse(null);
        if (current != null && current.outcome() == RiteOutcome.PENDING) {
            RiteExecutor.runImmediate(current, level);
        }
        // If current==null or already resolved: no double-apply, just complete.

        phase = Phase.DONE;
        npc.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        return Result.DONE;
    }

    /**
     * Called by {@code DoTaskBehavior} on a FAILED result. If the rite is still
     * PENDING and this priest is the presider, clear the presider so another
     * priest or the abstract fallback can take it.
     *
     * <p>Note: this is only invoked when the executor itself returns FAILED
     * (e.g. rite disappeared, UUID parse error, NPC null). For dusk-yield
     * (DoTaskBehavior.stop → gracefulRelease) the executor is not notified —
     * the presider field stays set, and the priest reclaims it next morning or
     * the abstract fallback handles it after the grace window. This is acceptable
     * and is documented in the PROGRESS log.</p>
     */
    public void onFailed(ServerLevel level, TownspersonMob npc) {
        if (riteId == null) return;
        RiteSavedData rdata = RiteSavedData.get(level);
        RiteExecution rite = rdata.getRite(riteId).orElse(null);
        if (rite == null) return;
        if (rite.outcome() != RiteOutcome.PENDING) return;
        UUID presider = rite.presidingPriestId().orElse(null);
        if (presider != null && presider.equals(npc.getUUID())) {
            rdata.putRite(rite.withPresider(null));
        }
    }
}
