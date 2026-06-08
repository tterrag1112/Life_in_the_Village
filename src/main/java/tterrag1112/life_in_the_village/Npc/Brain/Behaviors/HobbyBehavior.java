package tterrag1112.life_in_the_village.Npc.Brain.Behaviors;

import com.google.common.collect.ImmutableMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Brain.BrainNavGuard;
import tterrag1112.life_in_the_village.Npc.Brain.Memories.NpcMemoryTypes;
import tterrag1112.life_in_the_village.Npc.Hobby.HobbyActivity;
import tterrag1112.life_in_the_village.Npc.Hobby.HobbyCatalogue;
import tterrag1112.life_in_the_village.Npc.Hobby.HobbyDefinition;
import tterrag1112.life_in_the_village.Npc.Hobby.HobbyLocationResolver;
import tterrag1112.life_in_the_village.Npc.Hobby.NpcHobbyPreference;
import tterrag1112.life_in_the_village.Npc.Schedule.DayPhase;
import tterrag1112.life_in_the_village.Npc.Schedule.ScheduleResolver;

import java.util.Optional;

/**
 * Phase 6.2.a — migrated from {@code HobbyGoal}. SOCIAL activity, low
 * priority. Walks to a hobby spot, performs the hobby for the
 * definition's duration, returns home, awards skill XP on clean
 * completion.
 *
 * <p>External subsystem APIs (hobby preference, location resolver,
 * skill XP) are preserved 1:1 from the Goal — only the lifecycle
 * shell changed.
 */
public class HobbyBehavior extends Behavior<TownspersonMob> {

    private static final double ARRIVAL_DIST_SQ = 4.0;
    private static final float WALK_SPEED = 0.7f;
    private static final int CLOSE_ENOUGH = 1;
    private static final int MAX_RUN = 24000; // hard upper bound; canStillUse enforces real cap

    private enum Phase { WALKING_TO_LOCATION, PERFORMING, LEAVING }

    private Phase phase = Phase.WALKING_TO_LOCATION;
    private HobbyDefinition activeDefinition;
    private BlockPos targetPos;
    private long startTick;
    private int subTimer;
    private ItemStack savedMainHand = ItemStack.EMPTY;

    public HobbyBehavior() {
        super(ImmutableMap.of(
                MemoryModuleType.WALK_TARGET, MemoryStatus.VALUE_ABSENT
        ), MAX_RUN);
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, TownspersonMob entity) {
        if (entity.isChild()) return false;
        if (!BrainNavGuard.canSteerNavigation(entity)) return false;

        if (!hobbyEligible(level, entity)) return false;

        long tick = level.getGameTime();
        NpcHobbyPreference pref = entity.getHobbyPreference();
        if (!pref.hasCurrent() || !sameHobbyStillKnown(pref)) {
            Optional<HobbyDefinition> session = pref.pickForSession(
                    entity, level, tick, level.getRandom());
            if (session.isEmpty()) return false;
            pref.setCurrent(session.get().id(), tick);
        }
        Optional<HobbyDefinition> def = HobbyCatalogue.get(pref.currentHobby());
        if (def.isEmpty()) return false;

        Optional<BlockPos> resolved = HobbyLocationResolver.resolve(
                def.get().location(), entity, level);
        if (resolved.isEmpty()) {
            pref.clearCurrent();
            return false;
        }
        this.activeDefinition = def.get();
        this.targetPos = resolved.get();
        return true;
    }

    @Override
    protected boolean canStillUse(ServerLevel level, TownspersonMob entity, long gameTime) {
        if (activeDefinition == null) return false;
        long tick = level.getGameTime();
        // Wind down (LEAVING) once no longer eligible — i.e. leisure/day-off
        // ended, OR (idle work time) production has work again and cleared the
        // NO_ACTIONABLE_WORK signal. So the hobby yields when real work resumes.
        if (!hobbyEligible(level, entity) && phase != Phase.LEAVING) {
            phase = Phase.LEAVING;
            subTimer = 0;
        }
        return tick - startTick < activeDefinition.durationTicks() + 600L;
    }

