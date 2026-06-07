package tterrag1112.life_in_the_village.Npc.Brain.Behaviors;

import com.google.common.collect.ImmutableMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.phys.Vec3;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Brain.BrainNavGuard;
import tterrag1112.life_in_the_village.Npc.Brain.Gesture;
import tterrag1112.life_in_the_village.Npc.Brain.Memories.NpcMemoryTypes;
import tterrag1112.life_in_the_village.Npc.Brain.NpcBehaviorHelpers;
import tterrag1112.life_in_the_village.Village.Building;

import java.util.Optional;

/**
 * Liveliness L1 — the <b>single idle director</b>. The gap this fills: when
 * a profession behavior can't run, brain selection returns nothing and the
 * NPC stands still (there was no fallback in WORK/REST/IDLE and no general
 * "go somewhere, do something" behavior). One class, registered low-priority
 * in WORK, REST and IDLE, picks a cheap ambient action when the NPC has no
 * primary task and walks/animates it.
 *
 * <h3>Gating &amp; coexistence (must not fight real work)</h3>
 * <ul>
 *   <li>Requires {@code WALK_TARGET} ABSENT — never starts while anything
 *       (incl. a production cycle) is navigating, so it can't clobber a
 *       working behavior's path.</li>
 *   <li>Requires {@code IDLE_DIRECTOR_COOLDOWN} ABSENT — a per-pick cooldown
 *       so it picks-then-walks, never thrashes.</li>
 *   <li>WORK instance additionally requires {@code NO_ACTIONABLE_WORK}
 *       PRESENT — the work-satisfied signal set by
 *       {@code AbstractProductionBehavior.goIdle} on a structural idle. So
 *       during WORK it acts only when there is genuinely no task, and yields
 *       the moment production becomes runnable again (signal cleared).</li>
 *   <li>{@link BrainNavGuard#canSteerNavigation} in
 *       {@code checkExtraStartConditions} — respects Goal/homestead nav
 *       claims and active navigation.</li>
 * </ul>
 * Because {@code canSteerNavigation} is false while this behavior's own walk
 * is in progress, production can't start (and clobber) mid-stroll; when the
 * stroll ends, production reclaims the slot. Clean alternation, no flicker.
 */
public class IdleDirectorBehavior extends Behavior<TownspersonMob> {

    private static final float STROLL_SPEED = 0.45f;
    private static final float REST_SPEED   = 0.40f;
    private static final int   STROLL_RADIUS = 16;
    private static final int   STROLL_VERT   = 8;
    private static final int   REST_RADIUS   = 6;
    private static final int   REST_VERT     = 4;
    private static final int   FOOTPRINT_SAMPLES = 6;
    private static final int   COOLDOWN_MIN  = 100;
    private static final int   COOLDOWN_MAX  = 300;
    private static final int   MAX_RUN       = 600;

    /** WORK instances gate on the work-satisfied signal; REST/IDLE don't. */
    private final boolean requireWorkSatisfied;

    public IdleDirectorBehavior(boolean requireWorkSatisfied) {
        super(buildRequirements(requireWorkSatisfied), MAX_RUN);
        this.requireWorkSatisfied = requireWorkSatisfied;
    }

    private static ImmutableMap<MemoryModuleType<?>, MemoryStatus> buildRequirements(
            boolean workMode) {
        if (workMode) {
            return ImmutableMap.of(
                    MemoryModuleType.WALK_TARGET, MemoryStatus.VALUE_ABSENT,
                    NpcMemoryTypes.IDLE_DIRECTOR_COOLDOWN.get(), MemoryStatus.VALUE_ABSENT,
                    NpcMemoryTypes.NO_ACTIONABLE_WORK.get(), MemoryStatus.VALUE_PRESENT);
        }
        return ImmutableMap.of(
                MemoryModuleType.WALK_TARGET, MemoryStatus.VALUE_ABSENT,
                NpcMemoryTypes.IDLE_DIRECTOR_COOLDOWN.get(), MemoryStatus.VALUE_ABSENT);
    }

