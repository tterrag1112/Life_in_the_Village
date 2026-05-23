package tterrag1112.life_in_the_village.Entities.Goals.Social;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.phys.Vec3;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;
import tterrag1112.life_in_the_village.Npc.Skills.SkillXp;
import tterrag1112.life_in_the_village.Npc.Specialization.FarmerSpecialtyMultiplier;
import tterrag1112.life_in_the_village.Village.Decoration.Adjunct.ActivityTag;
import tterrag1112.life_in_the_village.Village.Decoration.Adjunct.AdjunctPlot;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

public class ChildPlayGoal extends Goal {

    private enum PlayMode {
        RUNNING,    // running to random point
        CHASING,    // chasing another child
        RESTING,    // brief pause between activities
        // Phase 6.3.3.j.1 — farming chores. Children learn by doing,
        // so no skill threshold. Routed by AdjunctPlotType.activityTag()
        // so future homestead tags (e.g. TANNING children's chore)
        // can extend the dispatch without touching the play loop.
        EGG_COLLECTION_CHORE,   // POULTRY plot → ANIMAL_HUSBANDRY
        WEEDING_CHORE,          // CROP plot   → CROP_FARMING
        WATERING_CHORE,         // CROP plot   → CROP_FARMING
        HERDING_CHORE           // LIVESTOCK   → ANIMAL_HUSBANDRY
    }

    private static final int PLAY_RADIUS = 16;
    private static final int CHASE_RANGE = 12;
    private static final int REST_DURATION = 40;
    private static final int PLAY_DURATION = 200;
    private static final int CHECK_INTERVAL = 100;

    /** Phase 6.3.3.j.1.D — chore cycle. Shorter than the adult
     *  homestead loop (90-160 ticks) so children look busy without
     *  earning adult-scale yields. */
    private static final int CHORE_DURATION = 80;
    private static final double CHORE_WALK_SPEED = 0.8;
    private static final float CHORE_BASE_XP = 1f;
    /** 1-in-CHORE_ROLL_DEN chance per pickPlayMode to attempt a chore
     *  instead of vanilla play; falls through to play if no farmable
     *  plots are attached to the household. */
    private static final int CHORE_ROLL_DEN = 3;

    private final TownspersonMob entity;
    private PlayMode mode = PlayMode.RESTING;
    private int timer = 0;
    private int checkTimer = 0;
    private TownspersonMob chaseTarget = null;
    private BlockPos runTarget = null;
    private AdjunctPlot choreTarget = null;
    private boolean choreXpGranted = false;

