package tterrag1112.life_in_the_village.Quests;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import tterrag1112.life_in_the_village.Guilds.Adventurer.GuildRank;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * F2a-1 — the per-player quest store. Mirrors the established SavedData idiom
 * ({@code RiteSavedData}/{@code SacredSpaceSavedData}): own {@link SavedDataType} +
 * storage name, codec, {@code markDirty}. Holds every {@link Quest} a player has
 * (status distinguishes active from completed/terminal). Additive — separate from the
 * legacy guild quest system.
 */
public class QuestSavedData extends SavedData {

    private static final String STORAGE = "life_in_the_village_quests";

    private static final Codec<UUID> UUID_STRING =
            Codec.STRING.xmap(UUID::fromString, UUID::toString);

    public static final SavedDataType<QuestSavedData> TYPE = new SavedDataType<>(
            STORAGE,
            QuestSavedData::new,
            RecordCodecBuilder.create(i -> i.group(
                    Codec.unboundedMap(UUID_STRING, Quest.CODEC.listOf())
                            .optionalFieldOf("questsByPlayer", Map.of())
                            .forGetter(d -> Map.copyOf(d.questsByPlayer)),
                    // F2a-3 — per-player giver standing: playerId → (giverKey → completed
                    // count). The player religious-career spine. Empty by default.
                    Codec.unboundedMap(UUID_STRING,
                                    Codec.unboundedMap(Codec.STRING, Codec.INT))
                            .optionalFieldOf("standingByPlayer", Map.of())
                            .forGetter(d -> Map.copyOf(d.standingByPlayer))
            ).apply(i, QuestSavedData::fromCodec)));

    /** playerId → all their quests (active + terminal; status distinguishes). */
    private final Map<UUID, List<Quest>> questsByPlayer = new LinkedHashMap<>();
    /** F2a-3 — playerId → (giverKey "TYPE:id" → quests completed for that giver). */
    private final Map<UUID, Map<String, Integer>> standingByPlayer = new LinkedHashMap<>();

    public QuestSavedData() {}

    private static QuestSavedData fromCodec(Map<UUID, List<Quest>> loaded,
                                           Map<UUID, Map<String, Integer>> standing) {
        QuestSavedData d = new QuestSavedData();
        if (loaded != null) loaded.forEach((k, v) -> d.questsByPlayer.put(k, new ArrayList<>(v)));
        if (standing != null) standing.forEach((k, v) -> d.standingByPlayer.put(k, new LinkedHashMap<>(v)));
        return d;
    }

    public static QuestSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    // ── Reads ────────────────────────────────────────────────────────────────

    /** Every quest the player holds (active + terminal). */
    public List<Quest> questsOf(UUID playerId) {
        List<Quest> list = playerId == null ? null : questsByPlayer.get(playerId);
        return list == null ? List.of() : List.copyOf(list);
    }

    /** The player's ACTIVE quests. */
    public List<Quest> active(UUID playerId) {
        List<Quest> out = new ArrayList<>();
        for (Quest q : questsOf(playerId)) if (q.status() == QuestStatus.ACTIVE) out.add(q);
        return out;
    }

    public Optional<Quest> quest(UUID playerId, UUID questId) {
        for (Quest q : questsOf(playerId)) if (q.questId().equals(questId)) return Optional.of(q);
        return Optional.empty();
    }

    /** True when the player has an ACTIVE quest from a giver of {@code type} (used to
     *  avoid stacking divine callings). */
    public boolean hasActiveGiverQuest(UUID playerId, QuestGiver.Type type) {
        for (Quest q : active(playerId)) if (q.giver().type() == type) return true;
        return false;
    }

    // ── Giver standing (F2a-3 — the player religious career) ─────────────────

    /** The canonical giver-standing key: "TYPE:id" (e.g. "DIVINE:sun_mother"). */
    public static String giverKey(QuestGiver giver) {
        return giver.type().name() + ":" + giver.id();
    }

    /** Quests {@code playerId} has completed for {@code giverKey}. */
    public int standing(UUID playerId, String giverKey) {
        Map<String, Integer> m = standingByPlayer.get(playerId);
        return m == null ? 0 : m.getOrDefault(giverKey, 0);
    }

