// src/main/java/tterrag1112/life_in_the_village/Entities/Goals/Profession/Adventurer/PartyCampGoal.java
package tterrag1112.life_in_the_village.Entities.Goals.Profession.Adventurer;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Guilds.Adventurer.CombatRole;
import tterrag1112.life_in_the_village.Guilds.Adventurer.PlayerParty;
import tterrag1112.life_in_the_village.Guilds.PlayerPartySavedData;

import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;

/**
 * Party equivalent of {@link AdventurerCampGoal}.
 * Triggers when the player is stationary at night.
 * Mirrors the AdventurerCampGoal pattern exactly:
 * - SWORDSMAN (or first alive member) places a campfire
 * - Members sit around it on Marker seats
 * - After SLEEP_AFTER_TICKS, members sleep
 * - SCOUT acts as watchman instead of sleeping
 * - Campfire and seats cleaned up on stop()
 */
public class PartyCampGoal extends net.minecraft.world.entity.ai.goal.Goal {

    private static final long SLEEP_AFTER_TICKS   = 3600L;
    private static final long EAT_INTERVAL        = 2400L;
    private static final int  WATCH_LOOK_INTERVAL = 60;
    private static final int  STILL_TICKS         = 100;
    private static final double CAMP_BREAK_DIST   = 24.0;

    private final TownspersonMob entity;

    private BlockPos campfirePos  = null;
    private UUID     seatEntityId = null;
    private boolean  isSitting    = false;
    private boolean  isSleeping   = false;
    private boolean  isWatchman   = false;
    private long     campStartTick = -1L;
    private long     lastEatTick   = -1L;
    private int      watchLookTimer = 0;
    private float    watchLookAngle = 0f;

    // Stillness detection
    private int      stillCounter   = 0;
    private net.minecraft.world.phys.Vec3 lastPlayerPos = null;

