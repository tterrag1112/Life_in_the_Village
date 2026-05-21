package tterrag1112.life_in_the_village.Npc.Brain.Behaviors;

import com.google.common.collect.ImmutableMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.properties.BedPart;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Brain.BrainNavGuard;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;

import java.util.Optional;

/**
 * Phase 6.2.c — migrated from {@code ReturnHomeGoal}. First REST-activity
 * behavior. REST @ 1.
 *
 * <p>Walks the NPC home (or to the village's town hall if homeless),
 * faces the bed, calls {@code entity.startSleeping(bed)} when night.
 * Wakes up when morning arrives.
 *
 * <p>Post-arrival action preserved verbatim: bed lookup via
 * {@link BlockTags#BEDS} + {@code BedBlock.PART == HEAD}, and the
 * actual sleep transition via {@code entity.startSleeping(targetBed)}
 * / {@code entity.stopSleeping()}. No new Pose or memory writes —
 * vanilla sleep handles the Pose.SLEEPING transition internally.
 */
public class ReturnHomeBehavior extends Behavior<TownspersonMob> {

    private static final int STUCK_TIMEOUT = 200;
    private static final long NIGHT_START = 12500L;
    private static final long NIGHT_END = 23500L;
    private static final double ARRIVAL_DIST_SQ = 2.25;
    private static final float WALK_SPEED = 1.0f;
    private static final int CLOSE_ENOUGH = 1;
    private static final int MAX_RUN = 24000;

    private BlockPos targetBed;
    private BlockPos standPos;
    private int stuckTimer;
    private boolean isSleeping;

    public ReturnHomeBehavior() {
        super(ImmutableMap.of(
                MemoryModuleType.WALK_TARGET, MemoryStatus.VALUE_ABSENT
        ), MAX_RUN);
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, TownspersonMob entity) {
        if (!entity.shouldBeHome()) return false;
        if (entity.getProfession() == Profession.ADVENTURER) return false;
        if (!BrainNavGuard.canSteerNavigation(entity)) return false;

        if (entity.hasHome()) {
            if (isAlreadyHome(level, entity) && !isNightTime(level)) return false;
            targetBed = findBedInHouse(level, entity);
            standPos = targetBed != null
                    ? findStandPosNearBed(level, targetBed)
                    : getHouseOrigin(level, entity);
            return standPos != null;
        }
        // No house — fall back to town hall.
        Building townHall = findTownHall(level, entity);
        if (townHall == null) return false;
        if (townHall.getShape().contains(entity.blockPosition())) return false;
        standPos = townHall.getShape().getOrigin().offset(
                townHall.getShape().getWidth() / 2, 1,
                townHall.getShape().getLength() / 2);
        targetBed = null;
        return true;
    }

    @Override
    protected boolean canStillUse(ServerLevel level, TownspersonMob entity, long gameTime) {
        if (!entity.shouldBeHome()) return false;
        if (isSleeping) return isNightTime(level);
        stuckTimer++;
        if (stuckTimer > STUCK_TIMEOUT) return false;
        return !isAlreadyAtStandPos(entity);
    }

