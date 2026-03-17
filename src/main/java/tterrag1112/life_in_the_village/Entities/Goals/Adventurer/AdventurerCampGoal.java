package tterrag1112.life_in_the_village.Entities.Goals.Adventurer;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Guild.Adventurers.AdventurerGroup;
import tterrag1112.life_in_the_village.Guild.Adventurers.AdventurerSavedData;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class AdventurerCampGoal extends Goal {

    // How long before NPCs start sleeping — 3 in-game minutes
    private static final long SLEEP_AFTER_TICKS   = 3600L;
    // How often to eat — every 2 minutes
    private static final long EAT_INTERVAL        = 2400L;
    // How often the watchman looks around
    private static final int  WATCH_LOOK_INTERVAL = 60;

    private final TownspersonMob entity;
    private BlockPos campfirePos        = null;
    private UUID seatEntityId           = null;
    private boolean isSitting           = false;
    private boolean isSleeping          = false;
    private boolean isWatchman          = false;
    private long campStartTick          = -1L;
    private long lastEatTick            = -1L;
    private int watchLookTimer          = 0;
    private float watchLookAngle        = 0f;

    public AdventurerCampGoal(TownspersonMob entity) {
        this.entity = entity;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP));
    }

    // -------------------------------------------------------------------------
    // Goal lifecycle
    // -------------------------------------------------------------------------

    @Override
    public boolean canUse() {
        if (!entity.isAdventurerGroupMember()) return false;
        return getGroup()
                .map(g -> g.getState()
                        == AdventurerGroup.GroupState.CAMPING)
                .orElse(false);
    }

    @Override
    public boolean canContinueToUse() {
        return getGroup()
                .map(g -> g.getState()
                        == AdventurerGroup.GroupState.CAMPING)
                .orElse(false);
    }

    @Override
    public void start() {
        if (!(entity.level() instanceof ServerLevel level)) return;

        AdventurerGroup group = getGroup().orElse(null);
        if (group == null) return;

        campStartTick = level.getGameTime();
        lastEatTick   = campStartTick;

        // Determine if this NPC is the watchman
        // Rotate by using group's stateChangeTick mod member count
        int memberCount = group.getMemberIds().size();
        int watchIndex  = (int)(group.getStateChangeTick()
                % memberCount);
        UUID watchmanId = group.getMemberIds().get(watchIndex);
        isWatchman = entity.getGroupId().isPresent()
                && group.getMemberIds().indexOf(
                getMemberIdForEntity()) == watchIndex;

        // Place campfire if this is the leader and none exists
        if (entity.isGroupLeader()) {
            placeCampfire(level, group);
        }

        // Sit down around campfire
        if (!isWatchman) {
            sitAroundFire(level, group);
        } else {
            entity.setCurrentActivity("Keeping watch...");
        }
    }

    @Override
    public void stop() {
        if (!(entity.level() instanceof ServerLevel level)) return;

        // Stand up
        if (isSitting) standUp(level);
        if (isSleeping) wakeUp();

        // Remove campfire if leader
        if (entity.isGroupLeader() && campfirePos != null) {
            removeCampfire(level);
        }

        campfirePos  = null;
        isSitting    = false;
        isSleeping   = false;
        campStartTick = -1L;
        entity.clearCurrentActivity();
    }

    @Override
    public void tick() {
        if (!(entity.level() instanceof ServerLevel level)) return;

        long currentTick = level.getGameTime();
        long elapsed     = currentTick - campStartTick;

        if (isWatchman) {
            tickWatchman(level, currentTick);
        } else {
            tickCamper(level, currentTick, elapsed);
        }
    }

    // -------------------------------------------------------------------------
    // Watchman behavior
    // -------------------------------------------------------------------------

    private void tickWatchman(ServerLevel level, long currentTick) {
        // Slowly look around scanning for threats
        watchLookTimer++;
        if (watchLookTimer >= WATCH_LOOK_INTERVAL) {
            watchLookTimer = 0;
            watchLookAngle += 45f;
            if (watchLookAngle >= 360f) watchLookAngle = 0f;

            double lookX = entity.getX()
                    + Math.cos(Math.toRadians(watchLookAngle)) * 8;
            double lookZ = entity.getZ()
                    + Math.sin(Math.toRadians(watchLookAngle)) * 8;
            entity.getLookControl().setLookAt(
                    lookX, entity.getEyeY(), lookZ);
        }

        // Watchman eats too
        tickEat(level, currentTick);

        entity.setCurrentActivity("Keeping watch...");
    }

    // -------------------------------------------------------------------------
    // Camper behavior
    // -------------------------------------------------------------------------

    private void tickCamper(ServerLevel level,
                            long currentTick, long elapsed) {
        // Face the campfire
        if (campfirePos != null) {
            entity.getLookControl().setLookAt(
                    campfirePos.getX() + 0.5,
                    campfirePos.getY() + 0.5,
                    campfirePos.getZ() + 0.5);
        }

        // Start sleeping after threshold
        if (!isSleeping && elapsed >= SLEEP_AFTER_TICKS) {
            startSleeping(level);
            return;
        }

        // Eat periodically while sitting
        if (!isSleeping) {
            tickEat(level, currentTick);
            entity.setCurrentActivity("Resting...");
        }
    }

    // -------------------------------------------------------------------------
    // Eating
    // -------------------------------------------------------------------------

    private void tickEat(ServerLevel level, long currentTick) {
        if (currentTick - lastEatTick < EAT_INTERVAL) return;
        lastEatTick = currentTick;

        // Play eat animation using cooked beef
        ItemStack food = new ItemStack(Items.COOKED_BEEF);
        entity.startUsingItem(
                entity.getRandom().nextBoolean()
                        ? net.minecraft.world.InteractionHand.MAIN_HAND
                        : net.minecraft.world.InteractionHand.OFF_HAND);
        entity.setItemInHand(
                net.minecraft.world.InteractionHand.MAIN_HAND, food);

        // Clear item after 40 ticks via a delayed task
        level.getServer().execute(() -> {
            if (entity.isAlive()) {
                entity.stopUsingItem();
                entity.setItemInHand(
                        net.minecraft.world.InteractionHand.MAIN_HAND,
                        ItemStack.EMPTY);
            }
        });
    }

    // -------------------------------------------------------------------------
    // Sitting via armor stand passenger
    // -------------------------------------------------------------------------

    private void sitAroundFire(ServerLevel level,
                               AdventurerGroup group) {
        if (campfirePos == null) return;

        // Calculate offset position around fire based on member index
        int index = group.getMemberIds().indexOf(getMemberIdForEntity());
        double angle = (index / (double) group.getMemberCount())
                * Math.PI * 2;
        double radius = 2.0;

        double seatX = campfirePos.getX() + 0.5
                + Math.cos(angle) * radius;
        double seatZ = campfirePos.getZ() + 0.5
                + Math.sin(angle) * radius;
        int seatY = level.getHeight(
                net.minecraft.world.level.levelgen.Heightmap.Types
                        .MOTION_BLOCKING_NO_LEAVES,
                (int) seatX, (int) seatZ);

        // Spawn invisible armor stand as seat
        Marker seat = new Marker(EntityType.MARKER, level);
        seat.setPos(seatX, seatY - 1.0, seatZ);
        seat.setSilent(true);
        seat.setNoGravity(true);
        level.addFreshEntity(seat);

// Mount entity on marker
        entity.startRiding(seat, true, true);
        seatEntityId = seat.getUUID();
        isSitting    = true;

        entity.setCurrentActivity("Resting...");
    }

    private void standUp(ServerLevel level) {
        entity.stopRiding();
        isSitting = false;

        // Remove the seat armor stand
        if (seatEntityId != null) {
            var seat = level.getEntity(seatEntityId);
            if (seat != null) seat.discard();
            seatEntityId = null;
        }
    }

    // -------------------------------------------------------------------------
    // Sleeping
    // -------------------------------------------------------------------------

    private void startSleeping(ServerLevel level) {
        // Stand up from sitting first
        if (isSitting) standUp(level);

        // Use Minecraft's sleep pose
        if (campfirePos != null) {
            entity.startSleeping(campfirePos.relative(
                    net.minecraft.core.Direction.NORTH, 2));
        }
        isSleeping = true;
        entity.setCurrentActivity("Sleeping...");
    }

    private void wakeUp() {
        entity.stopSleeping();
        isSleeping = false;
    }

    // -------------------------------------------------------------------------
    // Campfire placement
    // -------------------------------------------------------------------------

    private void placeCampfire(ServerLevel level,
                               AdventurerGroup group) {
        // Find a safe flat spot near the group position
        BlockPos base = findSafeCampSpot(level,
                group.getCurrentPos());
        if (base == null) return;

        // Only place if the spot is clear
        BlockState current = level.getBlockState(base);
        if (!current.isAir() && !current.canBeReplaced()) return;

        level.setBlock(base,
                Blocks.CAMPFIRE.defaultBlockState(), 3);
        campfirePos = base;

        // Store on group so other members know where it is
        group.setCampfirePos(base);
    }

    private void removeCampfire(ServerLevel level) {
        if (campfirePos == null) return;
        BlockState state = level.getBlockState(campfirePos);
        if (state.is(Blocks.CAMPFIRE)) {
            level.setBlock(campfirePos, Blocks.AIR.defaultBlockState(), 3);
        }

        // Clear from group
        getGroup().ifPresent(g -> g.setCampfirePos(null));
        campfirePos = null;
    }

    private BlockPos findSafeCampSpot(ServerLevel level,
                                      BlockPos origin) {
        // Check in a spiral pattern for a safe flat spot
        for (int r = 0; r <= 8; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) != r && Math.abs(dz) != r) continue;

                    int x = origin.getX() + dx;
                    int z = origin.getZ() + dz;
                    int y = level.getHeight(
                            net.minecraft.world.level.levelgen.Heightmap
                                    .Types.MOTION_BLOCKING_NO_LEAVES,
                            x, z);

                    BlockPos candidate = new BlockPos(x, y, z);
                    BlockPos below     = candidate.below();

                    // Must have solid ground and clear air above
                    if (level.getBlockState(below).isSolidRender()
                            && level.getBlockState(candidate).isAir()
                            && isSafeFromMobs(level, candidate)) {
                        return candidate;
                    }
                }
            }
        }
        return null;
    }

    private boolean isSafeFromMobs(ServerLevel level, BlockPos pos) {
        // Check no hostile mobs within 16 blocks
        var hostile = level.getNearbyEntities(
                net.minecraft.world.entity.monster.Monster.class,
                net.minecraft.world.entity.ai.targeting
                        .TargetingConditions.forNonCombat(),
                entity,
                entity.getBoundingBox().inflate(16));
        return hostile.isEmpty();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Optional<AdventurerGroup> getGroup() {
        if (!(entity.level() instanceof ServerLevel level)) {
            return Optional.empty();
        }
        return entity.getGroupId()
                .flatMap(id -> AdventurerSavedData.get(level)
                        .getGroup(id));
    }

    private UUID getMemberIdForEntity() {
        return getGroup()
                .flatMap(g -> g.getMemberIds().stream()
                        .filter(id -> {
                            // Match by spawn order index
                            int idx = g.getMemberIds().indexOf(id);
                            return g.getSpawnedEntityIds().size() > idx
                                    && g.getSpawnedEntityIds().get(idx)
                                    .equals(entity.getUUID());
                        })
                        .findFirst())
                .orElse(entity.getUUID());
    }
}