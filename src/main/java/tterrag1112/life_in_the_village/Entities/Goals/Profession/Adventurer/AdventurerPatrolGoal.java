package tterrag1112.life_in_the_village.Entities.Goals.Profession.Adventurer;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Guilds.Adventurer.Adventurers.AdventurerGroup;
import tterrag1112.life_in_the_village.Guilds.Adventurer.Adventurers.AdventurerSavedData;

import java.util.EnumSet;
import java.util.UUID;

public class AdventurerPatrolGoal extends Goal {

    private static final double MOVE_SPEED        = 0.6D;
    private static final int    REACHED_THRESHOLD = 3;   // blocks
    private static final int    RECHECK_INTERVAL  = 100; // ticks

    private final TownspersonMob entity;
    private BlockPos destination;
    private int recheckTimer = 0;

    public AdventurerPatrolGoal(TownspersonMob entity) {
        this.entity = entity;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        // Only applies to group members
        return entity.getGroupId().isPresent();
    }

    @Override
    public boolean canContinueToUse() {
        return entity.getGroupId().isPresent()
                && !entity.getNavigation().isDone();
    }

    @Override
    public void start() {
        updateDestination();
    }

    @Override
    public void stop() {
        entity.getNavigation().stop();
        destination = null;
    }

    @Override
    public void tick() {
        recheckTimer++;

        if (destination == null
                || entity.blockPosition().closerThan(destination,
                REACHED_THRESHOLD)) {
            updateDestination();
            return;
        }

        // Periodically recheck in case the group moved significantly
        if (recheckTimer >= RECHECK_INTERVAL) {
            recheckTimer = 0;
            updateDestination();
        }
    }

    private void updateDestination() {
        if (!(entity.level() instanceof ServerLevel level)) return;

        UUID groupId = entity.getGroupId().orElse(null);
        if (groupId == null) return;

        AdventurerSavedData data = AdventurerSavedData.get(level);
        AdventurerGroup group = data.getGroup(groupId).orElse(null);
        if (group == null) return;

        // If a player is leading, follow them instead of group position
        if (group.hasPlayerLeader()) {
            group.getJoinedPlayerId()
                    .map(id -> level.getServer()
                            .getPlayerList().getPlayer(id))
                    .ifPresent(leadingPlayer -> {
                        destination = getOffsetDestination(
                                leadingPlayer.blockPosition());
                        entity.getNavigation().moveTo(
                                destination.getX() + 0.5,
                                destination.getY(),
                                destination.getZ() + 0.5,
                                MOVE_SPEED);
                    });
            return;
        }

        // Otherwise follow group position as normal
        destination = getOffsetDestination(group.getCurrentPos());
        entity.getNavigation().moveTo(
                destination.getX() + 0.5,
                destination.getY(),
                destination.getZ() + 0.5,
                MOVE_SPEED);
    }

    /**
     * Offsets the destination slightly per entity so group members
     * spread out naturally rather than stacking on the same block.
     */
    private BlockPos getOffsetDestination(BlockPos groupPos) {
        int offsetX = (int)(entity.getUUID()
                .getMostSignificantBits() % 5);
        int offsetZ = (int)(entity.getUUID()
                .getLeastSignificantBits() % 5);
        return groupPos.offset(offsetX, 0, offsetZ);
    }
}
