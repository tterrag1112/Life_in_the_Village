package tterrag1112.life_in_the_village.Npc.Health;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
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
 * Per-world health-system persistence. Spec line 198.
 *
 * <p>Holds:</p>
 * <ul>
 *   <li>Active {@link Plague} per village (spec line 168).</li>
 *   <li>Player remedy stashes (spec line 195 — players can buy and
 *   carry remedies; v1 stores them here keyed by player UUID).</li>
 * </ul>
 */
public class HealthSavedData extends SavedData {

    public static final SavedDataType<HealthSavedData> TYPE = new SavedDataType<>(
            "life_in_the_village_health",
            HealthSavedData::new,
            RecordCodecBuilder.create(i -> i.group(
                    Plague.CODEC.listOf().optionalFieldOf("plagues", List.of())
                            .forGetter(d -> new ArrayList<>(d.plagueByVillage.values())),
                    Codec.unboundedMap(UUIDUtil.CODEC, HealerInventory.CODEC)
                            .optionalFieldOf("playerRemedies", Map.of())
                            .forGetter(d -> Map.copyOf(d.playerRemedies))
            ).apply(i, HealthSavedData::fromCodec)));

    private final Map<UUID, Plague> plagueByVillage   = new HashMap<>();
    private final Map<UUID, HealerInventory> playerRemedies = new HashMap<>();

    public HealthSavedData() {}

    private static HealthSavedData fromCodec(List<Plague> plagues,
                                             Map<UUID, HealerInventory> playerRemedies) {
        HealthSavedData d = new HealthSavedData();
        if (plagues != null) {
            for (Plague p : plagues) {
                if (p != null && p.isActive()) d.plagueByVillage.put(p.villageId(), p);
            }
        }
        if (playerRemedies != null) d.playerRemedies.putAll(playerRemedies);
        return d;
    }

    public static HealthSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    // ── Plague accessors ───────────────────────────────────────────────────

    public Optional<Plague> getActivePlague(UUID villageId) {
        if (villageId == null) return Optional.empty();
        Plague p = plagueByVillage.get(villageId);
        return (p != null && p.isActive()) ? Optional.of(p) : Optional.empty();
    }

    public List<Plague> activePlagues() {
        return plagueByVillage.values().stream().filter(Plague::isActive).toList();
    }

    public void putPlague(Plague plague) {
        if (plague == null) return;
        if (plague.isActive()) plagueByVillage.put(plague.villageId(), plague);
        else plagueByVillage.remove(plague.villageId());
        setDirty();
    }

    public void clearPlague(UUID villageId) {
        plagueByVillage.remove(villageId);
        setDirty();
    }

    // ── Player remedies ───────────────────────────────────────────────────

    public HealerInventory getOrCreatePlayerStash(UUID playerId) {
        return playerRemedies.computeIfAbsent(playerId, k -> new HealerInventory());
    }

    public Optional<HealerInventory> getPlayerStash(UUID playerId) {
        return Optional.ofNullable(playerRemedies.get(playerId));
    }

    public void markDirty() { setDirty(); }
}
