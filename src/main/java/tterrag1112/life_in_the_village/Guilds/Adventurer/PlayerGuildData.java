package tterrag1112.life_in_the_village.Guilds.Adventurer;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.*;
import java.util.stream.Collectors;

public class PlayerGuildData extends SavedData {

    public static final Codec<PlayerGuildData> CODEC =
            RecordCodecBuilder.create(instance -> instance.group(
                    GuildMember.CODEC.listOf()
                            .optionalFieldOf("members", new ArrayList<>())
                            .forGetter(d -> d.members),
                    Quest.CODEC.listOf()
                            .optionalFieldOf("quests", new ArrayList<>())
                            .forGetter(d -> d.quests)
            ).apply(instance, PlayerGuildData::fromCodec));

    public static final SavedDataType<PlayerGuildData> TYPE =
            new SavedDataType<>(
                    "life_in_the_village_guild",
                    PlayerGuildData::new,
                    CODEC
            );

    private final List<GuildMember> members = new ArrayList<>();
    private final List<Quest> quests = new ArrayList<>();

    public PlayerGuildData() {}

    public static PlayerGuildData fromCodec(
            List<GuildMember> members, List<Quest> quests) {
        PlayerGuildData data = new PlayerGuildData();
        data.members.addAll(members);
        data.quests.addAll(quests);
        return data;
    }

    public static PlayerGuildData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    // --- Members ---
    public boolean isRegistered(UUID playerId) {
        return members.stream().anyMatch(m -> m.playerId().equals(playerId));
    }

    public Optional<GuildMember> getMember(UUID playerId) {
        return members.stream()
                .filter(m -> m.playerId().equals(playerId))
                .findFirst();
    }

    public void registerPlayer(UUID playerId, String playerName,
                               UUID guildId) {
        if (isRegistered(playerId)) return;
        members.add(new GuildMember(playerId, playerName,
                guildId, GuildRank.BRONZE, 0, new ArrayList<>(),
                System.currentTimeMillis()));
        setDirty();
    }

    public void addXp(UUID playerId, int xp) {
        getMember(playerId).ifPresent(member -> {
            members.remove(member);
            GuildMember updated = member.withXp(member.xp() + xp);
            members.add(updated);
            setDirty();
        });
    }

    // --- Quests ---
    public void addQuest(Quest quest) {
        quests.add(quest);
        setDirty();
    }

    public Optional<Quest> getQuestById(UUID id) {
        return quests.stream().filter(q -> q.getId().equals(id))
                .findFirst();
    }

    public List<Quest> getAvailableQuestsForGuild(UUID guildId,
                                                  GuildRank rank) {
        return quests.stream()
                .filter(q -> q.getGuildId().equals(guildId)
                        && q.getStatus() == Quest.QuestStatus.AVAILABLE
                        && rank.canAcceptQuest(q.getDifficulty()))
                .collect(Collectors.toList());
    }

    public List<Quest> getActiveQuestsForPlayer(UUID playerId) {
        return quests.stream()
                .filter(q -> playerId.equals(q.getAssignedPlayerId())
                        && q.isActive())
                .collect(Collectors.toList());
    }

    public void removeExpiredQuests(long currentTick) {
        quests.removeIf(q -> q.isExpired(currentTick));
        setDirty();
    }

    public List<Quest> getAllQuestsForGuild(UUID guildId) {
        return quests.stream()
                .filter(q -> q.getGuildId().equals(guildId))
                .collect(Collectors.toList());
    }
   public void setRank(UUID playerId, GuildRank rank) {
        for (int i = 0; i < members.size(); i++) {
            if (members.get(i).playerId().equals(playerId)) {
                members.set(i, members.get(i).withRank(rank));

                setDirty();
                return;
            }
        }
    }
}