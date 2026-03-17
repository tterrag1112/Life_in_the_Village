package tterrag1112.life_in_the_village.Entities.Goals.Social;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import tterrag1112.life_in_the_village.Entities.FamilyRole;
import tterrag1112.life_in_the_village.Entities.LifeStage;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.Optional;

public class SeekHouseGoal extends Goal {

    private static final int CHECK_INTERVAL = 2400;
    private static final int WALK_TIMEOUT = 600;

    private final TownspersonMob entity;
    private int checkTimer = 0;
    private int walkTimer = 0;
    private Building targetHouse = null;

    public SeekHouseGoal(TownspersonMob entity) {
        this.entity = entity;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (entity.hasHome()) return false;
        if (entity.getLifeStage() == LifeStage.CHILD) return false;

        checkTimer++;
        if (checkTimer < CHECK_INTERVAL) return false;
        checkTimer = 0;

        if (!(entity.level() instanceof ServerLevel level)) return false;

        VillageSavedData data = VillageSavedData.get(level);
        Optional<Village> village = findNearbyVillage(level, data);
        if (village.isEmpty()) return false;

        targetHouse = findEmptyHouse(level, data, village.get());
        return targetHouse != null;
    }

    @Override
    public void start() {
        walkTimer = 0;
        if (targetHouse != null) {
            BlockPos origin = targetHouse.getShape().getOrigin();
            entity.getNavigation().moveTo(
                    origin.getX(), origin.getY(), origin.getZ(), 1.0
            );
        }
    }

    @Override
    public boolean canContinueToUse() {
        return !entity.hasHome() && walkTimer < WALK_TIMEOUT && targetHouse != null;
    }

    @Override
    public boolean requiresUpdateEveryTick() { return true; }

    @Override
    public void tick() {
        if (targetHouse == null) return;
        walkTimer++;

        BlockPos target = targetHouse.getShape().getOrigin();
        double distSq = entity.distanceToSqr(
                target.getX(), target.getY(), target.getZ()
        );

        if (distSq > 9) {
            if (!entity.getNavigation().isInProgress()) {
                entity.getNavigation().moveTo(
                        target.getX(), target.getY(), target.getZ(), 1.0
                );
            }
            return;
        }

        // Arrived — claim the house
        entity.getNavigation().stop();

        if (!(entity.level() instanceof ServerLevel level)) return;
        VillageSavedData data = VillageSavedData.get(level);

        // Re-check it's still empty (another NPC may have taken it)
        if (isHouseEmpty(level, targetHouse)) {
            entity.setHouseId(targetHouse.getId());
            entity.setFamilyRole(FamilyRole.HEAD);
            entity.setAssignedVillageName(
                    data.getVillageAt(targetHouse.getShape().getOrigin())
                            .map(Village::getName).orElse("")
            );
            System.out.println("NPC " + entity.getNpcName()
                    + " claimed house " + targetHouse.getName());
        }

        targetHouse = null;
    }

    @Override
    public void stop() {
        entity.getNavigation().stop();
        targetHouse = null;
        walkTimer = 0;
    }

    private Building findEmptyHouse(ServerLevel level,
                                    VillageSavedData data, Village village) {
        return village.getBuildingIds().stream()
                .map(data::getBuildingById)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .filter(b -> b.getType() == BuildingType.HOUSE
                        || b.getType() == BuildingType.FARMHOUSE)
                .filter(b -> isHouseEmpty(level, b))
                .min(Comparator.comparingDouble(b ->
                        entity.distanceToSqr(
                                b.getShape().getOrigin().getX(),
                                b.getShape().getOrigin().getY(),
                                b.getShape().getOrigin().getZ()
                        )
                ))
                .orElse(null);
    }

    private boolean isHouseEmpty(ServerLevel level, Building house) {
        return level.getEntitiesOfClass(TownspersonMob.class,
                house.getShape().toAABB().inflate(4),
                mob -> house.getId().equals(mob.getHouseId().orElse(null))
                        && mob.getFamilyRole() == FamilyRole.HEAD
        ).isEmpty();
    }

    private Optional<Village> findNearbyVillage(ServerLevel level,
                                                VillageSavedData data) {
        return data.getAllVillages().stream()
                .filter(v -> v.getBounds(data)
                        .map(b -> b.inflate(32).contains(
                                entity.getX(), entity.getY(), entity.getZ()
                        ))
                        .orElse(false)
                )
                .findFirst();
    }
}
