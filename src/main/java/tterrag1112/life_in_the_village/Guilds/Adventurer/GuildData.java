package tterrag1112.life_in_the_village.Guilds.Adventurer;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.UUID;

public record GuildData(
        UUID guildId,
        UUID villageId,
        UUID guildmasterId,   // NPC UUID
        long lastQuestRefresh
) {
    public static final Codec<GuildData> CODEC =
            RecordCodecBuilder.create(instance -> instance.group(
                    Codec.STRING.xmap(UUID::fromString, UUID::toString)
                            .fieldOf("guildId").forGetter(GuildData::guildId),
                    Codec.STRING.xmap(UUID::fromString, UUID::toString)
                            .fieldOf("villageId").forGetter(GuildData::villageId),
                    Codec.STRING.xmap(UUID::fromString, UUID::toString)
                            .optionalFieldOf("guildmasterId",
                                    UUID.fromString("00000000-0000-0000-0000-000000000000"))
                            .forGetter(GuildData::guildmasterId),
                    Codec.LONG.optionalFieldOf("lastQuestRefresh", 0L)
                            .forGetter(GuildData::lastQuestRefresh)
            ).apply(instance, GuildData::new));

    public GuildData withRefresh(long tick) {
        return new GuildData(guildId, villageId, guildmasterId, tick);
    }

    public GuildData withGuildmaster(UUID id) {
        return new GuildData(guildId, villageId, id, lastQuestRefresh);
    }

}