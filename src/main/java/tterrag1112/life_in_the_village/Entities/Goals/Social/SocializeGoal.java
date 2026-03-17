package tterrag1112.life_in_the_village.Entities.Goals.Social;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

public class SocializeGoal extends Goal {

    private static final int CHECK_INTERVAL = 200;
    private static final int SOCIALIZE_DURATION = 600;
    private static final int INTERACT_RANGE_SQ = 16;

    private final TownspersonMob entity;
    private int checkTimer = 0;
    private int socializeTimer = 0;
    private BlockPos destination = null;
    private TownspersonMob chatTarget = null;

    public SocializeGoal(TownspersonMob entity) {
        this.entity = entity;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (!entity.isSocialTime()) return false;
        if (entity.isChild()) return false; // children don't socialize independently
        checkTimer++;
        if (checkTimer < CHECK_INTERVAL) return false;
        checkTimer = 0;
        if (!(entity.level() instanceof ServerLevel level)) return false;

        destination = findSocialLocation(level);
        return destination != null;
    }

    @Override
    public boolean canContinueToUse() {
        if (!entity.isSocialTime()) return false;
        return socializeTimer < SOCIALIZE_DURATION;
    }

    @Override
    public void start() {
        entity.setCurrentActivity("Socializing");

        socializeTimer = 0;
        if (destination != null) {
            entity.getNavigation().moveTo(
                    destination.getX() + 0.5,
                    destination.getY(),
                    destination.getZ() + 0.5,
                    0.8 // slower walk for socializing
            );
        }
    }

    @Override
    public boolean requiresUpdateEveryTick() { return true; }

    @Override
    public void tick() {
        socializeTimer++;

        if (destination == null) return;

        double distSq = entity.distanceToSqr(
                destination.getX(), destination.getY(), destination.getZ()
        );

        if (distSq <= INTERACT_RANGE_SQ) {
            entity.getNavigation().stop();
            lookForChatPartner();
        } else if (!entity.getNavigation().isInProgress()) {
            entity.getNavigation().moveTo(
                    destination.getX() + 0.5,
                    destination.getY(),
                    destination.getZ() + 0.5,
                    0.8
            );
        }
    }

    @Override
    public void stop() {
        entity.getNavigation().stop();
        destination = null;
        chatTarget = null;
        socializeTimer = 0;
        entity.clearCurrentActivity();

    }

    // --- Helpers ---

    private void lookForChatPartner() {
        if (chatTarget != null) {
            // Already chatting — look at them
            entity.getLookControl().setLookAt(
                    chatTarget.getX(), chatTarget.getY() + chatTarget.getEyeHeight(),
                    chatTarget.getZ()
            );
            return;
        }

        // Find a nearby townsperson to chat with
        List<TownspersonMob> nearby = entity.level().getEntitiesOfClass(
                TownspersonMob.class,
                entity.getBoundingBox().inflate(6),
                mob -> mob != entity && !mob.isChild()
        );

        if (!nearby.isEmpty()) {
            chatTarget = nearby.get(entity.getRandom().nextInt(nearby.size()));
        }
    }

    private BlockPos findSocialLocation(ServerLevel level) {
        return entity.getAssignedVillageName()
                .flatMap(name -> VillageSavedData.get(level).getVillageByName(name))
                .flatMap(village -> {
                    // Find INN or GUILD_HALL in the village
                    return village.getBuildingIds().stream()
                            .map(id -> VillageSavedData.get(level).getBuildingById(id))
                            .filter(Optional::isPresent)
                            .map(Optional::get)
                            .filter(b -> b.getType() == BuildingType.INN
                                    || b.getType() == BuildingType.GUILD_HALL)
                            .findFirst()
                            .map(b -> b.getShape().getOrigin());
                })
                .orElseGet(() -> {
                    // No social building — wander near village center
                    return entity.getAssignedVillageName()
                            .flatMap(name -> VillageSavedData.get(level).getVillageByName(name))
                            .flatMap(v -> v.getBounds(VillageSavedData.get(level)))
                            .map(bounds -> {
                                // Pick a random point within village bounds
                                int x = (int)(bounds.minX + entity.getRandom().nextDouble()
                                        * (bounds.maxX - bounds.minX));
                                int z = (int)(bounds.minZ + entity.getRandom().nextDouble()
                                        * (bounds.maxZ - bounds.minZ));
                                return new BlockPos(x, (int)bounds.minY, z);
                            })
                            .orElse(null);
                });
    }
}