    public ChildPlayGoal(TownspersonMob entity) {
        this.entity = entity;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!entity.isChild()) return false;
        if (entity.shouldBeHome()) return false;
        checkTimer++;
        if (checkTimer < CHECK_INTERVAL) return false;
        checkTimer = 0;
        return entity.getRandom().nextInt(3) == 0;
    }

    @Override
    public boolean canContinueToUse() {
        if (entity.shouldBeHome()) return false;
        return timer < PLAY_DURATION * 3;
    }

    @Override
    public void start() {
        timer = 0;
        pickPlayMode();
    }

    @Override
    public boolean requiresUpdateEveryTick() { return true; }

    @Override
    public void tick() {
        if (!(entity.level() instanceof ServerLevel level)) return;
        timer++;

        switch (mode) {
            case RUNNING               -> tickRunning(level);
            case CHASING               -> tickChasing(level);
            case RESTING               -> tickResting(level);
            case EGG_COLLECTION_CHORE,
                 WEEDING_CHORE,
                 WATERING_CHORE,
                 HERDING_CHORE         -> tickChore(level);
        }

        // Occasionally jump while playing — suppress during chores so
        // a child weeding the garden doesn't bounce like they're playing.
        if (!isChoreMode() && timer % 30 == 0 && entity.onGround()
                && entity.getRandom().nextInt(3) == 0) {
            entity.setDeltaMovement(
                    entity.getDeltaMovement().x,
                    0.42,
                    entity.getDeltaMovement().z
            );
        }

        // Switch mode periodically
        if (timer % PLAY_DURATION == 0) {
            pickPlayMode();
        }
    }

    private void tickRunning(ServerLevel level) {
        if (runTarget == null || entity.distanceToSqr(
                runTarget.getX(), runTarget.getY(),
                runTarget.getZ()) < 4) {
            // Arrived — rest briefly then pick new target
            mode = PlayMode.RESTING;
            timer = 0;
            playAmbientSound(level);
        } else if (!entity.getNavigation().isInProgress()) {
            entity.getNavigation().moveTo(
                    runTarget.getX(), runTarget.getY(),
                    runTarget.getZ(), 1.4); // run speed
        }
    }

    private void tickChasing(ServerLevel level) {
        if (chaseTarget == null || !chaseTarget.isAlive()
                || !chaseTarget.isChild()) {
            mode = PlayMode.RUNNING;
            pickRunTarget(level);
            return;
        }

        entity.getLookControl().setLookAt(chaseTarget,
                30f, 30f);
        entity.getNavigation().moveTo(chaseTarget, 1.4);

        // If caught — play sound and switch to running
        if (entity.distanceToSqr(chaseTarget) < 4) {
            level.playSound(null, entity.blockPosition(),
                    SoundEvents.VILLAGER_YES,
                    SoundSource.NEUTRAL, 0.5f,
                    1.5f + entity.getRandom().nextFloat() * 0.5f);
            // Phase 2 task 15: shared-interest XP trickle. Spec line
            // 112 — children with overlapping interestedSkills gain
            // a small XP bump when they play together. Both sides
            // receive the bump.
            grantSharedInterestXp(level, chaseTarget);
            chaseTarget = null;
            mode = PlayMode.RESTING;
            timer = 0;
        }
    }

    /**
     * Grants +1 XP in any shared {@code interestedSkill} between
     * {@code entity} and {@code other}. Spec line 112 calls for
     * "+0.5 per session"; rounded to +1 since SkillComponent.addXp
     * takes integer-friendly floats. Routes through SkillXp.award so
     * the mentee multiplier composes — children with apprenticeship
     * contracts (rare but legal) get the right boost.
     */
    private void grantSharedInterestXp(ServerLevel level, TownspersonMob other) {
        var selfSkills = entity.getChildhoodState().interestedSkills();
        var otherState = other.getChildhoodState();
        long now = level.getGameTime();
        for (var s : selfSkills) {
            if (otherState.hasInterestIn(s.skill())) {
                SkillXp.award(entity, s.skill(), 1f, now);
                SkillXp.award(other, s.skill(), 1f, now);
            }
        }
    }

    private void tickResting(ServerLevel level) {
        entity.getNavigation().stop();
        if (timer >= REST_DURATION) {
            pickPlayMode();
        }
    }

    /**
     * Phase 6.3.3.j.1 — common chore tick. The chore-vs-play
     * distinction is the plot target and the sound + XP grant at
     * completion; movement is identical across the four chore modes
     * because the difference is the skill that gets the bump, not
     * the locomotion pattern. If the chore plot disappears mid-cycle
     * (plot re-roll, building destroyed) the child falls back to
     * RESTING and pickPlayMode re-evaluates.
     */
    private void tickChore(ServerLevel level) {
        if (choreTarget == null) {
            mode = PlayMode.RESTING;
            timer = 0;
            return;
        }
        BlockPos target = choreTarget.origin();
        if (timer < 20 && !entity.getNavigation().isInProgress()) {
            entity.getNavigation().moveTo(
                    target.getX() + 0.5, target.getY(), target.getZ() + 0.5,
                    CHORE_WALK_SPEED);
        }
        if (timer >= CHORE_DURATION && !choreXpGranted) {
            choreXpGranted = true;
            Skill skill = choreSkill(mode);
            float boosted = CHORE_BASE_XP
                    * FarmerSpecialtyMultiplier.of(entity, skill);
            SkillXp.award(entity, skill, boosted, level.getGameTime());
            level.playSound(null, entity.blockPosition(),
                    SoundEvents.VILLAGER_YES,
                    SoundSource.NEUTRAL, 0.3f,
                    1.4f + entity.getRandom().nextFloat() * 0.2f);
            mode = PlayMode.RESTING;
            timer = 0;
        }
    }

    private boolean isChoreMode() {
        return mode == PlayMode.EGG_COLLECTION_CHORE
                || mode == PlayMode.WEEDING_CHORE
                || mode == PlayMode.WATERING_CHORE
                || mode == PlayMode.HERDING_CHORE;
    }

    /** Per-chore XP target. BEES chores are not exposed to children
     *  yet (HOMESTEAD_BEES is treated as adult-supervised). */
    private static Skill choreSkill(PlayMode m) {
        return switch (m) {
            case EGG_COLLECTION_CHORE -> Skill.ANIMAL_HUSBANDRY;
            case HERDING_CHORE        -> Skill.ANIMAL_HUSBANDRY;
            case WEEDING_CHORE        -> Skill.CROP_FARMING;
            case WATERING_CHORE       -> Skill.CROP_FARMING;
            default -> Skill.FARMING;
        };
    }

    private void pickPlayMode() {
        if (!(entity.level() instanceof ServerLevel level)) return;
        choreTarget = null;
        choreXpGranted = false;

        // Phase 6.3.3.j.1 — first try a farming chore. Falls through
        // to vanilla play modes when the household has no farmable
        // adjunct plots or the random roll misses.
        if (entity.getRandom().nextInt(CHORE_ROLL_DEN) == 0
                && tryPickChore(level)) {
            return;
        }

        // 40% chase, 40% run, 20% rest
        int roll = entity.getRandom().nextInt(5);
        if (roll < 2) {
            // Try to find another child to chase
            List<TownspersonMob> children = level.getEntitiesOfClass(
                    TownspersonMob.class,
                    entity.getBoundingBox().inflate(CHASE_RANGE),
                    mob -> mob != entity && mob.isChild()
            );
            if (!children.isEmpty()) {
                chaseTarget = children.get(
                        entity.getRandom().nextInt(children.size()));
                mode = PlayMode.CHASING;
                return;
            }
        }
        if (roll < 4) {
            pickRunTarget(level);
            mode = PlayMode.RUNNING;
        } else {
            mode = PlayMode.RESTING;
            timer = 0;
        }
    }

    /**
     * Phase 6.3.3.j.1.C — scan the household's adjunct plots, group
     * by tag, pick a chore matching the rolled tag. Returns true and
     * sets {@link #mode} + {@link #choreTarget} on success; false
     * leaves mode selection to fall through to vanilla play.
     */
    private boolean tryPickChore(ServerLevel level) {
        var houseId = entity.getHouseId();
        if (houseId.isEmpty()) return false;
        List<AdjunctPlot> plots = VillageSavedData.get(level)
                .getAdjunctPlotsForBuilding(houseId.get());
        if (plots.isEmpty()) return false;
        List<AdjunctPlot> farmable = new ArrayList<>();
        for (AdjunctPlot p : plots) {
            if (isChildChoreTag(p.type().activityTag())) farmable.add(p);
        }
        if (farmable.isEmpty()) return false;
        AdjunctPlot pick = farmable.get(
                entity.getRandom().nextInt(farmable.size()));
        PlayMode chore = choreForTag(pick.type().activityTag());
        if (chore == null) return false;
        this.choreTarget = pick;
        this.choreXpGranted = false;
        this.mode = chore;
        this.timer = 0;
        return true;
    }

    /** Tags children may help with. BEES, METALWORK, TANNING etc.
     *  remain adult-only for safety / supervision realism. */
    private static boolean isChildChoreTag(ActivityTag tag) {
        return tag == ActivityTag.POULTRY
                || tag == ActivityTag.CROP
                || tag == ActivityTag.LIVESTOCK;
    }

    private PlayMode choreForTag(ActivityTag tag) {
        return switch (tag) {
            case POULTRY   -> PlayMode.EGG_COLLECTION_CHORE;
            case LIVESTOCK -> PlayMode.HERDING_CHORE;
            case CROP      -> entity.getRandom().nextBoolean()
                    ? PlayMode.WEEDING_CHORE
                    : PlayMode.WATERING_CHORE;
            default        -> null;
        };
    }

    private void pickRunTarget(ServerLevel level) {
        // Run within house bounds if possible
        BlockPos home = getHomeBounds(level);
        Vec3 target = DefaultRandomPos.getPos(entity, PLAY_RADIUS, 4);
        runTarget = target != null
                ? BlockPos.containing(target)
                : entity.blockPosition().offset(
                entity.getRandom().nextInt(PLAY_RADIUS * 2) - PLAY_RADIUS,
                0,
                entity.getRandom().nextInt(PLAY_RADIUS * 2) - PLAY_RADIUS
        );
    }

    private BlockPos getHomeBounds(ServerLevel level) {
        return entity.getHouseId()
                .flatMap(id -> VillageSavedData.get(level).getBuildingById(id))
                .map(b -> b.getShape().getOrigin())
                .orElse(entity.blockPosition());
    }

    private void playAmbientSound(ServerLevel level) {
        level.playSound(null, entity.blockPosition(),
                SoundEvents.VILLAGER_AMBIENT,
                SoundSource.NEUTRAL, 0.4f,
                1.4f + entity.getRandom().nextFloat() * 0.4f);
    }

    @Override
    public void stop() {
        entity.getNavigation().stop();
        chaseTarget = null;
        runTarget = null;
        choreTarget = null;
        choreXpGranted = false;
        timer = 0;
    }
}
