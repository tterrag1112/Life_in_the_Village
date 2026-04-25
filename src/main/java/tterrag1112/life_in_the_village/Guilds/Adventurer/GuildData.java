package tterrag1112.life_in_the_village.Guilds.Adventurer;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import tterrag1112.life_in_the_village.Npc.Office.OfficeHolding;
import tterrag1112.life_in_the_village.Npc.Office.OfficeRegistry;
import tterrag1112.life_in_the_village.Npc.Office.OfficeState;
import tterrag1112.life_in_the_village.Npc.Office.OrgType;
import tterrag1112.life_in_the_village.Npc.Office.SelectionMethod;

import java.util.UUID;

public record GuildData(
        UUID guildId,
        UUID villageId,
        UUID guildmasterId,   // NPC UUID — legacy field, kept readable for one release
        long lastQuestRefresh,
        OfficeState offices
) {
    /** Sentinel UUID used by older saves to mean "no guildmaster yet". */
    public static final UUID NO_GUILDMASTER =
            UUID.fromString("00000000-0000-0000-0000-000000000000");

    /**
     * Convenience for newly-created guilds. Mirrors the legacy 4-arg
     * constructor; offices is freshly empty for the guild's id.
     */
    public GuildData(UUID guildId, UUID villageId, UUID guildmasterId, long lastQuestRefresh) {
        this(guildId, villageId, guildmasterId, lastQuestRefresh,
                OfficeState.emptyFor(OrgType.GUILD, guildId));
    }

    public static final Codec<GuildData> CODEC =
            RecordCodecBuilder.create(instance -> instance.group(
                    Codec.STRING.xmap(UUID::fromString, UUID::toString)
                            .fieldOf("guildId").forGetter(GuildData::guildId),
                    Codec.STRING.xmap(UUID::fromString, UUID::toString)
                            .fieldOf("villageId").forGetter(GuildData::villageId),
                    Codec.STRING.xmap(UUID::fromString, UUID::toString)
                            .optionalFieldOf("guildmasterId", NO_GUILDMASTER)
                            .forGetter(GuildData::guildmasterId),
                    Codec.LONG.optionalFieldOf("lastQuestRefresh", 0L)
                            .forGetter(GuildData::lastQuestRefresh),
                    OfficeState.CODEC.optionalFieldOf("offices")
                            .forGetter(g -> java.util.Optional.ofNullable(g.offices))
            ).apply(instance, (guildId, villageId, guildmasterId, lastQuestRefresh, offices) -> {
                OfficeState resolved = offices.orElseGet(() -> {
                    OfficeState fresh = OfficeState.emptyFor(OrgType.GUILD, guildId);
                    if (guildmasterId != null && !guildmasterId.equals(NO_GUILDMASTER)) {
                        fresh.set(OfficeRegistry.GUILD_MASTER,
                                OfficeHolding.heldByNpc(OfficeRegistry.GUILD_MASTER,
                                        guildId, guildmasterId, 0L, 0L,
                                        SelectionMethod.MERITOCRATIC));
                    }
                    return fresh;
                });
                return new GuildData(guildId, villageId, guildmasterId, lastQuestRefresh, resolved);
            }));

    public GuildData withRefresh(long tick) {
        return new GuildData(guildId, villageId, guildmasterId, tick, offices);
    }

    public GuildData withGuildmaster(UUID id) {
        return new GuildData(guildId, villageId, id, lastQuestRefresh, offices);
    }
}