    private enum DirectorAction { STROLL, TIDY, REST }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, TownspersonMob entity) {
        // Don't pull a sleeping NPC out of bed (ReturnHome holds no WALK_TARGET
        // while sleeping, so the memory gate alone wouldn't stop us).
        if (entity.isSleeping()) return false;
        // Memory requirements (above) are checked by the superclass; here we
        // only assert nav is ours to steer.
        return BrainNavGuard.canSteerNavigation(entity);
    }

    @Override
    protected void start(ServerLevel level, TownspersonMob entity, long gameTime) {
        DirectorAction action = pickAction(entity);
        boolean acted = switch (action) {
            case STROLL -> doStroll(entity);
            case TIDY   -> doTidy(level, entity);
            case REST   -> doRest(entity);
        };
        // If the chosen action had no destination (e.g. no building for TIDY),
        // fall back to a plain stroll so the NPC still does *something*.
        if (!acted) doStroll(entity);
        armCooldown(entity);
    }

    @Override
    protected boolean canStillUse(ServerLevel level, TownspersonMob entity, long gameTime) {
        return entity.getNavigation().isInProgress();
    }

    @Override
    protected void stop(ServerLevel level, TownspersonMob entity, long gameTime) {
        // Drop the cosmetic carry overlay when the action ends.
        entity.getBrain().eraseMemory(NpcMemoryTypes.CARRYING_DISPLAY_ITEM.get());
    }

    private DirectorAction pickAction(TownspersonMob entity) {
        RandomSource rng = entity.getRandom();
        if (requireWorkSatisfied) {
            // At the workshop with no task: mostly putter, sometimes stroll out.
            return rng.nextInt(10) < 7 ? DirectorAction.TIDY : DirectorAction.STROLL;
        }
        // Off-duty / resting: mostly stroll, sometimes linger and look around.
        return rng.nextInt(10) < 7 ? DirectorAction.STROLL : DirectorAction.REST;
    }

    private boolean doStroll(TownspersonMob entity) {
        Vec3 dest = DefaultRandomPos.getPos(entity, STROLL_RADIUS, STROLL_VERT);
        if (dest == null) return false;
        NpcBehaviorHelpers.walkTo(entity, BlockPos.containing(dest), STROLL_SPEED);
        entity.setCurrentActivity("Strolling the village");
        // L3 part B — carry a cosmetic prop while strolling (cleared on stop).
        AmbientProps.applyDisplay(entity);
        return true;
    }

    private boolean doRest(TownspersonMob entity) {
        entity.getBrain().eraseMemory(NpcMemoryTypes.CARRYING_DISPLAY_ITEM.get());
        Vec3 dest = DefaultRandomPos.getPos(entity, REST_RADIUS, REST_VERT);
        if (dest == null) return false;
        NpcBehaviorHelpers.walkTo(entity, BlockPos.containing(dest), REST_SPEED);
        entity.setCurrentActivity("Taking a breather");
        entity.triggerGesture(Gesture.LOOK_AROUND);
        return true;
    }

    private boolean doTidy(ServerLevel level, TownspersonMob entity) {
        Optional<Building.BuildingShape> shapeOpt = entity.getAssignedBuildingId()
                .flatMap(id -> VillageSavedData.get(level).getBuildingById(id))
                .map(Building::getShape);
        if (shapeOpt.isEmpty()) return false;
        BlockPos pick = sampleWalkable(level, shapeOpt.get(), entity.getRandom());
        if (pick == null) return false;
        NpcBehaviorHelpers.walkTo(entity, pick, STROLL_SPEED);
        entity.setCurrentActivity("Tidying the workshop");
        // Cosmetic carry overlay (held item / profession prop). CORE
        // CarryHoldAnimationBehavior renders it; cleared on stop.
        AmbientProps.applyDisplay(entity);
        return true;
    }

    /** Cheap footprint walkable sampler (mirrors InternalBuildingWanderBehavior). */
    private static BlockPos sampleWalkable(ServerLevel level,
                                           Building.BuildingShape shape,
                                           RandomSource rng) {
        BlockPos min = shape.getMin();
        int width = Math.max(1, shape.getWidth());
        int length = Math.max(1, shape.getLength());
        int height = Math.max(1, shape.getHeight());
        for (int i = 0; i < FOOTPRINT_SAMPLES; i++) {
            BlockPos stand = min.offset(
                    rng.nextInt(width), rng.nextInt(height), rng.nextInt(length));
            if (!level.getBlockState(stand.below()).isSolid()) continue;
            if (!level.getBlockState(stand).isAir()) continue;
            if (!level.getBlockState(stand.above()).isAir()) continue;
            return stand;
        }
        return null;
    }

    private void armCooldown(TownspersonMob entity) {
        int ttl = COOLDOWN_MIN + entity.getRandom().nextInt(COOLDOWN_MAX - COOLDOWN_MIN + 1);
        entity.getBrain().setMemoryWithExpiry(
                NpcMemoryTypes.IDLE_DIRECTOR_COOLDOWN.get(),
                entity.level().getGameTime(), ttl);
    }
}
