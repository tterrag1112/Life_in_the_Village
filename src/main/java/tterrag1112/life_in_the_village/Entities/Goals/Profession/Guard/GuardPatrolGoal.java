package tterrag1112.life_in_the_village.Entities.Goals.Profession.Guard;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.AABB;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.EnumSet;
import java.util.Optional;

public class GuardPatrolGoal extends Goal {

    private final TownspersonMob entity;
    private int reassignTimer = 0;
    private static final int REASSIGN_INTERVAL = 600; // check every 30 seconds
    private static final int POST_PATROL_RADIUS = 8;

    public GuardPatrolGoal(TownspersonMob entity) {
        this.entity = entity;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (entity.getNavigation().isInProgress()) return false;
        return entity.getRandom().nextInt(40) == 0;
    }

    @Override
    public boolean canContinueToUse() {
        return entity.getNavigation().isInProgress();
    }

    @Override
    public void start() {
        if (!(entity.level() instanceof ServerLevel serverLevel)) return;

        // Periodically reassign post status
        reassignTimer++;
        if (reassignTimer >= REASSIGN_INTERVAL) {
            reassignTimer = 0;
            reassignPost(serverLevel);
        }

        // Navigate based on current assignment
        if (entity.getAssignedPost().isPresent()) {
            patrolAroundPost(entity.getAssignedPost().get());
        } else {
            patrolVillage(serverLevel);
        }
    }

    private void reassignPost(ServerLevel level) {
        Optional<Village> village = entity.getAssignedVillageName()
                .flatMap(name -> VillageSavedData.get(level).getVillageByName(name));

        if (village.isEmpty()) return;

        Optional<BlockPos> post = village.get().assignGuardPost(level, entity.getUUID());
        entity.setAssignedPost(post.orElse(null));
    }

    private void patrolAroundPost(BlockPos post) {
        // Pick random point within POST_PATROL_RADIUS of the post
        int attempts = 10;
        while (attempts-- > 0) {
            int dx = entity.getRandom().nextInt(POST_PATROL_RADIUS * 2) - POST_PATROL_RADIUS;
            int dz = entity.getRandom().nextInt(POST_PATROL_RADIUS * 2) - POST_PATROL_RADIUS;
            double x = post.getX() + dx;
            double z = post.getZ() + dz;

            // Don't wander too far from post
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist <= POST_PATROL_RADIUS) {
                entity.getNavigation().moveTo(x, post.getY(), z, 1.0);
                return;
            }
        }
    }

    private void patrolVillage(ServerLevel level) {
        Optional<Village> village = entity.getAssignedVillageName()
                .flatMap(name -> VillageSavedData.get(level).getVillageByName(name));

        if (village.isEmpty()) return;

        Optional<AABB> bounds = village.get().getBounds(VillageSavedData.get(level));
        if (bounds.isEmpty()) return;

        AABB area = bounds.get();
        int attempts = 10;
        while (attempts-- > 0) {
            double x = area.minX + entity.getRandom().nextDouble() * (area.maxX - area.minX);
            double z = area.minZ + entity.getRandom().nextDouble() * (area.maxZ - area.minZ);
            entity.getNavigation().moveTo(x, entity.getY(), z, 1.0);
            return;
        }
    }
}
