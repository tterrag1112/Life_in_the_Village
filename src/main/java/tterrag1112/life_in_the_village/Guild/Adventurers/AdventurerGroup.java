package tterrag1112.life_in_the_village.Guild.Adventurers;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import tterrag1112.life_in_the_village.Guild.Quest;

import javax.annotation.Nullable;
import java.util.*;

public class AdventurerGroup {

    // -------------------------------------------------------------------------
    // Enums
    // -------------------------------------------------------------------------

    public enum GroupState {
        TRAVELLING,
        CAMPING,
        HUNTING,
        RETURNING,
        EXPLORING,
        ESCORTING;

        public static final Codec<GroupState> CODEC = Codec.STRING.xmap(
                GroupState::valueOf, GroupState::name);
    }

    // -------------------------------------------------------------------------
    // Codec
    // -------------------------------------------------------------------------

    public static final Codec<AdventurerGroup> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    UUIDUtil.CODEC.fieldOf("groupId")
                            .forGetter(AdventurerGroup::getGroupId),
                    UUIDUtil.CODEC.fieldOf("homeVillageId")
                            .forGetter(AdventurerGroup::getHomeVillageId),
                    UUIDUtil.CODEC.listOf().fieldOf("memberIds")
                            .forGetter(AdventurerGroup::getMemberIds),
                    BlockPos.CODEC.fieldOf("currentPos")
                            .forGetter(AdventurerGroup::getCurrentPos),
                    BlockPos.CODEC.fieldOf("targetPos")
                            .forGetter(AdventurerGroup::getTargetPos),
                    GroupState.CODEC.fieldOf("state")
                            .forGetter(AdventurerGroup::getState),
                    Quest.CODEC.optionalFieldOf("activeQuest")
                            .forGetter(g -> Optional.ofNullable(g.getActiveQuest())),
                    Codec.LONG.fieldOf("stateChangeTick")
                            .forGetter(AdventurerGroup::getStateChangeTick),
                    Codec.BOOL.fieldOf("isSpawned")
                            .forGetter(g -> false),
                    Codec.BOOL.fieldOf("isDead")
                            .forGetter(AdventurerGroup::isDead),
                    Codec.LONG.optionalFieldOf("failureCooldownTick", -1L)
                            .forGetter(AdventurerGroup::getFailureCooldownTick),
                    Codec.DOUBLE.optionalFieldOf("successChanceModifier", 1.0)
                            .forGetter(AdventurerGroup::getSuccessChanceModifier),
                    GroupPlayerData.CODEC.fieldOf("playerData")
                            .forGetter(AdventurerGroup::getPlayerData)
            ).apply(instance, AdventurerGroup::fromCodec));



    public record GroupPlayerData(
            Optional<UUID> joinedPlayerId,
            Optional<UUID> alliedPlayerId,
            boolean playerLeading,
            Optional<BlockPos> campfirePos
    ) {
        public static final Codec<GroupPlayerData> CODEC =
                RecordCodecBuilder.create(i -> i.group(
                        UUIDUtil.CODEC.optionalFieldOf("joinedPlayerId")
                                .forGetter(GroupPlayerData::joinedPlayerId),
                        UUIDUtil.CODEC.optionalFieldOf("alliedPlayerId")
                                .forGetter(GroupPlayerData::alliedPlayerId),
                        Codec.BOOL.optionalFieldOf("playerLeading", false)
                                .forGetter(GroupPlayerData::playerLeading),
                        BlockPos.CODEC.optionalFieldOf("campfirePos")
                                .forGetter(GroupPlayerData::campfirePos)
                ).apply(i, GroupPlayerData::new));
    }

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------


    public GroupPlayerData getPlayerData() {
        return new GroupPlayerData(
                joinedPlayerId != null
                        ? Optional.of(joinedPlayerId) : Optional.empty(),
                alliedPlayerId != null
                        ? Optional.of(alliedPlayerId) : Optional.empty(),
                playerLeading,
                campfirePos != null
                        ? Optional.of(campfirePos) : Optional.empty()
        );
    }
    private final UUID groupId;
    private final UUID homeVillageId;
    private final List<UUID> memberIds;
    private BlockPos currentPos;
    private BlockPos targetPos;
    private GroupState state;
    private Quest activeQuest;
    private long stateChangeTick;
    private boolean isDead = false;
    private long failureCooldownTick = -1L;
    private double successChanceModifier = 1.0;

    @Nullable
    private UUID alliedPlayerId = null;
    @Nullable private UUID joinedPlayerId = null;
    private final Set<UUID> buffedPlayers = new HashSet<>();

    // Never persisted — always starts false on load.
    // Entities are re-spawned on proximity check.
    private boolean isSpawned = false;
    private final List<UUID> spawnedEntityIds = new ArrayList<>();

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    public AdventurerGroup(UUID groupId, UUID homeVillageId,
                           List<UUID> memberIds, BlockPos currentPos,
                           BlockPos targetPos, GroupState state,
                           Quest activeQuest, long stateChangeTick) {
        this.groupId = groupId;
        this.homeVillageId = homeVillageId;
        this.memberIds = new ArrayList<>(memberIds);
        this.currentPos = currentPos;
        this.targetPos = targetPos;
        this.state = state;
        this.activeQuest = activeQuest;
        this.stateChangeTick = stateChangeTick;
    }

    public static AdventurerGroup fromCodec(
            UUID groupId, UUID homeVillageId, List<UUID> memberIds,
            BlockPos currentPos, BlockPos targetPos, GroupState state,
            Optional<Quest> activeQuest, long stateChangeTick,
            boolean isSpawned, boolean isDead, long failureCooldownTick,
            double successChanceModifier, GroupPlayerData playerData) {
        AdventurerGroup group = new AdventurerGroup(groupId, homeVillageId,
                memberIds, currentPos, targetPos, state,
                activeQuest.orElse(null), stateChangeTick);
        group.isSpawned            = false;
        group.spawnedEntityIds.clear();
        group.isDead               = isDead;
        group.failureCooldownTick  = failureCooldownTick;
        group.successChanceModifier = successChanceModifier;
        group.joinedPlayerId       = playerData.joinedPlayerId().orElse(null);
        group.alliedPlayerId       = playerData.alliedPlayerId().orElse(null);
        group.playerLeading        = playerData.playerLeading();
        group.campfirePos          = playerData.campfirePos().orElse(null);
        return group;
    }

    // -------------------------------------------------------------------------
    // Convenience factory for creating new groups
    // -------------------------------------------------------------------------

    public static AdventurerGroup create(UUID homeVillageId,
                                         int memberCount,
                                         BlockPos startPos,
                                         BlockPos targetPos,
                                         long currentTick) {
        List<UUID> members = new ArrayList<>();
        for (int i = 0; i < memberCount; i++) {
            members.add(UUID.randomUUID());
        }
        return new AdventurerGroup(
                UUID.randomUUID(),
                homeVillageId,
                members,
                startPos,
                targetPos,
                GroupState.TRAVELLING,
                null,
                currentTick
        );
    }

    // -------------------------------------------------------------------------
    // Simulation
    // -------------------------------------------------------------------------

    /**
     * Advances the group's simulated position and state.
     * Should be called once per second (every 20 ticks), not every tick.
     */
    public boolean tick(long currentTick) {
        if (isSpawned) return false; // spawned side handles itself
        if (isDead) return false;

        boolean dirty = false;

        switch (state) {
            case TRAVELLING, RETURNING -> {
                BlockPos next = stepToward(currentPos, targetPos);
                if (!next.equals(currentPos)) {
                    currentPos = next;
                    dirty = true;
                }
                if (currentPos.equals(targetPos)) {
                    if (state == GroupState.RETURNING) {
                        completeActiveQuest();
                        state = GroupState.CAMPING;
                    } else {
                        state = activeQuest != null
                                ? questStateFor(activeQuest)
                                : GroupState.CAMPING;
                    }
                    stateChangeTick = currentTick;
                    dirty = true;
                }
            }
            case CAMPING -> {
                if (currentTick - stateChangeTick > 6000L) {
                    state = activeQuest != null
                            ? questStateFor(activeQuest)
                            : GroupState.HUNTING;
                    stateChangeTick = currentTick;
                    dirty = true;
                }
            }
            case HUNTING -> {
                dirty = tickHuntSimulation(currentTick);
            }
            case EXPLORING -> {
                dirty = tickExploreSimulation(currentTick);
            }
            case ESCORTING -> {
                BlockPos next = stepToward(currentPos, targetPos);
                if (!next.equals(currentPos)) {
                    currentPos = next;
                    dirty = true;
                }
                if (currentPos.equals(targetPos)) {
                    dirty = tickEscortResolution(currentTick);
                }
            }
        }

        return dirty;
    }