    /**
     * L2 — hobby eligibility. Existing: the resolved phase is LEISURE, or it's
     * a day off outside a work phase. New: it's work time AND there's no
     * actionable work ({@code NO_ACTIONABLE_WORK}, the shared work-satisfied
     * signal also gating the idle director) — so idle work time goes to hobbies
     * too. NOT eligible during active production (the signal is absent then).
     */
    private static boolean hobbyEligible(ServerLevel level, TownspersonMob entity) {
        long tick = level.getGameTime();
        DayPhase cur = ScheduleResolver.phaseAt(entity, tick);
        if (cur == DayPhase.LEISURE) return true;
        if (ScheduleResolver.isDayOff(entity, tick) && !cur.isWork()) return true;
        return entity.isWorkTime()
                && entity.getBrain().hasMemoryValue(NpcMemoryTypes.NO_ACTIONABLE_WORK.get());
    }

    @Override
    protected void start(ServerLevel level, TownspersonMob entity, long gameTime) {
        startTick = gameTime;
        phase = Phase.WALKING_TO_LOCATION;
        subTimer = 0;
        entity.setCurrentActivity(activeDefinition.displayName());
        entity.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                new WalkTarget(targetPos, WALK_SPEED, CLOSE_ENOUGH));
    }

    @Override
    protected void tick(ServerLevel level, TownspersonMob entity, long gameTime) {
        subTimer++;
        switch (phase) {
            case WALKING_TO_LOCATION -> tickWalking(entity);
            case PERFORMING          -> tickPerforming(entity);
            case LEAVING             -> tickLeaving(level, entity);
        }
    }

    private void tickWalking(TownspersonMob entity) {
        double distSq = entity.distanceToSqr(
                targetPos.getX() + 0.5, targetPos.getY(), targetPos.getZ() + 0.5);
        if (distSq <= ARRIVAL_DIST_SQ) {
            entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            startPerforming(entity);
            return;
        }
        if (subTimer > 600 && !entity.getNavigation().isInProgress() && distSq > ARRIVAL_DIST_SQ) {
            phase = Phase.LEAVING;
            subTimer = 0;
        }
    }

    private void startPerforming(TownspersonMob entity) {
        phase = Phase.PERFORMING;
        subTimer = 0;
        HobbyActivity activity = activeDefinition.activities().get(0);
        equipForActivity(entity, activity);
        // R5b — visiting a grave: ease grief + remember the deceased once, on
        // arrival (the rest of PERFORMING is the contemplative pose).
        if (activity == HobbyActivity.VISIT_GRAVE
                && entity.level() instanceof net.minecraft.server.level.ServerLevel sl) {
            tterrag1112.life_in_the_village.Village.Graveyard.GraveVisit
                    .contemplate(sl, entity, sl.getGameTime());
        }
    }

    private void tickPerforming(TownspersonMob entity) {
        entity.getLookControl().setLookAt(
                targetPos.getX() + 0.5, targetPos.getY() + 1.0, targetPos.getZ() + 0.5);
        performTick(entity);
        if (subTimer >= activeDefinition.durationTicks()) {
            phase = Phase.LEAVING;
            subTimer = 0;
        }
    }

    private void performTick(TownspersonMob entity) {
        HobbyActivity a = activeDefinition.activities().get(0);
        switch (a) {
            case SWORD_PRACTICE, ARCHERY_PRACTICE -> {
                if (subTimer % 30 == 0) entity.swing(InteractionHand.MAIN_HAND);
            }
            case FISH -> {
                if (subTimer % 200 == 0) entity.swing(InteractionHand.MAIN_HAND);
            }
            case SIT_AND_CARVE, SIT_AND_READ, MEDITATE, PRAY -> { /* pose-only */ }
            case WALK -> {
                if (subTimer % 80 == 0 && !entity.getNavigation().isInProgress()) {
                    BlockPos pace = targetPos.offset(
                            entity.getRandom().nextInt(7) - 3, 0,
                            entity.getRandom().nextInt(7) - 3);
                    entity.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                            new WalkTarget(pace, WALK_SPEED, CLOSE_ENOUGH));
                }
            }
            case GARDEN, TEND_FLOWERS, COOK, WRITE_LETTER, COPY_BOOK, DRINK_AND_TALK,
                 CARD_GAMES, TELL_STORY, SHOP_AIMLESSLY, VISIT_FRIEND, VISIT_GRAVE -> {
                /* held-item + look carries the moment */
            }
        }
    }

    private void tickLeaving(ServerLevel level, TownspersonMob entity) {
        BlockPos home = entity.getFamily().getHouseId()
                .flatMap(id -> VillageSavedData.get(level).getBuildingById(id))
                .map(b -> b.getShape().getOrigin())
                .orElse(null);
        if (home != null && !entity.getNavigation().isInProgress()) {
            entity.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                    new WalkTarget(home, WALK_SPEED, CLOSE_ENOUGH));
        }
        if (subTimer > 200) {
            // Behavior duration cap will fire shortly; let canStillUse end it.
            activeDefinition = null; // signal canStillUse to return false
        }
    }

    @Override
    protected void stop(ServerLevel level, TownspersonMob entity, long gameTime) {
        entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        entity.clearCurrentActivity();
        if (!savedMainHand.isEmpty() || !entity.getMainHandItem().isEmpty()) {
            entity.setItemInHand(InteractionHand.MAIN_HAND, savedMainHand);
        }
        savedMainHand = ItemStack.EMPTY;

        // XP only on a full PERFORMING completion. Aborted runs get nothing.
        if (activeDefinition != null && phase != Phase.WALKING_TO_LOCATION) {
            if (activeDefinition.skillGain().isPresent()
                    && activeDefinition.skillXpPerSession() > 0) {
                tterrag1112.life_in_the_village.Npc.Skills.SkillXp.award(entity, activeDefinition.skillGain().get(),
                        activeDefinition.skillXpPerSession(), gameTime);
            }
            entity.getHobbyPreference().noteUsed(activeDefinition.id(), gameTime);
        }
        entity.getHobbyPreference().clearCurrent();
        entity.getBrain().setMemoryWithExpiry(
                NpcMemoryTypes.HOBBY_COOLDOWN.get(), gameTime, 200L);

        activeDefinition = null;
        targetPos = null;
        phase = Phase.WALKING_TO_LOCATION;
    }

    private void equipForActivity(TownspersonMob entity, HobbyActivity a) {
        savedMainHand = entity.getMainHandItem().copy();
        switch (a) {
            case SIT_AND_READ, COPY_BOOK -> setHand(entity, Items.WRITTEN_BOOK);
            case WRITE_LETTER            -> setHand(entity, Items.PAPER);
            case FISH                    -> setHand(entity, Items.FISHING_ROD);
            case SWORD_PRACTICE          -> setHand(entity, Items.IRON_SWORD);
            case ARCHERY_PRACTICE        -> setHand(entity, Items.BOW);
            case COOK                    -> setHand(entity, Items.BREAD);
            case TEND_FLOWERS            -> setHand(entity, Items.POPPY);
            case GARDEN                  -> setHand(entity, Items.WHEAT_SEEDS);
            case DRINK_AND_TALK          -> setHand(entity, Items.POTION);
            case CARD_GAMES, TELL_STORY, SHOP_AIMLESSLY, MEDITATE, PRAY,
                 WALK, VISIT_FRIEND, VISIT_GRAVE, SIT_AND_CARVE -> { /* no change */ }
        }
    }

    private static void setHand(TownspersonMob entity, net.minecraft.world.item.Item item) {
        entity.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(item));
    }

    private static boolean sameHobbyStillKnown(NpcHobbyPreference pref) {
        return pref.currentHobby() != null
                && HobbyCatalogue.get(pref.currentHobby()).isPresent();
    }
}
