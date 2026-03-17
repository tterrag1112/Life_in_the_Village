package tterrag1112.life_in_the_village.Entities.Goals.Social;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.properties.BedPart;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;

import java.util.EnumSet;
import java.util.Optional;

public class ReturnHomeGoal extends Goal {

    private static final int    INTERACT_RANGE_SQ = 9;
    private static final int    BED_SEARCH_RADIUS = 2;
    private static final int    STUCK_TIMEOUT     = 200;
    private static final long   NIGHT_START       = 12500L;
    private static final long   NIGHT_END         = 23500L;

    private final TownspersonMob entity;
    private BlockPos targetBed  = null;
    private BlockPos standPos   = null;
    private int stuckTimer      = 0;
    private boolean isSleeping  = false;

    public ReturnHomeGoal(TownspersonMob entity) {
        this.entity = entity;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    // -------------------------------------------------------------------------
    // Goal lifecycle
    // -------------------------------------------------------------------------

    @Override
    public boolean canUse() {
        if (!entity.shouldBeHome()) return false;
        if (entity.getProfession()
                == Profession.ADVENTURER) return false;
        if (!(entity.level() instanceof ServerLevel level)) return false;

        if (entity.hasHome()) {
            if (isAlreadyHome(level) && !isNightTime(level)) return false;
            targetBed = findBedInHouse(level);
            standPos  = targetBed != null
                    ? findStandPosNearBed(targetBed)
                    : getHouseOrigin(level);
            return standPos != null;
        }

        // No house — fall back to town hall
        Building townHall = findTownHall(level);
        if (townHall == null) return false;
        if (townHall.getShape().contains(entity.blockPosition())) return false;

        standPos  = townHall.getShape().getOrigin().offset(
                townHall.getShape().getWidth() / 2, 1,
                townHall.getShape().getLength() / 2);
        targetBed = null;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (!entity.shouldBeHome()) return false;
        if (isSleeping) return isNightTime(
                (ServerLevel) entity.level());
        stuckTimer++;
        if (stuckTimer > STUCK_TIMEOUT) return false;
        return !isAlreadyAtStandPos();
    }

    @Override
    public void start() {
        stuckTimer = 0;
        isSleeping = false;
        entity.setCurrentActivity("Heading home");

        if (standPos != null) {
            entity.getNavigation().moveTo(
                    standPos.getX() + 0.5,
                    standPos.getY(),
                    standPos.getZ() + 0.5,
                    1.0);
        }
    }

    @Override
    public boolean requiresUpdateEveryTick() { return true; }

    @Override
    public void tick() {
        if (!(entity.level() instanceof ServerLevel level)) return;

        // -------------------------------------------------------
        // Sleeping phase — wake up when morning comes
        // -------------------------------------------------------
        if (isSleeping) {
            if (!isNightTime(level)) {
                wakeUp();
            }
            return;
        }

        // -------------------------------------------------------
        // Navigation phase
        // -------------------------------------------------------
        if (standPos == null) return;

        if (isAlreadyAtStandPos()) {
            entity.getNavigation().stop();

            // Face the bed
            if (targetBed != null) {
                entity.getLookControl().setLookAt(
                        targetBed.getX(),
                        targetBed.getY(),
                        targetBed.getZ());
            }

            // Try to sleep if it's night and we have a bed
            if (isNightTime(level) && targetBed != null
                    && !isSleeping) {
                trySleep();
            }
            return;
        }

        if (!entity.getNavigation().isInProgress()) {
            entity.getNavigation().moveTo(
                    standPos.getX() + 0.5,
                    standPos.getY(),
                    standPos.getZ() + 0.5,
                    1.0);
        }
    }

    @Override
    public void stop() {
        if (isSleeping) wakeUp();
        entity.getNavigation().stop();
        targetBed  = null;
        standPos   = null;
        stuckTimer = 0;
        entity.clearCurrentActivity();
    }

    // -------------------------------------------------------------------------
    // Sleep helpers
    // -------------------------------------------------------------------------

    private void trySleep() {
        if (targetBed == null) return;
        if (!(entity.level() instanceof ServerLevel level)) return;

        // Verify the bed block is still there
        var state = level.getBlockState(targetBed);
        if (!state.is(BlockTags.BEDS)) return;

        entity.startSleeping(targetBed);
        isSleeping = true;
        entity.setCurrentActivity("Sleeping");
    }

    private void wakeUp() {
        entity.stopSleeping();
        isSleeping = false;
        entity.clearCurrentActivity();
    }

    private boolean isNightTime(ServerLevel level) {
        long time = level.getDayTime() % 24000L;
        return time >= NIGHT_START && time <= NIGHT_END;
    }

    // -------------------------------------------------------------------------
    // Existing helpers — unchanged
    // -------------------------------------------------------------------------

    private boolean isAlreadyHome(ServerLevel level) {
        if (entity.hasHome()) {
            return getHouseBuilding(level)
                    .map(b -> b.getShape().contains(
                            entity.blockPosition()))
                    .orElse(false);
        }
        Building townHall = findTownHall(level);
        return townHall != null
                && townHall.getShape().contains(
                entity.blockPosition());
    }

    private boolean isAlreadyAtStandPos() {
        if (standPos == null) return false;
        return entity.distanceToSqr(
                standPos.getX() + 0.5,
                standPos.getY(),
                standPos.getZ() + 0.5) < 2.25;
    }

    private BlockPos findBedInHouse(ServerLevel level) {
        return getHouseBuilding(level).map(building -> {
            BlockPos min = building.getShape().getMin();
            BlockPos max = building.getShape().getMax();
            for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
                var state = level.getBlockState(pos);
                if (state.is(BlockTags.BEDS)
                        && state.getBlock() instanceof BedBlock
                        && state.getValue(BedBlock.PART)
                        == BedPart.HEAD) {
                    return pos.immutable();
                }
            }
            return null;
        }).orElse(null);
    }

    private BlockPos findStandPosNearBed(BlockPos bedHead) {
        for (BlockPos candidate : BlockPos.betweenClosed(
                bedHead.offset(-1, 0, -1),
                bedHead.offset(1, 0, 1))) {
            if (candidate.equals(bedHead)) continue;
            if (entity.level().getBlockState(candidate).isAir()
                    && entity.level().getBlockState(
                    candidate.above()).isAir()) {
                return candidate.immutable();
            }
        }
        return bedHead.north().immutable();
    }

    private BlockPos getHouseOrigin(ServerLevel level) {
        return getHouseBuilding(level)
                .map(b -> b.getShape().getOrigin())
                .orElse(null);
    }

    private Optional<Building> getHouseBuilding(ServerLevel level) {
        return entity.getHouseId()
                .flatMap(id -> VillageSavedData.get(level)
                        .getBuildingById(id));
    }

    private Building findTownHall(ServerLevel level) {
        return entity.getAssignedVillageName()
                .flatMap(name -> VillageSavedData.get(level)
                        .getVillageByName(name))
                .flatMap(village -> village.getBuildingIds().stream()
                        .map(id -> VillageSavedData.get(level)
                                .getBuildingById(id))
                        .filter(Optional::isPresent)
                        .map(Optional::get)
                        .filter(b -> b.getType()
                                == BuildingType.TOWN_HALL)
                        .findFirst())
                .orElse(null);
    }
}