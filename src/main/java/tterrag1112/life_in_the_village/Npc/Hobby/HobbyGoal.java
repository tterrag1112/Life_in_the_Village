package tterrag1112.life_in_the_village.Npc.Hobby;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Schedule.DayPhase;
import tterrag1112.life_in_the_village.Npc.Schedule.ScheduleResolver;

import java.util.EnumSet;
import java.util.Optional;

/**
 * Executes the NPC's currently-selected hobby. Spec
 * {@code 14-hobby-activities.md} (line 172).
 *
 * <p>Phases: WALKING_TO_LOCATION → PERFORMING → LEAVING. The
 * {@link HobbyActivity} drives the per-tick animation loop while
 * PERFORMING. On clean stop the goal awards skill XP per
 * {@link HobbyDefinition#skillXpPerSession}.</p>
 */
public class HobbyGoal extends Goal {

    /** How close (squared) the NPC must be to the hobby pos before performing. */
    public static final double ARRIVAL_DIST_SQ = 4.0;
    /** Walk speed multiplier — leisure pace. */
    public static final double WALK_SPEED = 0.7;

    private final TownspersonMob entity;

    private Phase phase = Phase.IDLE;
    private HobbyDefinition activeDefinition;
    private BlockPos targetPos;
    private long startTick;
    private int subTimer;        // counts within the current phase
    private ItemStack savedMainHand = ItemStack.EMPTY;

    public enum Phase { IDLE, WALKING_TO_LOCATION, PERFORMING, LEAVING }

