package tterrag1112.life_in_the_village.Npc.Brain.Behaviors;

import com.google.common.collect.ImmutableMap;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.EntityTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Brain.BrainNavGuard;
import tterrag1112.life_in_the_village.Npc.Brain.Memories.NpcMemoryTypes;
import tterrag1112.life_in_the_village.Npc.BusinessFront.BusinessFrontStatus;
import tterrag1112.life_in_the_village.Npc.BusinessFront.BusinessFrontTracker;
import tterrag1112.life_in_the_village.Npc.BusinessFront.BuildingPresenceTracker;
import tterrag1112.life_in_the_village.Profession.Profession;

import java.util.UUID;

/**
 * Phase 6.2.b — migrated from {@code GreetPlayerGoal}. Universal IDLE
 * and SOCIAL @ 0 (high). Externally seated: {@code GreeterAssignment}
 * sets {@code GREET_TARGET} memory on the chosen worker; this behavior
 * consumes it.
 *
 * <p>Phases (mirror the original goal): APPROACH → ATTENDING →
 * DISMISS. APPROACH walks to within {@link #INTERACT_RANGE_SQ} of the
 * player and barks once; ATTENDING holds for up to {@link #ATTEND_TIMEOUT_TICKS}
 * waiting for a right-click before auto-DISMISS. DISMISS clears state.
 *
 * <p>Coexistence: WALK_TARGET write gated by canSteerNavigation,
 * LOOK_TARGET write gated by canRotateHead.
 */
public class GreetPlayerBehavior extends Behavior<TownspersonMob> {

    private static final int INTERACT_RANGE_SQ = 16;
    private static final int ATTEND_TIMEOUT_TICKS = 600;
    private static final float WALK_SPEED = 1.0f;
    private static final int CLOSE_ENOUGH = 2;
    private static final int MAX_RUN = 2400; // safety upper bound

    private enum Phase { APPROACH, ATTENDING, DISMISS }

    private Phase phase = Phase.DISMISS;
    private long phaseStartTick = 0L;
    private boolean hasBarked = false;

    public GreetPlayerBehavior() {
        super(ImmutableMap.of(
                NpcMemoryTypes.GREET_TARGET.get(), MemoryStatus.VALUE_PRESENT,
                MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED,
                MemoryModuleType.LOOK_TARGET, MemoryStatus.REGISTERED
        ), MAX_RUN);
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, TownspersonMob entity) {
        if (!entity.isWorkTime()) return false;
        if (!BrainNavGuard.canSteerNavigation(entity)) return false;
        if (!BrainNavGuard.canRotateHead(entity)) return false;
        ServerPlayer target = targetOrNull(entity);
        return target != null && target.isAlive() && !target.isRemoved();
    }

    @Override
    protected boolean canStillUse(ServerLevel level, TownspersonMob entity, long gameTime) {
        if (phase == Phase.DISMISS) return false;
        ServerPlayer target = targetOrNull(entity);
        return target != null && target.isAlive() && !target.isRemoved()
                && entity.isWorkTime();
    }

