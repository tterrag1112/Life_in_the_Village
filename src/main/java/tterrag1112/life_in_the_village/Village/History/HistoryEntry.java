package tterrag1112.life_in_the_village.Village.History;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 4 doc 30 lines 26-39 — one archived event in a village's
 * history. Producers build entries via the convenience builders +
 * {@link HistorySummaryTemplates#render}; consumers read
 * {@link #renderedSummary} directly without re-rendering.
 *
 * <p>{@link #details} holds free-form structured key/values that
 * the template uses for substitution. NPC names are cached at
 * record time per spec line 345 so old entries still resolve after
 * the referenced NPC's data is evicted.</p>
 */
public record HistoryEntry(
        UUID entryId,
        HistoryEventType type,
        long tick,
        long recordedTick,
        UUID villageId,
        List<UUID> relatedNpcIds,
        List<UUID> relatedBuildingIds,
        Map<String, String> details,
        String renderedSummary,
        HistoryImportance importance
) {

    public HistoryEntry {
        if (entryId == null) throw new IllegalArgumentException("entryId required");
        if (type == null) throw new IllegalArgumentException("type required");
        if (villageId == null) throw new IllegalArgumentException("villageId required");
        relatedNpcIds      = List.copyOf(relatedNpcIds      != null ? relatedNpcIds      : List.of());
        relatedBuildingIds = List.copyOf(relatedBuildingIds != null ? relatedBuildingIds : List.of());
        details            = Map.copyOf(details             != null ? details            : Map.of());
        if (renderedSummary == null) renderedSummary = "";
        if (importance == null) importance = type.defaultImportance();
    }

    /** Convenience: build a fresh entry, render the summary via the
     *  default template, default importance from the type. */
    public static HistoryEntry create(HistoryEventType type, UUID villageId,
                                      long tick, Map<String, String> details,
                                      List<UUID> relatedNpcs) {
        Map<String, String> safeDetails = details != null
                ? new LinkedHashMap<>(details) : new LinkedHashMap<>();
        String summary = HistorySummaryTemplates.render(type, safeDetails);
        return new HistoryEntry(UUID.randomUUID(), type, tick, tick, villageId,
                relatedNpcs != null ? relatedNpcs : List.of(),
                List.of(), safeDetails, summary, type.defaultImportance());
    }

    /** Same as {@link #create} but with an explicit importance
     *  override and the recorded-tick set to the live tick (which
     *  may differ from the event tick when the producer is
     *  back-filling). */
    public static HistoryEntry of(HistoryEventType type, UUID villageId,
                                  long eventTick, long recordedTick,
                                  Map<String, String> details,
                                  List<UUID> relatedNpcs,
                                  HistoryImportance importance) {
        Map<String, String> safeDetails = details != null
                ? new LinkedHashMap<>(details) : new LinkedHashMap<>();
        String summary = HistorySummaryTemplates.render(type, safeDetails);
        return new HistoryEntry(UUID.randomUUID(), type, eventTick, recordedTick, villageId,
                relatedNpcs != null ? relatedNpcs : List.of(),
                List.of(), safeDetails, summary,
                importance != null ? importance : type.defaultImportance());
    }

    /** True when {@code other} carries the same hash signature for
     *  the dedupe rule per spec line 348-349. */
    public boolean isSemanticDuplicate(HistoryEntry other) {
        if (other == null) return false;
        return type == other.type
                && tick == other.tick
                && relatedNpcIds.equals(other.relatedNpcIds);
    }

    public static final Codec<HistoryEntry> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("entryId").forGetter(HistoryEntry::entryId),
            HistoryEventType.CODEC.fieldOf("type").forGetter(HistoryEntry::type),
            Codec.LONG.fieldOf("tick").forGetter(HistoryEntry::tick),
            Codec.LONG.optionalFieldOf("recordedTick", 0L).forGetter(HistoryEntry::recordedTick),
            UUIDUtil.CODEC.fieldOf("villageId").forGetter(HistoryEntry::villageId),
            UUIDUtil.CODEC.listOf().optionalFieldOf("relatedNpcIds", List.of())
                    .forGetter(HistoryEntry::relatedNpcIds),
            UUIDUtil.CODEC.listOf().optionalFieldOf("relatedBuildingIds", List.of())
                    .forGetter(HistoryEntry::relatedBuildingIds),
            Codec.unboundedMap(Codec.STRING, Codec.STRING)
                    .optionalFieldOf("details", Map.of()).forGetter(HistoryEntry::details),
            Codec.STRING.optionalFieldOf("renderedSummary", "")
                    .forGetter(HistoryEntry::renderedSummary),
            HistoryImportance.CODEC.optionalFieldOf("importance", HistoryImportance.MINOR)
                    .forGetter(HistoryEntry::importance)
    ).apply(i, HistoryEntry::new));
}
