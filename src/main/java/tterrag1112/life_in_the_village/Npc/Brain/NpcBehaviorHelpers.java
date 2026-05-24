package tterrag1112.life_in_the_village.Npc.Brain;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.item.ItemStack;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;

/**
 * Phase 6.3.3.s.4 — canonical entry point for NPC behavior operations
 * that have historically been scattered across direct-utility calls.
 *
 * <p>Behaviors (Brain {@code BehaviorControl} implementations, vanilla
 * {@code Goal}s, and Homestead handlers) should route ALL of:</p>
 * <ul>
 *   <li>Storage deposits / withdrawals</li>
 *   <li>Walking to a destination</li>
 *   <li>Tool use + skill XP awards (these already had canonical
 *       helpers — {@code ToolUseSupport}, {@code SkillXp.award} —
 *       and the audit confirmed 100% adoption; this class doesn't
 *       wrap them again, just points at them in the javadoc.)</li>
 *   <li>Wealth / payments (delegated to {@code NpcEconomy})</li>
 *   <li>Market interactions (delegated to {@code VillageEconomy})</li>
 * </ul>
 *
 * <p>through this facade so the canonical helper layer is the single
 * source of truth for "how NPCs do common things". Direct calls to
 * the underlying utilities (e.g., {@code npc.getPersonalInventory()
 * .addItem}, {@code mob.getNavigation().moveTo}) bypass the facade
 * and are flagged in the {@code litv-npc-behavior} skill as
 * non-compliant.</p>
 *
 * <h3>Why facade now and not earlier</h3>
 * <p>Behavior code accumulated organically across phases 6.0 → 6.3.3.r;
 * each phase had a focused utility (BuildingStorageAccess from j.2,
 * ToolUseSupport from h.5, SkillXp from g.x, etc.). The audit
 * inventory in 6.3.3.s surfaced 15+ sites still using direct calls
 * that ought to go through a uniform helper layer — this class is
 * that layer.</p>
 *
 * <p>Existing utility classes stay as-is and remain THE
 * implementation. This facade adds no new behavior; it makes the
 * intended call shape explicit and lets a single skill-file
 * convention point at one place.</p>
 */
public final class NpcBehaviorHelpers {

    /** When a caller doesn't supply a building, this signals
     *  "skip building storage entirely; go straight to fallback". */
    public static final Building NO_BUILDING = null;

    private NpcBehaviorHelpers() {}

    // ── Navigation ───────────────────────────────────────────────────────────

    /**
     * Phase 6.3.3.s.3 — canonical "walk to position" helper. Does
     * three things atomically so all behavior walk sites compose
     * the same way:
     *
     * <ol>
     *   <li>Writes {@code WALK_TARGET} memory with the block-centered
     *       destination (the Brain idiom; {@code MoveToTargetSink} in
     *       CORE consumes this).</li>
     *   <li>Calls {@code entity.getNavigation().moveTo(x+0.5, y, z+0.5,
     *       speed)} as backstop and to force path computation this
     *       tick (the r-commit fix; navigation centers on block
     *       midpoint per vanilla idiom).</li>
     *   <li>Returns {@code true} when {@code moveTo} accepted the
     *       request (path computed), {@code false} otherwise — the
     *       caller can log / fall back / re-route if unreachable.</li>
     * </ol>
     *
     * <p>Pre-s.3, walk sites in FarmerBehavior called
     * {@code setMemory(WALK_TARGET, navWalkTarget(...))} alone, which
     * relied on {@code MoveToTargetSink} picking up the memory on
     * the next CORE tick. Per the r-trace, race conditions or memory
     * pruning could leave the NPC stuck. The direct-moveTo backstop
     * lifted out of harvest() here applies universally to every walk
     * site that adopts {@code walkTo}.</p>
     *
     * @param entity the NPC; nav and Brain are pulled from this.
     * @param pos    destination block (NW corner; +0.5 centering
     *               applied internally for the nav layer).
     * @param speed  vanilla speed-modifier multiplier.
     * @return       true when navigation accepted the destination.
     */
    public static boolean walkTo(TownspersonMob entity, BlockPos pos, double speed) {
        if (entity == null || pos == null) return false;
        entity.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                new WalkTarget(pos, (float) speed, 1));
        return entity.getNavigation().moveTo(
                pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, speed);
    }

    /** Convenience: walk to (x, y, z) coords. Treats the coords as
     *  a block position (truncates fractional components). */
    public static boolean walkTo(TownspersonMob entity,
                                 double x, double y, double z, double speed) {
        return walkTo(entity, BlockPos.containing(x, y, z), speed);
    }

    // ── Storage ──────────────────────────────────────────────────────────────

    /**
     * Phase 6.3.3.s.4 — canonical deposit. Tries building storage
     * first; falls back to the NPC's personal inventory. Wraps
     * {@link BuildingStorageAccess#storeWithFallback} so behaviors
     * don't have to import that class directly.
     *
     * @param level     server level.
     * @param entity    depositing NPC (personal inventory used as fallback).
     * @param building  target building; {@code null} routes straight
     *                  to personal inventory.
     * @param stack     stack to deposit; mutated to track remainder
     *                  per {@link BuildingStorageAccess#storeItem}.
     * @return          true when the stack fully landed somewhere.
     */
    public static boolean depositToBuilding(ServerLevel level,
                                            TownspersonMob entity,
                                            Building building,
                                            ItemStack stack) {
        if (entity == null) return false;
        return BuildingStorageAccess.storeWithFallback(
                level, building, stack, entity.getPersonalInventory());
    }
}
