package tterrag1112.life_in_the_village.Village.History;

import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 4 doc 30 lines 108-132 — per-village append-only log of
 * notable events. Lives as a single per-world SavedData with a
 * {@code Map<villageId, List<HistoryEntry>>} so the spec's
 * one-log-per-village shape is preserved without paying for one
 * SavedData slot per village.
 *
 * <p>Eviction policy per spec lines 138-145:</p>
 * <ul>
 *   <li>{@link HistoryImportance#LEGENDARY} / {@link HistoryImportance#MAJOR}
 *   never pruned.</li>
 *   <li>{@link HistoryImportance#NOTABLE} kept ≤ 2 in-game years.</li>
 *   <li>{@link HistoryImportance#MINOR} kept ≤ 1 in-game year, with
 *   a 200-MINOR cap that drops oldest first.</li>
 *   <li>Total cap 1000 entries per village; excess MINOR pruned
 *   first.</li>
 * </ul>
 */
public class VillageHistoryLog extends SavedData {

    /**
     * String-form UUID codec for use as map keys.
     *
     * <p>{@code UUIDUtil.CODEC} produces an int-array NBT tag (4 ints).
     * That works fine when a UUID is a record FIELD (nested inside a
     * CompoundTag), but it crashes when used as a {@code Codec.unboundedMap}
     * KEY because CompoundTag keys must be strings — the encoder pulls the
     * key tag through {@code getStringValue}, which rejects IntArrayTag
     * with the error "Not a string". Use the string form for map keys.</p>
     */
    private static final com.mojang.serialization.Codec<UUID> UUID_STRING_KEY =
            com.mojang.serialization.Codec.STRING.xmap(UUID::fromString, UUID::toString);

    public static final SavedDataType<VillageHistoryLog> TYPE = new SavedDataType<>(
            "life_in_the_village_history",
            VillageHistoryLog::new,
            RecordCodecBuilder.create(i -> i.group(
                    com.mojang.serialization.Codec.unboundedMap(
                                    UUID_STRING_KEY,
                                    HistoryEntry.CODEC.listOf())
                            .optionalFieldOf("byVillage", Map.of())
                            .forGetter(d -> snapshot(d.byVillage))
            ).apply(i, VillageHistoryLog::fromCodec)));

    /** Spec line 144 — total per-village cap. */
    public static final int  PER_VILLAGE_CAP        = 1000;
    /** Spec line 142 — MINOR-tier cap. */
    public static final int  MINOR_CAP              = 200;

    private final Map<UUID, List<HistoryEntry>> byVillage = new LinkedHashMap<>();

    public VillageHistoryLog() {}

    private static VillageHistoryLog fromCodec(Map<UUID, List<HistoryEntry>> seed) {
        VillageHistoryLog l = new VillageHistoryLog();
        if (seed != null) seed.forEach((k, v) ->
                l.byVillage.put(k, v == null ? new ArrayList<>() : new ArrayList<>(v)));
        return l;
    }

    private static Map<UUID, List<HistoryEntry>> snapshot(Map<UUID, List<HistoryEntry>> live) {
        Map<UUID, List<HistoryEntry>> copy = new LinkedHashMap<>();
        live.forEach((k, v) -> copy.put(k, List.copyOf(v)));
        return copy;
    }

    public static VillageHistoryLog get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    public void markDirty() { setDirty(); }

    // ── Mutation ──────────────────────────────────────────────────────────

    /**
     * Appends an entry to the village's log. Dedupe per spec line
     * 348-349 — silently drops entries whose
     * {@link HistoryEntry#isSemanticDuplicate} matches the most
     * recent existing record.
     */
    public boolean add(HistoryEntry entry) {
        if (entry == null) return false;
        List<HistoryEntry> list = byVillage.computeIfAbsent(entry.villageId(),
                k -> new ArrayList<>());
        if (!list.isEmpty()) {
            HistoryEntry tail = list.get(list.size() - 1);
            if (entry.isSemanticDuplicate(tail)) return false;
        }
        list.add(entry);
        setDirty();
        return true;
    }

    /** Convenience: build + add via {@link HistoryEntry#create}. */
    public boolean record(HistoryEventType type, UUID villageId, long tick,
                          Map<String, String> details, List<UUID> relatedNpcs) {
        return add(HistoryEntry.create(type, villageId, tick, details, relatedNpcs));
    }

    /** Same as {@link #record} but with an explicit importance
     *  override. */
    public boolean record(HistoryEventType type, UUID villageId, long tick,
                          Map<String, String> details, List<UUID> relatedNpcs,
                          HistoryImportance importance) {
        return add(HistoryEntry.of(type, villageId, tick, tick, details,
                relatedNpcs, importance));
    }

    // ── Queries ───────────────────────────────────────────────────────────

    public List<HistoryEntry> all(UUID villageId) {
        if (villageId == null) return List.of();
        List<HistoryEntry> list = byVillage.get(villageId);
        return list == null ? List.of() : List.copyOf(list);
    }

    public List<HistoryEntry> recent(UUID villageId, int count) {
        List<HistoryEntry> list = byVillage.get(villageId);
        if (list == null || list.isEmpty()) return List.of();
        int from = Math.max(0, list.size() - Math.max(0, count));
        return List.copyOf(list.subList(from, list.size()));
    }

    public List<HistoryEntry> byType(UUID villageId, HistoryEventType type) {
        if (villageId == null || type == null) return List.of();
        List<HistoryEntry> list = byVillage.get(villageId);
        if (list == null) return List.of();
        return list.stream().filter(e -> e.type() == type).toList();
    }

    public List<HistoryEntry> byImportance(UUID villageId, HistoryImportance min) {
        if (villageId == null) return List.of();
        List<HistoryEntry> list = byVillage.get(villageId);
        if (list == null) return List.of();
        return list.stream().filter(e -> e.importance().atLeast(min)).toList();
    }

    public List<HistoryEntry> byNpc(UUID villageId, UUID npcId) {
        if (villageId == null || npcId == null) return List.of();
        List<HistoryEntry> list = byVillage.get(villageId);
        if (list == null) return List.of();
        return list.stream().filter(e -> e.relatedNpcIds().contains(npcId)).toList();
    }

    public List<HistoryEntry> inRange(UUID villageId, long startTick, long endTick) {
        List<HistoryEntry> list = byVillage.get(villageId);
        if (list == null) return List.of();
        return list.stream()
                .filter(e -> e.tick() >= startTick && e.tick() <= endTick)
                .toList();
    }

    /** Spec line 213-220: kingdom-level history compilation. Walks
     *  every village's log and keeps the entries whose type
     *  propagates per {@link HistoryEventType#propagatesToKingdom}
     *  filtered to the supplied village ids. */
    public List<HistoryEntry> kingdomCompilation(Collection<UUID> villageIds) {
        if (villageIds == null || villageIds.isEmpty()) return List.of();
        List<HistoryEntry> out = new ArrayList<>();
        for (UUID vid : villageIds) {
            List<HistoryEntry> list = byVillage.get(vid);
            if (list == null) continue;
            for (HistoryEntry e : list) {
                if (e.type().propagatesToKingdom()) out.add(e);
            }
        }
        out.sort((a, b) -> Long.compare(a.tick(), b.tick()));
        return out;
    }

    // ── Eviction ──────────────────────────────────────────────────────────

    /** Daily-tick entry point. Walks each village's log, drops
     *  entries past their importance retention window, then enforces
     *  the per-village + MINOR caps. */
    public void prune(long currentTick) {
        boolean dirty = false;
        for (List<HistoryEntry> list : byVillage.values()) {
            int before = list.size();
            list.removeIf(e -> {
                if (!e.importance().isPrunable()) return false;
                long age = currentTick - e.tick();
                return age > e.importance().retentionTicks();
            });
            // MINOR cap.
            int minorCount = (int) list.stream()
                    .filter(e -> e.importance() == HistoryImportance.MINOR)
                    .count();
            int minorOver = minorCount - MINOR_CAP;
            if (minorOver > 0) dropOldest(list, HistoryImportance.MINOR, minorOver);
            // Total cap — drop oldest MINOR first, then NOTABLE.
            int totalOver = list.size() - PER_VILLAGE_CAP;
            if (totalOver > 0) dropOldest(list, HistoryImportance.MINOR, totalOver);
            totalOver = list.size() - PER_VILLAGE_CAP;
            if (totalOver > 0) dropOldest(list, HistoryImportance.NOTABLE, totalOver);
            if (list.size() != before) dirty = true;
        }
        if (dirty) setDirty();
    }

    /** Force-prune one village. Used by the /history prune debug. */
    public int pruneVillage(UUID villageId, long currentTick) {
        List<HistoryEntry> list = byVillage.get(villageId);
        if (list == null) return 0;
        int before = list.size();
        list.removeIf(e -> {
            if (!e.importance().isPrunable()) return false;
            long age = currentTick - e.tick();
            return age > e.importance().retentionTicks();
        });
        int removed = before - list.size();
        if (removed > 0) setDirty();
        return removed;
    }

    private static void dropOldest(List<HistoryEntry> list, HistoryImportance tier,
                                   int howMany) {
        if (howMany <= 0) return;
        for (int i = 0; i < list.size() && howMany > 0; ) {
            if (list.get(i).importance() == tier) {
                list.remove(i);
                howMany--;
            } else {
                i++;
            }
        }
    }
}
