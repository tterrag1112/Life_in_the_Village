package tterrag1112.life_in_the_village.Entities.Goals.Social;

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
        // Phase 6.3.4.8.1 — no flags. The goal does pathfind via
        // entity.getNavigation().moveTo() in start(), but holding the
        // MOVE flag indefinitely while wander runs blocks
        // BrainNavGuard.canSteerNavigation for any production / sell /
        // buy Brain behavior that wants nav. Mirrors the
        // NpcRandomLookAroundGoal precedent from 6.3.4.5.1.
        //
        // Coordination still works: canUse self-gates on
        // !nav.isInProgress() so the wander won't start while another
        // behavior is steering. Once a wander is running and a Brain
        // behavior wants to claim nav, BrainNavGuard's stale-nav
        // escape hatch (NAV_BUSY_SINCE, STALE_NAV_TICKS ≈ 10s)
        // force-stops the nav and the wander naturally ends —
        // canContinueToUse returns false the moment nav stops.
        //
        // Vanilla GoalSelector preemption-via-flag is unused since
        // no other goal in our registration competes for nav at
        // P_IDLE / P_AMBIENT priority.
        setFlags(EnumSet.noneOf(Flag.class));
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
