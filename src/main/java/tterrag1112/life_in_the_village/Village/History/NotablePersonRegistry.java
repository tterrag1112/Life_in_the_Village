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
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 4 doc 30 lines 248-266 — single-world registry of every NPC
 * who reached legendary status during their life. Keyed by NPC UUID
 * so multiple notable acts collapse into one record (the same
 * person can be both a long-serving leader and a prestigious
 * author).
 *
 * <p>Producers call {@link #recordNotable} from the matching
 * archival hook (e.g. an NPC whose {@code AuthorStatus} prestige
 * crosses 40 fires here); the record stays even after the NPC is
 * evicted so kingdom-history queries still resolve names.</p>
 */
public class NotablePersonRegistry extends SavedData {

    public static final SavedDataType<NotablePersonRegistry> TYPE = new SavedDataType<>(
            "life_in_the_village_notable_persons",
            NotablePersonRegistry::new,
            RecordCodecBuilder.create(i -> i.group(
                    NotablePerson.CODEC.listOf().optionalFieldOf("notable", List.of())
                            .forGetter(d -> new ArrayList<>(d.byNpc.values()))
            ).apply(i, NotablePersonRegistry::fromCodec)));

    private final Map<UUID, NotablePerson> byNpc = new LinkedHashMap<>();

    public NotablePersonRegistry() {}

    private static NotablePersonRegistry fromCodec(List<NotablePerson> seed) {
        NotablePersonRegistry r = new NotablePersonRegistry();
        if (seed != null) for (NotablePerson p : seed) {
            if (p != null) r.byNpc.put(p.npcId(), p);
        }
        return r;
    }

    public static NotablePersonRegistry get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    public void markDirty() { setDirty(); }

    // ── Mutation ──────────────────────────────────────────────────────────

    /**
     * Adds or upgrades a notable-person entry. If the NPC already
     * has a record, the new achievement string is appended and the
     * peak-tick advances to the supplied value.
     */
    public NotablePerson recordNotable(UUID npcId, String npcName, String reason,
                                       long peakAchievementTick,
                                       String achievementLine) {
        if (npcId == null) return null;
        NotablePerson existing = byNpc.get(npcId);
        NotablePerson updated;
        if (existing == null) {
            List<String> achievements = achievementLine == null || achievementLine.isEmpty()
                    ? List.of() : List.of(achievementLine);
            updated = new NotablePerson(npcId, npcName != null ? npcName : "",
                    reason != null ? reason : "",
                    peakAchievementTick, List.of(), achievements);
        } else {
            updated = existing
                    .withAchievement(achievementLine)
                    .withOffice("");  // no-op office bump; preserves existing offices
            updated = new NotablePerson(updated.npcId(),
                    !npcName.isEmpty() ? npcName : updated.npcName(),
                    !reason.isEmpty() ? reason : updated.notabilityReason(),
                    Math.max(updated.peakAchievementTick(), peakAchievementTick),
                    updated.relevantOffices(), updated.achievements());
        }
        byNpc.put(npcId, updated);
        setDirty();
        return updated;
    }

    public void recordOfficeHeld(UUID npcId, String officeId) {
        if (npcId == null || officeId == null || officeId.isEmpty()) return;
        NotablePerson existing = byNpc.get(npcId);
        if (existing == null) return;
        NotablePerson updated = existing.withOffice(officeId);
        if (updated != existing) {
            byNpc.put(npcId, updated);
            setDirty();
        }
    }

    // ── Queries ───────────────────────────────────────────────────────────

    public Optional<NotablePerson> get(UUID npcId) {
        return Optional.ofNullable(npcId == null ? null : byNpc.get(npcId));
    }

    public Collection<NotablePerson> all() {
        return java.util.Collections.unmodifiableCollection(byNpc.values());
    }

    public boolean isNotable(UUID npcId) {
        return npcId != null && byNpc.containsKey(npcId);
    }

    /** Most-recent {@code count} notable persons by peak tick. */
    public List<NotablePerson> mostRecent(int count) {
        return byNpc.values().stream()
                .sorted((a, b) -> Long.compare(b.peakAchievementTick(), a.peakAchievementTick()))
                .limit(Math.max(0, count))
                .toList();
    }
}
