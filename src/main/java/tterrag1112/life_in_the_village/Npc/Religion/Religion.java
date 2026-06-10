package tterrag1112.life_in_the_village.Npc.Religion;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import tterrag1112.life_in_the_village.Npc.Letters.BookCategory;

import java.util.List;

/**
 * Static religion record. Spec line 16.
 *
 * <p>Definitions live in {@link ReligionRegistry}; instances are
 * shared across every village that worships them. The codec ships
 * for forward compatibility with content-pack-defined religions
 * (Phase 5) — v1 only persists the {@code id} on
 * {@link PietyComponent#beliefs} entries.</p>
 *
 * @param id                       stable string id (e.g. "sunstead")
 * @param displayName              UI label
 * @param coreTenets               flavor strings; surfaced in the
 *                                 priest's confession dialogue
 * @param rites                    every {@link Rite} the religion
 *                                 ritualises; rites missing here are
 *                                 ignored when this religion is the
 *                                 village's primary
 * @param sacredLocations          tag list — Phase 5 worldgen may
 *                                 elevate these block tags
 * @param calendar                 holy-day map
 * @param preferredBookCategories  drives the temple library's book
 *                                 stocking pass when scribal +
 *                                 religion meet
 * @param godIds                   F1a — the ordered god ids this religion
 *                                 venerates (first = primary). All four
 *                                 starters are single-god today; pantheons
 *                                 come later. Resolve via {@link GodRegistry}.
 */
public record Religion(
        String id,
        String displayName,
        List<String> coreTenets,
        List<Rite> rites,
        List<String> sacredLocations,
        ReligiousCalendar calendar,
        List<BookCategory> preferredBookCategories,
        List<String> godIds
) {
    public Religion {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id required");
        if (displayName == null) displayName = id;
        coreTenets               = coreTenets               == null ? List.of() : List.copyOf(coreTenets);
        rites                    = rites                    == null ? List.of() : List.copyOf(rites);
        sacredLocations          = sacredLocations          == null ? List.of() : List.copyOf(sacredLocations);
        if (calendar == null) calendar = new ReligiousCalendar(java.util.Map.of());
        preferredBookCategories  = preferredBookCategories  == null ? List.of() : List.copyOf(preferredBookCategories);
        godIds                   = godIds                   == null ? List.of() : List.copyOf(godIds);
    }

    public boolean ritualises(Rite r) { return rites.contains(r); }

    // Codec — now 8 fields (still under the 16-field RecordCodecBuilder ceiling).
    // F1b's per-world fields will eat the remaining headroom; nest into a
    // sub-record then if needed. F1a cleanup dropped the legacy "deity" field —
    // the deity identity now lives on the first-class God (see GodRegistry); a
    // pre-cleanup save's stored "deity" key is simply ignored on load.
    public static final Codec<Religion> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("id").forGetter(Religion::id),
            Codec.STRING.fieldOf("displayName").forGetter(Religion::displayName),
            Codec.STRING.listOf().optionalFieldOf("coreTenets", List.of()).forGetter(Religion::coreTenets),
            Rite.CODEC.listOf().optionalFieldOf("rites", List.of()).forGetter(Religion::rites),
            Codec.STRING.listOf().optionalFieldOf("sacredLocations", List.of()).forGetter(Religion::sacredLocations),
            ReligiousCalendar.CODEC.optionalFieldOf("calendar", new ReligiousCalendar(java.util.Map.of()))
                    .forGetter(Religion::calendar),
            Codec.STRING.xmap(BookCategory::valueOf, BookCategory::name).listOf()
                    .optionalFieldOf("preferredBookCategories", List.of())
                    .forGetter(Religion::preferredBookCategories),
            Codec.STRING.listOf().optionalFieldOf("godIds", List.of()).forGetter(Religion::godIds)
    ).apply(i, Religion::new));
}
