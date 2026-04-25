package tterrag1112.life_in_the_village.Npc.Verbs;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Per-NPC log of verb-cooldown expiries, keyed by {@code (verbId, playerId)}
 * so multiple players accrue cooldowns independently.
 *
 * <p>Spec: {@code docs/npc_redesign/09-player-verbs.md} (section
 * "Verb cooldowns" line 240).</p>
 *
 * <p>Stored on each {@link tterrag1112.life_in_the_village.Entities.custom.TownspersonMob}
 * under {@code npcVerbCooldowns}. Lazy-eviction on read keeps the map
 * from accumulating expired entries.</p>
 */
public final class NpcVerbCooldowns {

    private static final String SAVE_KEY = "npcVerbCooldowns";

    /** {@code verbId -> playerId -> expiry tick} per spec NBT layout (line 264). */
    private final Map<String, Map<UUID, Long>> cooldowns = new LinkedHashMap<>();

    public NpcVerbCooldowns() {}

    /** Returns true if the given (verb, player) pair is still under cooldown at {@code now}. */
    public boolean isOnCooldown(String verbId, UUID playerId, long now) {
        Map<UUID, Long> perPlayer = cooldowns.get(verbId);
        if (perPlayer == null) return false;
        Long expiry = perPlayer.get(playerId);
        if (expiry == null) return false;
        if (expiry <= now) {
            perPlayer.remove(playerId);
            return false;
        }
        return true;
    }

    /** Number of ticks remaining until the cooldown expires, or 0. */
    public long ticksRemaining(String verbId, UUID playerId, long now) {
        Map<UUID, Long> perPlayer = cooldowns.get(verbId);
        if (perPlayer == null) return 0L;
        Long expiry = perPlayer.get(playerId);
        if (expiry == null) return 0L;
        return Math.max(0L, expiry - now);
    }

    /**
     * Sets a cooldown. {@code expiry} is the absolute tick at which the
     * verb becomes available again. Same {@code expiry <= now} clears it.
     */
    public void setCooldown(String verbId, UUID playerId, long expiry) {
        if (expiry <= 0L) {
            Map<UUID, Long> perPlayer = cooldowns.get(verbId);
            if (perPlayer != null) perPlayer.remove(playerId);
            return;
        }
        cooldowns.computeIfAbsent(verbId, k -> new HashMap<>()).put(playerId, expiry);
    }

    public void clear(String verbId, UUID playerId) {
        Map<UUID, Long> perPlayer = cooldowns.get(verbId);
        if (perPlayer != null) perPlayer.remove(playerId);
    }

    public void clearAll() { cooldowns.clear(); }

    public Map<String, Map<UUID, Long>> snapshot() {
        Map<String, Map<UUID, Long>> out = new LinkedHashMap<>();
        cooldowns.forEach((k, v) -> out.put(k, Map.copyOf(v)));
        return Map.copyOf(out);
    }

    // ── Persistence ────────────────────────────────────────────────────────

    public void save(ValueOutput output) {
        output.store(SAVE_KEY, CODEC, this);
    }

    public boolean load(ValueInput input) {
        var read = input.read(SAVE_KEY, CODEC);
        if (read.isEmpty()) return false;
        NpcVerbCooldowns loaded = read.get();
        cooldowns.clear();
        loaded.cooldowns.forEach((k, v) -> cooldowns.put(k, new HashMap<>(v)));
        return true;
    }

    // ── Codec ──────────────────────────────────────────────────────────────

    private record Entry(String verbId, UUID playerId, long expiry) {
        static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("verbId").forGetter(Entry::verbId),
                UUIDUtil.CODEC.fieldOf("playerId").forGetter(Entry::playerId),
                Codec.LONG.fieldOf("expiry").forGetter(Entry::expiry)
        ).apply(i, Entry::new));
    }

    public static final Codec<NpcVerbCooldowns> CODEC = RecordCodecBuilder.create(i -> i.group(
            Entry.CODEC.listOf().optionalFieldOf("entries", List.of())
                    .forGetter(c -> {
                        List<Entry> out = new java.util.ArrayList<>();
                        c.cooldowns.forEach((verbId, byPlayer) ->
                                byPlayer.forEach((playerId, expiry) ->
                                        out.add(new Entry(verbId, playerId, expiry))));
                        return out;
                    })
    ).apply(i, NpcVerbCooldowns::fromCodec));

    public static NpcVerbCooldowns fromCodec(List<Entry> entries) {
        NpcVerbCooldowns c = new NpcVerbCooldowns();
        for (Entry e : entries) {
            c.cooldowns.computeIfAbsent(e.verbId(), k -> new HashMap<>())
                    .put(e.playerId(), e.expiry());
        }
        return c;
    }
}
