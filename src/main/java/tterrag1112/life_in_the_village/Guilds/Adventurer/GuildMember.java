package tterrag1112.life_in_the_village.Guilds.Adventurer;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record GuildMember(
        UUID playerId,
        String playerName,
        UUID guildId,
        GuildRank rank,
        int xp,
        List<UUID> completedQuestIds,
        long registeredTime
) {
    public static final Codec<GuildMember> CODEC =
            RecordCodecBuilder.create(instance -> instance.group(
                    Codec.STRING.xmap(UUID::fromString, UUID::toString)
                            .fieldOf("playerId").forGetter(GuildMember::playerId),
                    Codec.STRING.fieldOf("playerName")
                            .forGetter(GuildMember::playerName),
                    Codec.STRING.xmap(UUID::fromString, UUID::toString)
                            .fieldOf("guildId").forGetter(GuildMember::guildId),
                    Codec.STRING.xmap(GuildRank::valueOf, GuildRank::name)
                            .fieldOf("rank").forGetter(GuildMember::rank),
                    Codec.INT.fieldOf("xp").forGetter(GuildMember::xp),
                    Codec.STRING.xmap(UUID::fromString, UUID::toString).listOf()
                            .optionalFieldOf("completedQuestIds", new ArrayList<>())
                            .forGetter(GuildMember::completedQuestIds),
                    Codec.LONG.fieldOf("registeredTime")
                            .forGetter(GuildMember::registeredTime)
            ).apply(instance, GuildMember::new));

    public GuildRank currentRank() {
        return GuildRank.fromXp(xp);
    }

    public int xpToNextRank() {
        if (currentRank().isMaxRank()) return 0;
        return currentRank().next().getMinXp() - xp;
    }

    public GuildMember withXp(int newXp) {
        return new GuildMember(playerId, playerName, guildId,
                GuildRank.fromXp(newXp), newXp,
                completedQuestIds, registeredTime);
    }

    public GuildMember withCompletedQuest(UUID questId) {
        List<UUID> updated = new ArrayList<>(completedQuestIds);
        updated.add(questId);
        return new GuildMember(playerId, playerName, guildId,
                rank, xp, updated, registeredTime);
    }
    public GuildMember withRank(GuildRank newRank) {
        // Calculate minimum XP for this rank so currentRank() stays consistent
        int newXp = Math.max(xp, newRank.getMinXp());
        return new GuildMember(playerId, playerName, guildId,
                newRank, newXp, completedQuestIds, registeredTime);
    }
}