    @Override
    protected void start(ServerLevel level, TownspersonMob entity, long gameTime) {
        stuckTimer = 0;
        isSleeping = false;
        entity.setCurrentActivity("Heading home");
        if (standPos != null) {
            entity.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                    new WalkTarget(standPos, WALK_SPEED, CLOSE_ENOUGH));
        }
    }

    @Override
    protected void tick(ServerLevel level, TownspersonMob entity, long gameTime) {
        if (isSleeping) {
            if (!isNightTime(level)) wakeUp(entity);
            return;
        }
        if (standPos == null) return;

        if (isAlreadyAtStandPos(entity)) {
            entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            if (targetBed != null) {
                entity.getLookControl().setLookAt(
                        targetBed.getX(), targetBed.getY(), targetBed.getZ());
            }
            if (isNightTime(level) && targetBed != null && !isSleeping) {
                trySleep(level, entity);
            }
            return;
        }
        // Refresh WALK_TARGET if it dropped.
        entity.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                new WalkTarget(standPos, WALK_SPEED, CLOSE_ENOUGH));
    }

    @Override
    protected void stop(ServerLevel level, TownspersonMob entity, long gameTime) {
        if (isSleeping) wakeUp(entity);
        entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        targetBed = null;
        standPos = null;
        stuckTimer = 0;
        entity.clearCurrentActivity();
    }

    // ── Sleep / time helpers (verbatim from ReturnHomeGoal) ─────────────────

    private void trySleep(ServerLevel level, TownspersonMob entity) {
        if (targetBed == null) return;
        var state = level.getBlockState(targetBed);
        if (!state.is(BlockTags.BEDS)) return;
        entity.startSleeping(targetBed);
        isSleeping = true;
        entity.setCurrentActivity("Sleeping");
    }

    private void wakeUp(TownspersonMob entity) {
        entity.stopSleeping();
        isSleeping = false;
        entity.clearCurrentActivity();
    }

    private static boolean isNightTime(ServerLevel level) {
        long time = level.getDayTime() % 24000L;
        return time >= NIGHT_START && time <= NIGHT_END;
    }

    private boolean isAlreadyAtStandPos(TownspersonMob entity) {
        if (standPos == null) return false;
        return entity.distanceToSqr(
                standPos.getX() + 0.5, standPos.getY(), standPos.getZ() + 0.5)
                < ARRIVAL_DIST_SQ;
    }

    private static boolean isAlreadyHome(ServerLevel level, TownspersonMob entity) {
        if (entity.hasHome()) {
            return getHouseBuilding(level, entity)
                    .map(b -> b.getShape().contains(entity.blockPosition()))
                    .orElse(false);
        }
        Building townHall = findTownHall(level, entity);
        return townHall != null && townHall.getShape().contains(entity.blockPosition());
    }

    private static BlockPos findBedInHouse(ServerLevel level, TownspersonMob entity) {
        return getHouseBuilding(level, entity).map(building -> {
            BlockPos min = building.getShape().getMin();
            BlockPos max = building.getShape().getMax();
            for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
                var state = level.getBlockState(pos);
                if (state.is(BlockTags.BEDS)
                        && state.getBlock() instanceof BedBlock
                        && state.getValue(BedBlock.PART) == BedPart.HEAD) {
                    return pos.immutable();
                }
            }
            return null;
        }).orElse(null);
    }

    private static BlockPos findStandPosNearBed(ServerLevel level, BlockPos bedHead) {
        for (BlockPos candidate : BlockPos.betweenClosed(
                bedHead.offset(-1, 0, -1), bedHead.offset(1, 0, 1))) {
            if (candidate.equals(bedHead)) continue;
            if (level.getBlockState(candidate).isAir()
                    && level.getBlockState(candidate.above()).isAir()) {
                return candidate.immutable();
            }
        }
        return bedHead.north().immutable();
    }

    private static BlockPos getHouseOrigin(ServerLevel level, TownspersonMob entity) {
        return getHouseBuilding(level, entity)
                .map(b -> b.getShape().getOrigin())
                .orElse(null);
    }

    private static Optional<Building> getHouseBuilding(ServerLevel level, TownspersonMob entity) {
        return entity.getHouseId()
                .flatMap(id -> VillageSavedData.get(level).getBuildingById(id));
    }

    private static Building findTownHall(ServerLevel level, TownspersonMob entity) {
        return entity.getAssignedVillageName()
                .flatMap(name -> VillageSavedData.get(level).getVillageByName(name))
                .flatMap(village -> village.getBuildingIds().stream()
                        .map(id -> VillageSavedData.get(level).getBuildingById(id))
                        .filter(Optional::isPresent)
                        .map(Optional::get)
                        .filter(b -> b.getType() == BuildingType.TOWN_HALL)
                        .findFirst())
                .orElse(null);
    }
}