    public PartyCampGoal(TownspersonMob entity) {
        this.entity = entity;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP));
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    @Override
    public boolean canUse() {
        if (!(entity.level() instanceof ServerLevel level)) return false;
        if (entity.getTarget() != null) return false;
        if (entity.getCombatRole() == null) return false;

        Optional<PlayerParty> partyOpt = getParty(level);
        if (partyOpt.isEmpty()) return false;

        ServerPlayer player = getPlayer(level, partyOpt.get());
        if (player == null) return false;

        return checkStillness(level, player);
    }

    @Override
    public boolean canContinueToUse() {
        if (!(entity.level() instanceof ServerLevel level)) return false;
        if (entity.getTarget() != null) return false;

        Optional<PlayerParty> partyOpt = getParty(level);
        if (partyOpt.isEmpty()) return false;

        ServerPlayer player = getPlayer(level, partyOpt.get());
        if (player == null) return false;

        // Break camp if player moves far away
        if (campfirePos != null) {
            double distSq = player.distanceToSqr(
                    campfirePos.getX(),
                    campfirePos.getY(),
                    campfirePos.getZ());
            if (distSq > CAMP_BREAK_DIST * CAMP_BREAK_DIST) return false;
        }

        // Break camp at dawn
        long timeOfDay = level.getDayTime() % 24000L;
        return timeOfDay >= 12000L && timeOfDay <= 23900L;
    }

    @Override
    public void start() {
        if (!(entity.level() instanceof ServerLevel level)) return;

        Optional<PlayerParty> partyOpt = getParty(level);
        if (partyOpt.isEmpty()) return;

        PlayerParty party = partyOpt.get();
        ServerPlayer player = getPlayer(level, party);
        if (player == null) return;

        campStartTick = level.getGameTime();
        lastEatTick   = campStartTick;

        // SCOUT is the watchman — they stay alert while others rest
        isWatchman = entity.getCombatRole() == CombatRole.SCOUT;

        // SWORDSMAN (or first alive member if no swordsman) places fire
        if (isCampfirePlacer(party)) {
            campfirePos = findSafeCampSpot(level, player.blockPosition());
            if (campfirePos != null) {
                placeCampfire(level, campfirePos);
                // Store on party rally point so other members can find it
                party.setRallyPoint(campfirePos);
            }
        } else {
            // Use the rally point set by the placer
            campfirePos = party.getRallyPoint();
        }

        if (isWatchman) {
            entity.setCurrentActivity("Keeping watch...");
        } else {
            sitAroundFire(level, party);
        }
    }

    @Override
    public void stop() {
        if (!(entity.level() instanceof ServerLevel level)) return;

        if (isSitting) standUp(level);
        if (isSleeping) wakeUp();

        // Campfire placer removes the fire on stop
        if (isCampfirePlacer(getParty(level).orElse(null))
                && campfirePos != null) {
            removeCampfire(level);
        }

        campfirePos   = null;
        isSitting     = false;
        isSleeping    = false;
        campStartTick = -1L;
        stillCounter  = 0;
        lastPlayerPos = null;
        entity.clearCurrentActivity();
    }

    @Override
    public void tick() {
        if (!(entity.level() instanceof ServerLevel level)) return;

        long currentTick = level.getGameTime();
        long elapsed     = campStartTick >= 0
                ? currentTick - campStartTick : 0;

        if (isWatchman) {
            tickWatchman(level, currentTick);
        } else {
            tickCamper(level, currentTick, elapsed);
        }
    }

    // =========================================================================
    // Watchman — SCOUT role
    // =========================================================================

    private void tickWatchman(ServerLevel level, long currentTick) {
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

        tickEat(level, currentTick);
        entity.setCurrentActivity("Keeping watch...");
    }

    // =========================================================================
    // Camper — all other roles
    // =========================================================================

    private void tickCamper(ServerLevel level,
                            long currentTick, long elapsed) {
        // Face the campfire
        if (campfirePos != null) {
            entity.getLookControl().setLookAt(
                    campfirePos.getX() + 0.5,
                    campfirePos.getY() + 0.5,
                    campfirePos.getZ() + 0.5);
        }

        // Sleep after threshold
        if (!isSleeping && elapsed >= SLEEP_AFTER_TICKS) {
            startSleeping(level);
            return;
        }

        if (!isSleeping) {
            tickEat(level, currentTick);
            entity.setCurrentActivity(campActivity());
        }
    }

    // =========================================================================
    // Eating — identical to AdventurerCampGoal
    // =========================================================================

    private void tickEat(ServerLevel level, long currentTick) {
        if (currentTick - lastEatTick < EAT_INTERVAL) return;
        lastEatTick = currentTick;

        ItemStack food = new ItemStack(Items.COOKED_BEEF);
        entity.startUsingItem(
                entity.getRandom().nextBoolean()
                        ? net.minecraft.world.InteractionHand.MAIN_HAND
                        : net.minecraft.world.InteractionHand.OFF_HAND);
        entity.setItemInHand(
                net.minecraft.world.InteractionHand.MAIN_HAND, food);

        level.getServer().execute(() -> {
            if (entity.isAlive()) {
                entity.stopUsingItem();
                entity.setItemInHand(
                        net.minecraft.world.InteractionHand.MAIN_HAND,
                        ItemStack.EMPTY);
            }
        });
    }

    // =========================================================================
    // Sitting — identical pattern to AdventurerCampGoal
    // =========================================================================

    private void sitAroundFire(ServerLevel level, PlayerParty party) {
        if (campfirePos == null) return;

        // Distribute seats by role ordinal — same as PartyCampGoal
        // original but using the Marker pattern from AdventurerCampGoal
        int memberCount = (int) party.getMembers().stream()
                .filter(PlayerParty.PartyMember::isAlive).count();
        int index = getRoleIndex(party);
        double angle = (index / (double) Math.max(1, memberCount))
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

        Marker seat = new Marker(EntityType.MARKER, level);
        seat.setPos(seatX, seatY - 1.0, seatZ);
        seat.setSilent(true);
        seat.setNoGravity(true);
        level.addFreshEntity(seat);

        entity.startRiding(seat, true, true);
        seatEntityId = seat.getUUID();
        isSitting    = true;

        entity.setCurrentActivity(campActivity());
    }

    private void standUp(ServerLevel level) {
        entity.stopRiding();
        isSitting = false;

        if (seatEntityId != null) {
            var seat = level.getEntity(seatEntityId);
            if (seat != null) seat.discard();
            seatEntityId = null;
        }
    }

    // =========================================================================
    // Sleeping — identical to AdventurerCampGoal
    // =========================================================================

    private void startSleeping(ServerLevel level) {
        if (isSitting) standUp(level);

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

    // =========================================================================
    // Campfire — identical to AdventurerCampGoal
    // =========================================================================

    private void placeCampfire(ServerLevel level, BlockPos pos) {
        BlockState current = level.getBlockState(pos);
        if (!current.isAir() && !current.canBeReplaced()) return;

        level.setBlock(pos,
                Blocks.CAMPFIRE.defaultBlockState()
                        .setValue(CampfireBlock.LIT, true)
                        .setValue(BlockStateProperties.WATERLOGGED,
                                false),
                3);
    }

    private void removeCampfire(ServerLevel level) {
        if (campfirePos == null) return;
        BlockState state = level.getBlockState(campfirePos);
        if (state.is(Blocks.CAMPFIRE)) {
            level.setBlock(campfirePos,
                    Blocks.AIR.defaultBlockState(), 3);
        }
        campfirePos = null;
    }

    private BlockPos findSafeCampSpot(ServerLevel level,
                                      BlockPos origin) {
        for (int r = 0; r <= 8; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) != r
                            && Math.abs(dz) != r) continue;

                    int x = origin.getX() + dx;
                    int z = origin.getZ() + dz;
                    int y = level.getHeight(
                            net.minecraft.world.level.levelgen.Heightmap
                                    .Types.MOTION_BLOCKING_NO_LEAVES,
                            x, z);

                    BlockPos candidate = new BlockPos(x, y, z);
                    BlockPos below     = candidate.below();

                    if (level.getBlockState(below).isSolidRender()
                            && level.getBlockState(candidate).isAir()
                            && isSafeFromMobs(level, candidate)) {
                        return candidate;
                    }
                }
            }
        }
        return origin.offset(2, 0, 0);
    }

    private boolean isSafeFromMobs(ServerLevel level, BlockPos pos) {
        return level.getNearbyEntities(
                        net.minecraft.world.entity.monster.Monster.class,
                        net.minecraft.world.entity.ai.targeting
                                .TargetingConditions.forNonCombat(),
                        entity,
                        entity.getBoundingBox().inflate(16))
                .isEmpty();
    }

    // =========================================================================
    // Stillness detection
    // =========================================================================

    private boolean checkStillness(ServerLevel level,
                                   ServerPlayer player) {
        // Only camp at night
        long timeOfDay = level.getDayTime() % 24000L;
        if (timeOfDay < 12000L || timeOfDay > 23900L) {
            stillCounter  = 0;
            lastPlayerPos = null;
            return false;
        }

        net.minecraft.world.phys.Vec3 pos = player.position();
        if (lastPlayerPos == null) {
            lastPlayerPos = pos;
            stillCounter  = 0;
            return false;
        }

        if (pos.distanceToSqr(lastPlayerPos) < 1.0) {
            stillCounter++;
        } else {
            stillCounter  = 0;
            lastPlayerPos = pos;
        }

        return stillCounter >= STILL_TICKS;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * SWORDSMAN places the fire; if no swordsman, first alive member does.
     */
    private boolean isCampfirePlacer(PlayerParty party) {
        if (party == null) return false;
        if (entity.getCombatRole() == CombatRole.SWORDSMAN) return true;
        if (!party.hasRole(CombatRole.SWORDSMAN)) {
            return party.getMembers().stream()
                    .filter(PlayerParty.PartyMember::isAlive)
                    .findFirst()
                    .map(m -> m.npcId().equals(entity.getUUID()))
                    .orElse(false);
        }
        return false;
    }

    /**
     * Seat index around the campfire, based on role ordinal so
     * members spread out predictably and never stack.
     */
    private int getRoleIndex(PlayerParty party) {
        if (entity.getCombatRole() == null) return 0;
        return entity.getCombatRole().ordinal();
    }

    private String campActivity() {
        if (entity.getCombatRole() == null) return "Resting...";
        return switch (entity.getCombatRole()) {
            case SWORDSMAN -> "Keeping watch";
            case ARCHER    -> "Fletching arrows";
            case MAGE      -> "Studying grimoire";
            case HEALER    -> "Preparing potions";
            case SCOUT     -> "Scanning the dark";
        };
    }

    private Optional<PlayerParty> getParty(ServerLevel level) {
        return PlayerPartySavedData.get(level)
                .getPartyContaining(entity.getUUID());
    }

    private ServerPlayer getPlayer(ServerLevel level,
                                   PlayerParty party) {
        return level.getServer().getPlayerList()
                .getPlayer(party.getLeaderPlayerId());
    }
}