// -------------------------------------------------------------------------
// Probabilistic simulation methods
// -------------------------------------------------------------------------

    private boolean tickHuntSimulation(long currentTick) {
        if (activeQuest == null) {
            // No quest — idle hunt, just return after a while
            if (currentTick - stateChangeTick > 3600L) {
                state = GroupState.RETURNING;
                stateChangeTick = currentTick;
                return true;
            }
            return false;
        }

        long elapsed = currentTick - stateChangeTick;

        // Roll every 600 ticks (30 seconds) for a kill
        if (elapsed % 600 != 0) return false;

        double baseChance = getBaseSuccessChance() * successChanceModifier;

        // Chance of a kill this roll
        if (RANDOM.nextDouble() < baseChance) {
            int newCount = activeQuest.getCurrentCount() + 1;
            activeQuest.setCurrentCount(newCount);

            if (newCount >= activeQuest.getTargetCount()) {
                // Quest complete
                completeActiveQuest();
                state = GroupState.RETURNING;
                stateChangeTick = currentTick;
                return true;
            }
        } else {
            // Failed roll — small chance of group dying
            double deathChance = getDeathChanceOnFailedRoll();
            if (RANDOM.nextDouble() < deathChance) {
                isDead = true;
                return true;
            }
            // Near-failure degrades future success chance slightly
            successChanceModifier = Math.max(0.1,
                    successChanceModifier - 0.05);
        }

        // Quest expired
        if (activeQuest.isExpired(currentTick)) {
            isDead = true;
            return true;
        }

        return true; // dirty because elapsed check passed
    }

    private boolean tickExploreSimulation(long currentTick) {
        if (activeQuest == null) return false;

        long elapsed = currentTick - stateChangeTick;
        long requiredTime = switch (activeQuest.getDifficulty()) {
            case EASY      -> 2400L;
            case MEDIUM    -> 3600L;
            case HARD      -> 4800L;
            case ELITE     -> 6000L;
            case LEGENDARY -> 7200L;
        };

        if (elapsed < requiredTime) return false;

        double successChance = getBaseSuccessChance() * successChanceModifier;
        if (RANDOM.nextDouble() < successChance) {
            completeActiveQuest();
        } else {
            // No death — they just didn't find it, return empty
            activeQuest.setStatus(Quest.QuestStatus.FAILED);
            activeQuest = null;
        }
        state = GroupState.RETURNING;
        stateChangeTick = currentTick;
        return true;
    }

    private boolean tickEscortResolution(long currentTick) {
        if (activeQuest == null) return false;

        double successChance = getBaseSuccessChance() * successChanceModifier;
        if (RANDOM.nextDouble() < successChance) {
            completeActiveQuest();
            state = GroupState.RETURNING;
        } else {
            // Escort failure CAN result in death — they were ambushed
            double deathChance = getDeathChanceOnFailedRoll() * 2.0;
            if (RANDOM.nextDouble() < deathChance) {
                isDead = true;
            } else {
                // Survived but failed — return home
                activeQuest.setStatus(Quest.QuestStatus.FAILED);
                activeQuest = null;
                state = GroupState.RETURNING;
            }
        }
        stateChangeTick = currentTick;
        return true;
    }

