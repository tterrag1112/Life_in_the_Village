package tterrag1112.life_in_the_village.Entities.Goals;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.phys.Vec3;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

public class ChildPlayGoal extends Goal {

    private enum PlayMode {
        RUNNING,    // running to random point
        CHASING,    // chasing another child
        RESTING     // brief pause between activities
    }

    private static final int PLAY_RADIUS = 16;
    private static final int CHASE_RANGE = 12;
    private static final int REST_DURATION = 40;
    private static final int PLAY_DURATION = 200;
    private static final int CHECK_INTERVAL = 100;

    private final TownspersonMob entity;
    private PlayMode mode = PlayMode.RESTING;
    private int timer = 0;
    private int checkTimer = 0;
    private TownspersonMob chaseTarget = null;
    private BlockPos runTarget = null;

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
            case RUNNING  -> tickRunning(level);
            case CHASING  -> tickChasing(level);
            case RESTING  -> tickResting(level);
        }

        // Occasionally jump while playing
        if (timer % 30 == 0 && entity.onGround()
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
            chaseTarget = null;
            mode = PlayMode.RESTING;
            timer = 0;
        }
    }

    private void tickResting(ServerLevel level) {
        entity.getNavigation().stop();
        if (timer >= REST_DURATION) {
            pickPlayMode();
        }
    }

    private void pickPlayMode() {
        if (!(entity.level() instanceof ServerLevel level)) return;

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
        timer = 0;
    }
}