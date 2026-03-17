package tterrag1112.life_in_the_village.Guild;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;


import javax.annotation.Nullable;
import javax.swing.text.html.Option;
import java.util.Optional;
import java.util.UUID;

public class Quest {

    // -------------------------------------------------------------------------
    // Enums
    // -------------------------------------------------------------------------

    public enum QuestType {
        GATHER, HUNT, EXPLORE, ESCORT, DELIVER;

        public static final Codec<QuestType> CODEC = Codec.STRING.xmap(
                QuestType::valueOf, QuestType::name);
    }

    public enum QuestDifficulty {
        EASY(GuildRank.BRONZE, 10, 32),
        MEDIUM(GuildRank.SILVER, 25, 64),
        HARD(GuildRank.GOLD, 50, 128),
        ELITE(GuildRank.PLATINUM, 100, 256),
        LEGENDARY(GuildRank.DIAMOND, 200, 512);

        private final GuildRank minRank;
        private final int baseXp;
        private final long baseCoinReward;

        QuestDifficulty(GuildRank minRank, int baseXp, long baseCoinReward) {
            this.minRank = minRank;
            this.baseXp = baseXp;
            this.baseCoinReward = baseCoinReward;
        }

        public GuildRank minRank()      { return minRank; }
        public int getBaseXp()          { return baseXp; }
        public long getBaseCoinReward() { return baseCoinReward; }

        public static final Codec<QuestDifficulty> CODEC = Codec.STRING.xmap(
                QuestDifficulty::valueOf, QuestDifficulty::name);
    }

    public enum QuestStatus {
        AVAILABLE, ACTIVE, COMPLETED, FAILED, EXPIRED;

        public static final Codec<QuestStatus> CODEC = Codec.STRING.xmap(
                QuestStatus::valueOf, QuestStatus::name);
    }

    // -------------------------------------------------------------------------
    // QuestProgress nested record
    // -------------------------------------------------------------------------

    public record QuestProgress(
            QuestStatus status,
            UUID assignedPlayer,
            long acceptedTick,
            int currentCount,
            BlockPos targetPos
    ) {
        public static final Codec<QuestProgress> CODEC = RecordCodecBuilder.create(i -> i.group(
                QuestStatus.CODEC.fieldOf("status")
                        .forGetter(QuestProgress::status),
                UUIDUtil.CODEC.optionalFieldOf("assignedPlayer")
                        .forGetter(p -> Optional.ofNullable(p.assignedPlayer())),
                Codec.LONG.fieldOf("acceptedTick")
                        .forGetter(QuestProgress::acceptedTick),
                Codec.INT.fieldOf("currentCount")
                        .forGetter(QuestProgress::currentCount),
                BlockPos.CODEC.optionalFieldOf("targetPos")
                        .forGetter(p -> Optional.ofNullable(p.targetPos()))
        ).apply(i, (status, player, tick, count, pos) ->
                new QuestProgress(status, player.orElse(null),
                        tick, count, pos.orElse(null))
        ));
    }

    // -------------------------------------------------------------------------
    // Codec
    // -------------------------------------------------------------------------

