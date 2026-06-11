package tterrag1112.life_in_the_village.Guilds.Adventurer;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.*;
import java.util.stream.Collectors;

public class PlayerGuildData extends SavedData {

    // F2b-2 — quests re-seated onto the F2 QuestSavedData store; this data now holds only
    // guild membership (rank / XP). The legacy "quests" save key (if present on an old
    // world) is simply ignored on load.
    public static final Codec<PlayerGuildData> CODEC =
            RecordCodecBuilder.create(instance -> instance.group(
                    GuildMember.CODEC.listOf()
                            .optionalFieldOf("members", new ArrayList<>())
                            .forGetter(d -> d.members)
            ).apply(instance, PlayerGuildData::fromCodec));

    public static final SavedDataType<PlayerGuildData> TYPE =
            new SavedDataType<>(
                    "life_in_the_village_guild",
                    PlayerGuildData::new,
                    CODEC
            );

    private final List<GuildMember> members = new ArrayList<>();

    public PlayerGuildData() {}

    public static PlayerGuildData fromCodec(List<GuildMember> members) {
        PlayerGuildData data = new PlayerGuildData();
        data.members.addAll(members);
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

    public void setRank(UUID playerId, GuildRank rank) {
        for (int i = 0; i < members.size(); i++) {
            if (members.get(i).playerId().equals(playerId)) {
                members.set(i, members.get(i).withRank(rank));

                setDirty();
                return;
            }
        }
    }

    public List<GuildMember> getAllMembersForGuild(UUID guildId) {
        return members.stream()
                .filter(m -> m.guildId().equals(guildId))
                .collect(Collectors.toList());
    }

    /**
     * Replaces an existing member record with an updated one.
     * Used after quest completion and rank-up.
     */
    public void updateMember(GuildMember updated) {
        for (int i = 0; i < members.size(); i++) {
            if (members.get(i).playerId().equals(updated.playerId())) {
                members.set(i, updated);
                setDirty();
                return;
            }
        }
    }
}