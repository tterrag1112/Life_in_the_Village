package tterrag1112.life_in_the_village.Entities.Goals.Social;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;

import java.util.EnumSet;
import java.util.List;

public class ElderlyRelaxGoal extends Goal {

    private enum RelaxMode {
        WALKING_TO_SPOT,
        SITTING,
        WANDERING
    }

    private static final int CHECK_INTERVAL = 600;
    private static final int SIT_DURATION = 2400;
    private static final int WANDER_RADIUS = 8;
    private static final int INTERACT_RANGE_SQ = 9;

    private final TownspersonMob entity;
    private RelaxMode mode = RelaxMode.WANDERING;
    private int checkTimer = 0;
    private int sitTimer = 0;
    private BlockPos sitPos = null;

    public ElderlyRelaxGoal(TownspersonMob entity) {
        this.entity = entity;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!entity.isElderly()) return false;
        if (entity.shouldBeHome()) return false;
        checkTimer++;
        if (checkTimer < CHECK_INTERVAL) return false;
        checkTimer = 0;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (entity.shouldBeHome()) return false;
        return mode == RelaxMode.SITTING
                ? sitTimer < SIT_DURATION
                : true;
    }

    @Override
    public void start() {
        if (!(entity.level() instanceof ServerLevel level)) return;

        // 60% chance to find a sit spot, 40% chance to wander
        if (entity.getRandom().nextFloat() < 0.6f) {
            sitPos = findSitSpot(level);
            if (sitPos != null) {
                mode = RelaxMode.WALKING_TO_SPOT;
                entity.getNavigation().moveTo(
                        sitPos.getX() + 0.5, sitPos.getY(),
                        sitPos.getZ() + 0.5, 0.5); // slow walk
                return;
            }
        }

        mode = RelaxMode.WANDERING;
        pickWanderTarget(level);
    }

    @Override
    public boolean requiresUpdateEveryTick() { return true; }

    @Override
    public void tick() {
        if (!(entity.level() instanceof ServerLevel level)) return;

        switch (mode) {
            case WALKING_TO_SPOT -> walkToSpot();
            case SITTING         -> sit(level);
            case WANDERING       -> wander(level);
        }
    }

    private void walkToSpot() {
        if (sitPos == null) { mode = RelaxMode.WANDERING; return; }

        double distSq = entity.distanceToSqr(
                sitPos.getX(), sitPos.getY(), sitPos.getZ());

        if (distSq <= INTERACT_RANGE_SQ) {
            entity.getNavigation().stop();
            sitTimer = 0;
            mode = RelaxMode.SITTING;
        } else if (!entity.getNavigation().isInProgress()) {
            entity.getNavigation().moveTo(
                    sitPos.getX() + 0.5, sitPos.getY(),
                    sitPos.getZ() + 0.5, 0.5);
        }
    }

    private void sit(ServerLevel level) {
        sitTimer++;

        // Face outward — look at passing NPCs or village activity
        if (sitTimer % 60 == 0) {
            List<TownspersonMob> nearby = level.getEntitiesOfClass(
                    TownspersonMob.class,
                    entity.getBoundingBox().inflate(12),
                    mob -> mob != entity
            );
            if (!nearby.isEmpty()) {
                TownspersonMob watch = nearby.get(
                        entity.getRandom().nextInt(nearby.size()));
                entity.getLookControl().setLookAt(watch,
                        30f, 30f);
            }
        }

        // Occasional ambient sound
        if (sitTimer % 200 == 0
                && entity.getRandom().nextInt(3) == 0) {
            level.playSound(null, entity.blockPosition(),
                    SoundEvents.VILLAGER_AMBIENT,
                    SoundSource.NEUTRAL, 0.3f,
                    0.7f + entity.getRandom().nextFloat() * 0.2f);
        }

        if (sitTimer >= SIT_DURATION) {
            // Done sitting — wander a bit
            mode = RelaxMode.WANDERING;
            pickWanderTarget(level);
        }
    }

    private void wander(ServerLevel level) {
        if (!entity.getNavigation().isInProgress()) {
            pickWanderTarget(level);
        }
    }

    private void pickWanderTarget(ServerLevel level) {
        BlockPos home = entity.getHouseId()
                .flatMap(id -> VillageSavedData.get(level).getBuildingById(id))
                .map(b -> b.getShape().getOrigin())
                .orElse(entity.blockPosition());

        BlockPos target = home.offset(
                entity.getRandom().nextInt(WANDER_RADIUS * 2) - WANDER_RADIUS,
                0,
                entity.getRandom().nextInt(WANDER_RADIUS * 2) - WANDER_RADIUS
        );

        entity.getNavigation().moveTo(
                target.getX(), target.getY(), target.getZ(),
                0.4); // very slow walk
    }

    private BlockPos findSitSpot(ServerLevel level) {
        // Look for slabs, stairs, or benches near house
        BlockPos home = entity.getHouseId()
                .flatMap(id -> VillageSavedData.get(level).getBuildingById(id))
                .map(b -> b.getShape().getOrigin())
                .orElse(entity.blockPosition());

        int radius = 12;
        for (BlockPos pos : BlockPos.betweenClosed(
                home.offset(-radius, -2, -radius),
                home.offset(radius, 2, radius))) {
            var state = level.getBlockState(pos);
            if ((state.getBlock() instanceof SlabBlock
                    || state.getBlock() instanceof StairBlock
                    || state.is(BlockTags.WOODEN_SLABS)
                    || state.is(BlockTags.WOODEN_STAIRS))
                    && level.getBlockState(pos.above()).isAir()) {
                return pos.immutable().above();
            }
        }
        return null;
    }

    @Override
    public void stop() {
        entity.getNavigation().stop();
        sitPos = null;
        sitTimer = 0;
    }
}