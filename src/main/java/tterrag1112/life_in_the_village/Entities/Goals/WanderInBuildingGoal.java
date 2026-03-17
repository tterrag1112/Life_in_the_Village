package tterrag1112.life_in_the_village.Entities.Goals;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.phys.Vec3;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Village.Building;

import java.util.EnumSet;
import java.util.Optional;

public class WanderInBuildingGoal extends Goal {

    private final TownspersonMob entity;
    private Vec3 targetPos;

    public WanderInBuildingGoal(TownspersonMob entity) {
        this.entity = entity;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        // Only wander if not already moving toward something
        if (entity.getNavigation().isInProgress()) return false;
        return entity.getRandom().nextInt(120) == 0;
    }

    @Override
    public boolean canContinueToUse() {
        return entity.getNavigation().isInProgress();
    }

    @Override
    public void start() {
        if (!(entity.level() instanceof ServerLevel serverLevel)) return;

        Optional<Building> building = entity.getAssignedBuilding(serverLevel);

        if (building.isPresent()) {
            // Pick a random position within the building bounds
            BlockPos min = building.get().getShape().getMin();
            BlockPos max = building.get().getShape().getMax();

            int attempts = 10;
            while (attempts-- > 0) {
                int x = min.getX() + entity.getRandom().nextInt(max.getX() - min.getX() + 1);
                int z = min.getZ() + entity.getRandom().nextInt(max.getZ() - min.getZ() + 1);
                BlockPos target = new BlockPos(x, entity.blockPosition().getY(), z);

                if (building.get().getShape().contains(target)) {
                    entity.getNavigation().moveTo(x, entity.getY(), z, 1.0);
                    return;
                }
            }
        } else {
            // No building assigned — fall back to short-range wander
            Vec3 randomPos = DefaultRandomPos.getPos(entity, 10, 7);
            if (randomPos != null) {
                entity.getNavigation().moveTo(randomPos.x, randomPos.y, randomPos.z, 1.0);
            }
        }
    }
}