    public static final Codec<Quest> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            UUIDUtil.CODEC.fieldOf("id")
                    .forGetter(Quest::getId),
            UUIDUtil.CODEC.fieldOf("guildId")
                    .forGetter(Quest::getGuildId),
            QuestType.CODEC.fieldOf("type")
                    .forGetter(Quest::getType),
            QuestDifficulty.CODEC.fieldOf("difficulty")
                    .forGetter(Quest::getDifficulty),
            Codec.STRING.fieldOf("title")
                    .forGetter(Quest::getTitle),
            Codec.STRING.fieldOf("description")
                    .forGetter(Quest::getDescription),
            Codec.LONG.fieldOf("coinReward")
                    .forGetter(Quest::getCoinReward),
            Codec.INT.fieldOf("xpReward")
                    .forGetter(Quest::getXpReward),
            Codec.LONG.fieldOf("expiryTick")
                    .forGetter(Quest::getExpiryTick),
            BuiltInRegistries.ITEM.byNameCodec().optionalFieldOf("targetItem")
                    .forGetter(q -> Optional.ofNullable(q.getTargetItem())),
            Codec.INT.fieldOf("targetCount")
                    .forGetter(Quest::getTargetCount),
            UUIDUtil.CODEC.optionalFieldOf("targetVillageId")
                    .forGetter(q -> Optional.ofNullable(q.getTargetVillageId())),
            Codec.STRING.optionalFieldOf("specialRewardId", "")
                    .forGetter(Quest::getSpecialRewardId),
            Codec.BOOL.fieldOf("hasSpecialReward")
                    .forGetter(Quest::hasSpecialReward),
            QuestProgress.CODEC.fieldOf("progress")
                    .forGetter(Quest::getProgress),
            BlockPos.CODEC.optionalFieldOf("questTargetPos")
                    .forGetter(q -> Optional.ofNullable(q.getQuestTargetPos()))
    ).apply(instance, Quest::fromCodecNew));

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    private final UUID id;
    private final UUID guildId;
    private final QuestType type;
    private final QuestDifficulty difficulty;
    private QuestStatus status;
    private final String title;
    private final String description;
    private final long coinReward;
    private final int xpReward;
    private UUID assignedPlayerId;
    private long expiryTick;
    private long acceptedTick;
    @Nullable
    private BlockPos questTargetPos = null;


    // Type-specific fields
    private Item targetItem;
    private int targetCount;
    private int currentCount;
    private BlockPos targetPos;
    private String targetBiome;
    private String targetMob;
    private UUID targetVillageId;
    private boolean hasSpecialReward;
    private String specialRewardId;

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    public Quest(UUID id, UUID guildId, QuestType type,
                 QuestDifficulty difficulty, String title,
                 String description, long coinReward, int xpReward,
                 long expiryTick) {
        this.id = id;
        this.guildId = guildId;
        this.type = type;
        this.difficulty = difficulty;
        this.status = QuestStatus.AVAILABLE;
        this.title = title;
        this.description = description;
        this.coinReward = coinReward;
        this.xpReward = xpReward;
        this.expiryTick = expiryTick;
        this.acceptedTick = -1L;
        this.specialRewardId = "";
    }

    public static Quest fromCodecNew(
            UUID id, UUID guildId, QuestType type, QuestDifficulty difficulty,
            String title, String description, long coinReward, int xpReward,
            long expiryTick, Optional<Item> targetItem, int targetCount,
            Optional<UUID> targetVillageId, String specialRewardId,
            boolean hasSpecialReward, QuestProgress progress, Optional<BlockPos> questTargetPos) {
        Quest q = new Quest(id, guildId, type, difficulty, title,
                description, coinReward, xpReward, expiryTick);
        q.status           = progress.status();
        q.assignedPlayerId = progress.assignedPlayer();
        q.acceptedTick     = progress.acceptedTick();
        q.currentCount     = progress.currentCount();
        q.targetPos        = progress.targetPos();
        q.targetItem       = targetItem.orElse(null);
        q.targetCount      = targetCount;
        q.targetVillageId  = targetVillageId.orElse(null);
        q.specialRewardId  = specialRewardId;
        q.hasSpecialReward = hasSpecialReward;
        q.questTargetPos = questTargetPos.orElse(null);

        return q;
    }

    // -------------------------------------------------------------------------
    // Getters
    // -------------------------------------------------------------------------

    public UUID getId()                    { return id; }
    public UUID getGuildId()               { return guildId; }
    public QuestType getType()             { return type; }
    public QuestDifficulty getDifficulty() { return difficulty; }
    public QuestStatus getStatus()         { return status; }
    public String getTitle()               { return title; }
    public String getDescription()         { return description; }
    public long getCoinReward()            { return coinReward; }
    public int getXpReward()               { return xpReward; }
    public UUID getAssignedPlayerId()      { return assignedPlayerId; }
    public long getExpiryTick()            { return expiryTick; }
    public long getAcceptedTick()          { return acceptedTick; }
    public Item getTargetItem()            { return targetItem; }
    public int getTargetCount()            { return targetCount; }
    public int getCurrentCount()           { return currentCount; }
    public BlockPos getTargetPos()         { return targetPos; }
    public String getTargetBiome()         { return targetBiome; }
    public String getTargetMob()           { return targetMob; }
    public UUID getTargetVillageId()       { return targetVillageId; }
    public boolean hasSpecialReward()      { return hasSpecialReward; }
    public String getSpecialRewardId()     { return specialRewardId; }

    /** Returns a snapshot of the mutable runtime state for codec serialization. */
    public QuestProgress getProgress() {
        return new QuestProgress(status, assignedPlayerId, acceptedTick,
                currentCount, targetPos);
    }

    // -------------------------------------------------------------------------
    // Setters
    // -------------------------------------------------------------------------

    public void setStatus(QuestStatus status)       { this.status = status; }
    public void setAssignedPlayer(UUID id)          { this.assignedPlayerId = id; }
    public void setAcceptedTick(long tick)          { this.acceptedTick = tick; }
    public void setCurrentCount(int count)          { this.currentCount = count; }
    public void setTargetItem(Item item)            { this.targetItem = item; }
    public void setTargetCount(int count)           { this.targetCount = count; }
    public void setTargetPos(BlockPos pos)          { this.targetPos = pos; }
    public void setTargetBiome(String biome)        { this.targetBiome = biome; }
    public void setTargetMob(String mob)            { this.targetMob = mob; }
    public void setTargetVillageId(UUID id)         { this.targetVillageId = id; }
    public void setHasSpecialReward(boolean v)      { this.hasSpecialReward = v; }
    public void setSpecialRewardId(String id)       { this.specialRewardId = id; }

    // -------------------------------------------------------------------------
    // Utility
    // -------------------------------------------------------------------------

    public boolean isExpired(long currentTick) {
        return expiryTick > 0 && currentTick > expiryTick
                && status == QuestStatus.AVAILABLE;
    }

    public boolean isActive()    { return status == QuestStatus.ACTIVE; }
    public boolean isCompleted() { return status == QuestStatus.COMPLETED; }

    public double getCompletionFraction() {
        if (targetCount <= 0) return 0;
        return (double) currentCount / targetCount;
    }

    public BlockPos getQuestTargetPos()              { return questTargetPos; }
    public void setQuestTargetPos(BlockPos pos)           { this.questTargetPos = pos; }
}