// -------------------------------------------------------------------------
// Probability helpers
// -------------------------------------------------------------------------

    private static final java.util.Random RANDOM = new java.util.Random();

    private double getBaseSuccessChance() {
        if (activeQuest == null) return 0.5;

        // Base chance by difficulty
        double base = switch (activeQuest.getDifficulty()) {
            case EASY      -> 0.80;
            case MEDIUM    -> 0.65;
            case HARD      -> 0.50;
            case ELITE     -> 0.35;
            case LEGENDARY -> 0.20;
        };

        // Larger groups are more successful
        double groupBonus = (memberIds.size() - 1) * 0.05;
        return Math.min(0.95, base + groupBonus);
    }

    private double getDeathChanceOnFailedRoll() {
        if (activeQuest == null) return 0.01;
        return switch (activeQuest.getDifficulty()) {
            case EASY      -> 0.02;
            case MEDIUM    -> 0.05;
            case HARD      -> 0.10;
            case ELITE     -> 0.20;
            case LEGENDARY -> 0.35;
        };
    }

    private GroupState questStateFor(Quest quest) {
        return switch (quest.getType()) {
            case HUNT    -> GroupState.HUNTING;
            case EXPLORE -> GroupState.EXPLORING;
            case ESCORT  -> GroupState.ESCORTING;
            default      -> GroupState.CAMPING;
        };
    }

    private void completeActiveQuest() {
        if (activeQuest == null) return;
        activeQuest.setStatus(Quest.QuestStatus.COMPLETED);
        activeQuest = null;
    }

    /**
     * Steps currentPos toward targetPos by approximately 4 blocks
     * (one second of walking speed at 0.2 blocks/tick * 20 ticks).
     */
    private static BlockPos stepToward(BlockPos from, BlockPos to) {
        if (from.equals(to)) return from;

        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);

        if (dist <= 4.0) return to;

        double scale = 4.0 / dist;
        return new BlockPos(
                (int) Math.round(from.getX() + dx * scale),
                from.getY(),
                (int) Math.round(from.getZ() + dz * scale)
        );
    }

    // -------------------------------------------------------------------------
    // Getters
    // -------------------------------------------------------------------------

    public UUID getGroupId()              { return groupId; }
    public UUID getHomeVillageId()        { return homeVillageId; }
    public List<UUID> getMemberIds()      { return memberIds; }
    public BlockPos getCurrentPos()       { return currentPos; }
    public BlockPos getTargetPos()        { return targetPos; }
    public GroupState getState()          { return state; }
    public Quest getActiveQuest()         { return activeQuest; }
    public long getStateChangeTick()      { return stateChangeTick; }
    public boolean isSpawned()            { return isSpawned; }
    public List<UUID> getSpawnedEntityIds() { return spawnedEntityIds; }

    public int getMemberCount()           { return memberIds.size(); }

    public boolean isNearPosition(BlockPos pos, int radius) {
        return currentPos.closerThan(pos, radius);
    }

    // -------------------------------------------------------------------------
    // Setters
    // -------------------------------------------------------------------------

    public void setCurrentPos(BlockPos pos)       { this.currentPos = pos; }
    public void setTargetPos(BlockPos pos)         { this.targetPos = pos; }
    public void setState(GroupState state)         { this.state = state; }
    public void setActiveQuest(Quest quest)        { this.activeQuest = quest; }
    public void setStateChangeTick(long tick)      { this.stateChangeTick = tick; }
    public void setSpawned(boolean spawned)        { this.isSpawned = spawned; }

    public boolean isDead()                          { return isDead; }
    public void setDead(boolean dead)                { this.isDead = dead; }
    public long getFailureCooldownTick()             { return failureCooldownTick; }
    public void setFailureCooldownTick(long tick)    { this.failureCooldownTick = tick; }
    public double getSuccessChanceModifier()         { return successChanceModifier; }
    public void setSuccessChanceModifier(double v)   { this.successChanceModifier = v; }


    public Optional<UUID> getAlliedPlayerId() {
        return Optional.ofNullable(alliedPlayerId);
    }
    public void setAlliedPlayerId(UUID id) {
        this.alliedPlayerId = id;
    }

    public boolean hasJoinedPlayer() {
        return joinedPlayerId != null;
    }
    public Optional<UUID> getJoinedPlayerId() {
        return Optional.ofNullable(joinedPlayerId);
    }
    public void setJoinedPlayerId(@Nullable UUID id) {
        this.joinedPlayerId = id;
    }

    public boolean hasBuffedPlayer(UUID playerId) {
        return buffedPlayers.contains(playerId);
    }
    public void markPlayerBuffed(UUID playerId) {
        buffedPlayers.add(playerId);
    }
    public void clearBuffedPlayers() {
        buffedPlayers.clear();
    }

    private boolean playerLeading = false;

    public boolean isPlayerLeading(UUID playerId) {
        return playerLeading
                && joinedPlayerId != null
                && joinedPlayerId.equals(playerId);
    }

    public void setPlayerLeading(boolean leading) {
        this.playerLeading = leading;
    }

    // Convenience — used by AdventurerPatrolGoal to check
// whether to follow player or group position
    public boolean hasPlayerLeader() {
        return playerLeading && joinedPlayerId != null;
    }

    public boolean isPlayerLeadingAny() {
        return playerLeading;
    }


    // Field
    @Nullable private BlockPos campfirePos = null;

    // Getters/setters
    public Optional<BlockPos> getCampfirePos() {
        return Optional.ofNullable(campfirePos);
    }
    public void setCampfirePos(@Nullable BlockPos pos) {
        this.campfirePos = pos;
    }
}