    /** The player's standing map (giverKey → count), read-only. */
    public Map<String, Integer> standings(UUID playerId) {
        Map<String, Integer> m = standingByPlayer.get(playerId);
        return m == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(m));
    }

    /** Accrues one completed quest toward {@code giver}'s standing for {@code playerId}. */
    public void accrueStanding(UUID playerId, QuestGiver giver) {
        if (playerId == null || giver == null) return;
        standingByPlayer.computeIfAbsent(playerId, k -> new LinkedHashMap<>())
                .merge(giverKey(giver), 1, Integer::sum);
        setDirty();
    }

    // ── Writes ───────────────────────────────────────────────────────────────

    /** Issues a quest to a player (added in its current status). */
    public void add(UUID playerId, Quest quest) {
        if (playerId == null || quest == null) return;
        questsByPlayer.computeIfAbsent(playerId, k -> new ArrayList<>()).add(quest);
        setDirty();
    }

    /** Replaces the player's quest of the same {@code questId} (immutable-quest update). */
    public void replace(UUID playerId, Quest quest) {
        if (playerId == null || quest == null) return;
        List<Quest> list = questsByPlayer.get(playerId);
        if (list == null) return;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).questId().equals(quest.questId())) { list.set(i, quest); setDirty(); return; }
        }
    }

    // ── Guild quest pool (F2b-2) ─────────────────────────────────────────────
    // The legacy guild model is a per-GUILD shared AVAILABLE pool that members accept
    // from (rank-gated); F2 storage is per-UUID. The re-seat reuses that per-UUID map,
    // keying a guild's OFFERED pool under the guildId (a UUID) with giver GUILD:<guildId>.
    // Accepting moves an offer out of the pool into the player's ACTIVE list.

    /** A guild's OFFERED pool (every offer currently posted for {@code guildId}). */
    public List<Quest> guildOffers(UUID guildId) {
        List<Quest> out = new ArrayList<>();
        for (Quest q : questsOf(guildId)) if (q.status() == QuestStatus.OFFERED) out.add(q);
        return out;
    }

    /** The guild's OFFERED pool filtered to what {@code rank} may accept (rankRequirement). */
    public List<Quest> availableGuildQuests(UUID guildId, GuildRank rank) {
        List<Quest> out = new ArrayList<>();
        for (Quest q : guildOffers(guildId)) {
            if (q.rankRequirement().map(req -> rank.ordinal() >= req.ordinal()).orElse(true)) out.add(q);
        }
        return out;
    }

    /** A specific OFFERED quest in {@code guildId}'s pool. */
    public Optional<Quest> guildOffer(UUID guildId, UUID questId) {
        for (Quest q : guildOffers(guildId)) if (q.questId().equals(questId)) return Optional.of(q);
        return Optional.empty();
    }

    /** Posts a quest to a guild's OFFERED pool. */
    public void offerGuildQuest(UUID guildId, Quest quest) { add(guildId, quest); }

    /** Removes an offer from a guild's pool (e.g. on accept). */
    public void removeGuildOffer(UUID guildId, UUID questId) {
        List<Quest> list = questsByPlayer.get(guildId);
        if (list != null && list.removeIf(q -> q.questId().equals(questId))) setDirty();
    }

    /** Drops OFFERED pool entries whose deadline has passed. */
    public void clearExpiredGuildOffers(UUID guildId, long now) {
        List<Quest> list = questsByPlayer.get(guildId);
        if (list != null && list.removeIf(q -> q.status() == QuestStatus.OFFERED
                && q.deadlineTick() > 0 && now > q.deadlineTick())) setDirty();
    }

    /** A player's ACTIVE quests issued by a guild (giver type GUILD). */
    public List<Quest> activeGuildQuests(UUID playerId) {
        List<Quest> out = new ArrayList<>();
        for (Quest q : active(playerId)) if (q.giver().type() == QuestGiver.Type.GUILD) out.add(q);
        return out;
    }

    public void markDirty() { setDirty(); }
}