    @Override
    protected void start(ServerLevel level, TownspersonMob entity, long gameTime) {
        phase = Phase.APPROACH;
        phaseStartTick = gameTime;
        hasBarked = false;
        entity.setCurrentActivity("Greeting customer");
        ServerPlayer target = targetOrNull(entity);
        if (target != null) {
            entity.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                    new WalkTarget(target.blockPosition(), WALK_SPEED, CLOSE_ENOUGH));
        }
    }

    @Override
    protected void tick(ServerLevel level, TownspersonMob entity, long gameTime) {
        ServerPlayer target = targetOrNull(entity);
        if (target == null) { phase = Phase.DISMISS; return; }

        switch (phase) {
            case APPROACH -> tickApproach(level, entity, target, gameTime);
            case ATTENDING -> tickAttending(entity, target, gameTime);
            case DISMISS -> {}
        }
    }

    private void tickApproach(ServerLevel level, TownspersonMob entity,
                              ServerPlayer target, long now) {
        BlockPos pos = target.blockPosition();
        double distSq = entity.distanceToSqr(pos.getX(), pos.getY(), pos.getZ());
        if (distSq <= INTERACT_RANGE_SQ) {
            entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            entity.getBrain().setMemory(MemoryModuleType.LOOK_TARGET,
                    new EntityTracker(target, true));
            phase = Phase.ATTENDING;
            phaseStartTick = now;
            UUID buildingId = entity.getAssignedBuildingId().orElse(null);
            if (buildingId != null) {
                BusinessFrontTracker.put(BusinessFrontTracker.getOrCreate(buildingId)
                        .withGreeter(entity.getUUID(), target.getUUID(), now,
                                BusinessFrontStatus.GREETER_ATTENDING));
            }
            return;
        }
        if (!hasBarked) {
            bark(target, entity);
            hasBarked = true;
            UUID buildingId = entity.getAssignedBuildingId().orElse(null);
            if (buildingId != null) {
                BusinessFrontTracker.put(BusinessFrontTracker.getOrCreate(buildingId)
                        .withGreeter(entity.getUUID(), target.getUUID(), now,
                                BusinessFrontStatus.GREETER_APPROACHING));
            }
        }
        // Refresh walk target — player moves.
        entity.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                new WalkTarget(pos, WALK_SPEED, CLOSE_ENOUGH));
    }

    private void tickAttending(TownspersonMob entity, ServerPlayer target, long now) {
        UUID buildingId = entity.getAssignedBuildingId().orElse(null);
        if (buildingId != null) {
            UUID playerHere = BuildingPresenceTracker.getBuildingFor(target.getUUID()).orElse(null);
            if (playerHere == null || !playerHere.equals(buildingId)) {
                phase = Phase.DISMISS;
                return;
            }
        }
        if (now - phaseStartTick > ATTEND_TIMEOUT_TICKS) {
            phase = Phase.DISMISS;
        }
    }

    @Override
    protected void stop(ServerLevel level, TownspersonMob entity, long gameTime) {
        Brain<TownspersonMob> brain = entity.getBrain();
        brain.eraseMemory(MemoryModuleType.WALK_TARGET);
        brain.eraseMemory(MemoryModuleType.LOOK_TARGET);
        // Don't erase GREET_TARGET unconditionally — the GreeterAssignment
        // side may still be tracking the player. The memory's TTL (set by
        // assignFor) self-clears if no one re-stamps it.
        phase = Phase.DISMISS;
        hasBarked = false;
    }

    private static ServerPlayer targetOrNull(TownspersonMob entity) {
        LivingEntity le = entity.getBrain()
                .getMemory(NpcMemoryTypes.GREET_TARGET.get()).orElse(null);
        return (le instanceof ServerPlayer sp) ? sp : null;
    }

    // ── Bark text (verbatim from GreetPlayerGoal) ───────────────────────────
    private static void bark(ServerPlayer target, TownspersonMob npc) {
        Component bark = barkFor(npc.getProfession(), npc);
        target.displayClientMessage(bark, /* actionBar */ true);
    }

    private static Component barkFor(Profession p, TownspersonMob npc) {
        String name = npc.getNpcName();
        return switch (p) {
            case MERCHANT, STOCKPILE_KEEPER -> Component.literal("[" + name + "] Welcome — looking for anything in particular?");
            case BLACKSMITH, CARPENTER, STONEMASON, WEAVER, CANDLEMAKER, BAKER, MILLER ->
                    Component.literal("[" + name + "] Welcome to my workshop. Need something made?");
            case INNKEEPER -> Component.literal("[" + name + "] Take a seat, traveller.");
            case SCRIBE    -> Component.literal("[" + name + "] A letter, a contract, or a copy?");
            case LIBRARIAN -> Component.literal("[" + name + "] Quietly, please. What are you looking for?");
            case SCHOLAR   -> Component.literal("[" + name + "] Hmm? Oh, do come in.");
            case PRIEST    -> Component.literal("[" + name + "] Peace be with you. How may I help?");
            default        -> Component.literal("[" + name + "] Welcome.");
        };
    }

}
