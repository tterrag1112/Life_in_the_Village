package tterrag1112.life_in_the_village.Npc.Religion;

import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Per-world persistent rite ledger. Spec line 205 — also parks the
 * player-piety values keyed by player UUID since v1 doesn't ship a
 * separate player-data attachment for religion.
 */
public class RiteSavedData extends SavedData {

    public static final SavedDataType<RiteSavedData> TYPE = new SavedDataType<>(
            "life_in_the_village_rites",
            RiteSavedData::new,
            RecordCodecBuilder.create(i -> i.group(
                    RiteExecution.CODEC.listOf().optionalFieldOf("rites", List.of())
                            .forGetter(d -> new ArrayList<>(d.rites.values())),
                    com.mojang.serialization.Codec.unboundedMap(
                                    net.minecraft.core.UUIDUtil.CODEC,
                                    PietyComponent.CODEC)
                            .optionalFieldOf("playerPiety", Map.of())
                            .forGetter(d -> Map.copyOf(d.playerPiety))
            ).apply(i, RiteSavedData::fromCodec)));

    private final Map<UUID, RiteExecution>     rites       = new HashMap<>();
    private final Map<UUID, PietyComponent>    playerPiety = new HashMap<>();

    public RiteSavedData() {}

    private static RiteSavedData fromCodec(List<RiteExecution> rites,
                                           Map<UUID, PietyComponent> playerPiety) {
        RiteSavedData d = new RiteSavedData();
        if (rites != null) for (RiteExecution r : rites) d.rites.put(r.riteId(), r);
        if (playerPiety != null) d.playerPiety.putAll(playerPiety);
        return d;
    }

    public static RiteSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    // ── Rites ──────────────────────────────────────────────────────────────

    public void putRite(RiteExecution r) {
        if (r != null) { rites.put(r.riteId(), r); setDirty(); }
    }

    public Optional<RiteExecution> getRite(UUID id) {
        return Optional.ofNullable(rites.get(id));
    }

    public List<RiteExecution> all() { return List.copyOf(rites.values()); }

    public List<RiteExecution> dueRites(long currentTick) {
        return rites.values().stream()
                .filter(r -> r.outcome() == RiteOutcome.PENDING)
                .filter(r -> r.scheduledTick() <= currentTick)
                .toList();
    }

    public List<RiteExecution> ritesForVillage(UUID villageId) {
        if (villageId == null) return List.of();
        return rites.values().stream()
                .filter(r -> villageId.equals(r.villageId()))
                .toList();
    }

    // ── Player piety ────────────────────────────────────────────────────────

    public PietyComponent getOrCreatePlayerPiety(UUID playerId) {
        return playerPiety.computeIfAbsent(playerId, k -> {
            PietyComponent p = new PietyComponent();
            // Default unaffiliated; verbs / rite attendance grow it.
            return p;
        });
    }

    public Optional<PietyComponent> getPlayerPiety(UUID playerId) {
        return Optional.ofNullable(playerPiety.get(playerId));
    }

    public Map<UUID, PietyComponent> allPlayerPieties() {
        return java.util.Collections.unmodifiableMap(playerPiety);
    }

    public void markDirty() { setDirty(); }
}