    public HobbyGoal(TownspersonMob entity) {
        this.entity = entity;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (entity.isChild()) return false;
        if (!(entity.level() instanceof ServerLevel level)) return false;
        long tick = level.getGameTime();

        DayPhase cur = ScheduleResolver.phaseAt(entity, tick);
        boolean leisure = cur == DayPhase.LEISURE;
        boolean dayOff = ScheduleResolver.isDayOff(entity, tick) && !cur.isWork();
        if (!leisure && !dayOff) return false;

        var pref = entity.getHobbyPreference();
        // Pick a session hobby on the boundary if not currently set.
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
            // Location no longer accessible — clear and let next pick try.
            pref.clearCurrent();
            return false;
        }
        this.activeDefinition = def.get();
        this.targetPos = resolved.get();
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (phase == Phase.IDLE) return false;
        if (!(entity.level() instanceof ServerLevel level)) return false;
        long tick = level.getGameTime();
        DayPhase cur = ScheduleResolver.phaseAt(entity, tick);
        boolean inLeisureOrDayOff = cur == DayPhase.LEISURE
                || (ScheduleResolver.isDayOff(entity, tick) && !cur.isWork());
        if (!inLeisureOrDayOff && phase != Phase.LEAVING) {
            // Schedule changed mid-session — bail out cleanly via LEAVING.
            phase = Phase.LEAVING;
            subTimer = 0;
        }
        // Hard cap so a stuck goal can't pin the slot.
        return tick - startTick < activeDefinition.durationTicks() + 600L;
    }

    @Override
    public void start() {
        if (!(entity.level() instanceof ServerLevel level)) return;
        startTick = level.getGameTime();
        phase = Phase.WALKING_TO_LOCATION;
        subTimer = 0;
        entity.setCurrentActivity(activeDefinition.displayName());
        entity.getNavigation().moveTo(
                targetPos.getX() + 0.5, targetPos.getY(), targetPos.getZ() + 0.5,
                WALK_SPEED);
    }

    @Override
    public boolean requiresUpdateEveryTick() { return true; }

    @Override
    public void tick() {
        subTimer++;
        switch (phase) {
            case WALKING_TO_LOCATION -> tickWalking();
            case PERFORMING          -> tickPerforming();
            case LEAVING             -> tickLeaving();
            case IDLE                -> {}
        }
    }

    private void tickWalking() {
        double distSq = entity.distanceToSqr(
                targetPos.getX() + 0.5, targetPos.getY(), targetPos.getZ() + 0.5);
        if (distSq <= ARRIVAL_DIST_SQ) {
            entity.getNavigation().stop();
            startPerforming();
            return;
        }
        if (!entity.getNavigation().isInProgress()) {
            entity.getNavigation().moveTo(
                    targetPos.getX() + 0.5, targetPos.getY(), targetPos.getZ() + 0.5,
                    WALK_SPEED);
        }
        // Bail if we somehow can't pathfind.
        if (subTimer > 600 && !entity.getNavigation().isInProgress() && distSq > ARRIVAL_DIST_SQ) {
            phase = Phase.LEAVING;
            subTimer = 0;
        }
    }

    private void startPerforming() {
        phase = Phase.PERFORMING;
        subTimer = 0;
        equipForActivity(activeDefinition.activities().get(0));
    }

    private void tickPerforming() {
        // Look at the target.
        entity.getLookControl().setLookAt(
                targetPos.getX() + 0.5, targetPos.getY() + 1.0, targetPos.getZ() + 0.5);
        performTick();
        if (subTimer >= activeDefinition.durationTicks()) {
            phase = Phase.LEAVING;
            subTimer = 0;
        }
    }

    /** Activity-specific per-tick behaviour. Spec line 200. */
    private void performTick() {
        HobbyActivity a = activeDefinition.activities().get(0);
        switch (a) {
            case SWORD_PRACTICE, ARCHERY_PRACTICE -> {
                if (subTimer % 30 == 0) entity.swing(InteractionHand.MAIN_HAND);
            }
            case FISH -> {
                if (subTimer % 200 == 0) entity.swing(InteractionHand.MAIN_HAND);
            }
            case SIT_AND_CARVE, SIT_AND_READ, MEDITATE, PRAY -> {
                // Pose-only; no per-tick swing. NPC already looking at target.
            }
            case WALK -> {
                // Pace around the trail point.
                if (subTimer % 80 == 0 && !entity.getNavigation().isInProgress()) {
                    entity.getNavigation().moveTo(
                            targetPos.getX() + (entity.getRandom().nextInt(7) - 3),
                            targetPos.getY(),
                            targetPos.getZ() + (entity.getRandom().nextInt(7) - 3),
                            WALK_SPEED);
                }
            }
            case GARDEN, TEND_FLOWERS, COOK, WRITE_LETTER, COPY_BOOK, DRINK_AND_TALK,
                 CARD_GAMES, TELL_STORY, SHOP_AIMLESSLY, VISIT_FRIEND, VISIT_GRAVE -> {
                // No per-tick action; the look + held-item carries the moment.
            }
        }
    }

    private void tickLeaving() {
        // Walk back home. Spec line 192 ("LEAVING" phase).
        var home = entity.getHouseId()
                .flatMap(id -> tterrag1112.life_in_the_village.Networking.VillageSavedData
                        .get((ServerLevel) entity.level()).getBuildingById(id))
                .map(b -> b.getShape().getOrigin())
                .orElse(null);
        if (home != null && !entity.getNavigation().isInProgress()) {
            entity.getNavigation().moveTo(
                    home.getX() + 0.5, home.getY(), home.getZ() + 0.5, WALK_SPEED);
        }
        // Clean stop after a brief beat or once arrived/no-progress for too long.
        if (subTimer > 200) {
            phase = Phase.IDLE;
        }
    }

    @Override
    public void stop() {
        entity.getNavigation().stop();
        entity.clearCurrentActivity();
        if (!savedMainHand.isEmpty() || !entity.getMainHandItem().isEmpty()) {
            entity.setItemInHand(InteractionHand.MAIN_HAND, savedMainHand);
        }
        savedMainHand = ItemStack.EMPTY;

        // Award XP only on a full PERFORMING completion (LEAVING reached
        // after subTimer >= duration). Aborted runs get nothing.
        if (activeDefinition != null
                && entity.level() instanceof ServerLevel level
                && phase != Phase.WALKING_TO_LOCATION) {
            long now = level.getGameTime();
            if (activeDefinition.skillGain().isPresent()
                    && activeDefinition.skillXpPerSession() > 0) {
                entity.getSkills().addXp(activeDefinition.skillGain().get(),
                        activeDefinition.skillXpPerSession(), now);
            }
            entity.getHobbyPreference().noteUsed(activeDefinition.id(), now);
        }
        // Whether or not XP was granted, clear current — next LEISURE
        // boundary picks again.
        entity.getHobbyPreference().clearCurrent();
        phase = Phase.IDLE;
        activeDefinition = null;
        targetPos = null;
    }

    private void equipForActivity(HobbyActivity a) {
        savedMainHand = entity.getMainHandItem().copy();
        switch (a) {
            case SIT_AND_READ, COPY_BOOK -> setHand(Items.WRITTEN_BOOK);
            case WRITE_LETTER            -> setHand(Items.PAPER);
            case FISH                    -> setHand(Items.FISHING_ROD);
            case SWORD_PRACTICE          -> setHand(Items.IRON_SWORD);
            case ARCHERY_PRACTICE        -> setHand(Items.BOW);
            case COOK                    -> setHand(Items.BREAD);
            case TEND_FLOWERS            -> setHand(Items.POPPY);
            case GARDEN                  -> setHand(Items.WHEAT_SEEDS);
            case DRINK_AND_TALK          -> setHand(Items.POTION);
            case CARD_GAMES, TELL_STORY,
                 SHOP_AIMLESSLY, MEDITATE, PRAY,
                 WALK, VISIT_FRIEND, VISIT_GRAVE,
                 SIT_AND_CARVE -> { /* no held-item change */ }
        }
    }

    private void setHand(net.minecraft.world.item.Item item) {
        entity.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(item));
    }

    private static boolean sameHobbyStillKnown(NpcHobbyPreference pref) {
        return pref.currentHobby() != null
                && HobbyCatalogue.get(pref.currentHobby()).isPresent();
    }
}
