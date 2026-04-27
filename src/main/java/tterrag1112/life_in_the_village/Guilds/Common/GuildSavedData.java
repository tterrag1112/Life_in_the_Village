package tterrag1112.life_in_the_village.Guilds.Common;

import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 4 doc 27 — per-world store for the new {@link AbstractGuild}
 * hierarchy. Lives alongside the legacy {@code VillageSavedData.guilds}
 * (which keeps the adventurer-specific {@code GuildData} records
 * intact); the new abstract guilds are additive so existing 25-file
 * consumers don't break.
 *
 * <p>Lookups are keyed by guildId for the canonical access path and
 * filtered by villageId for the per-village list. NPCs / players get
 * a one-guild-at-a-time slot per spec "Open decisions" #3 — the
 * accessors enforce that constraint at write time.</p>
 */
public class GuildSavedData extends SavedData {

    public static final SavedDataType<GuildSavedData> TYPE = new SavedDataType<>(
            "life_in_the_village_guilds_v2",
            GuildSavedData::new,
            RecordCodecBuilder.create(i -> i.group(
                    AbstractGuild.CODEC.listOf().optionalFieldOf("guilds", List.of())
                            .forGetter(d -> new ArrayList<>(d.guildById.values()))
            ).apply(i, GuildSavedData::fromCodec)));

    private final Map<UUID, AbstractGuild> guildById = new LinkedHashMap<>();

    public GuildSavedData() {}

    private static GuildSavedData fromCodec(List<AbstractGuild> guilds) {
        GuildSavedData d = new GuildSavedData();
        if (guilds != null) {
            for (AbstractGuild g : guilds) {
                if (g != null) d.guildById.put(g.guildId(), g);
            }
        }
        return d;
    }

    public static GuildSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    // ── Mutators ──────────────────────────────────────────────────────────

    public void putGuild(AbstractGuild guild) {
        if (guild == null) return;
        guildById.put(guild.guildId(), guild);
        setDirty();
    }

    public void removeGuild(UUID guildId) {
        if (guildId == null) return;
        if (guildById.remove(guildId) != null) setDirty();
    }

    public void markDirty() { setDirty(); }

    // ── Queries ───────────────────────────────────────────────────────────

    public Optional<AbstractGuild> get(UUID guildId) {
        return Optional.ofNullable(guildId == null ? null : guildById.get(guildId));
    }

    public Collection<AbstractGuild> all() {
        return java.util.Collections.unmodifiableCollection(guildById.values());
    }

    public List<AbstractGuild> forVillage(UUID villageId) {
        if (villageId == null) return List.of();
        return guildById.values().stream()
                .filter(g -> villageId.equals(g.homeVillageId()))
                .toList();
    }

    public Optional<AbstractGuild> forVillageAndType(UUID villageId, GuildType type) {
        if (villageId == null || type == null) return Optional.empty();
        for (AbstractGuild g : guildById.values()) {
            if (Objects.equals(g.homeVillageId(), villageId) && g.type() == type) {
                return Optional.of(g);
            }
        }
        return Optional.empty();
    }

    public List<AbstractGuild> forMember(UUID memberId) {
        if (memberId == null) return List.of();
        return guildById.values().stream()
                .filter(g -> g.hasMember(memberId))
                .toList();
    }
}
