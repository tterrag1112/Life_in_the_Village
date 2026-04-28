package tterrag1112.life_in_the_village.Village.Decoration.Variants;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tterrag1112.life_in_the_village.Village.Decoration.VillageSizeTier;
import tterrag1112.life_in_the_village.Village.Planning.Zoning.SlotTag;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Doc 15 — parsed contents of a {@code manifest.json} sitting next to
 * the {@code level_{n}.nbt} files of a single building variant.
 *
 * <p>This record is the raw manifest data; the {@code BuildingVariant}
 * record (P0a-03) wraps it with folder-derived context (culture, style,
 * type, variantId). Construction here intentionally avoids coupling to
 * any Building record codec — manifests are reload-time read-only data,
 * not save-data.</p>
 *
 * <p>Defaults applied at parse time follow doc 15's "Missing manifest
 * defaults" plus reasonable fallbacks for fields not enumerated there:</p>
 * <ul>
 *   <li>{@code id} — defaults to the folder name passed in by the loader
 *   <li>{@code displayName} — defaults to {@code id}
 *   <li>{@code minTier} — {@link VillageSizeTier#HAMLET}
 *   <li>{@code maxTier} — {@link VillageSizeTier#CITY}
 *   <li>{@code weight} — {@code 1.0}
 *   <li>{@code preferredTags} — empty
 *   <li>{@code stylePreference} — {@link StylePreference#ANY}
 *   <li>{@code agePreference} — {@link AgePreference#ANY}
 *   <li>{@code tags} — empty
 *   <li>{@code maxPerVillage} — {@code 0} means "no cap"
 *   <li>{@code colorSlots} — {@link ColorSlot#PRIMARY} only
 *   <li>{@code ruinationLevel} — {@code 0.0} (pristine)
 * </ul>
 *
 * <p><b>Footprint:</b> not parsed. Doc 15 §"Footprint resolution"
 * makes the NBT the single source of truth for variant geometry;
 * {@link tterrag1112.life_in_the_village.Village.Planning
 * .StructureSizeCache} measures it from the file. Legacy manifests
 * that still carry a {@code footprint} block load fine — JSON
 * unknown-field tolerance is unchanged — and the value is
 * silently ignored.</p>
 */
public record VariantManifest(
        String id,
        String displayName,
        VillageSizeTier minTier,
        VillageSizeTier maxTier,
        float weight,
        Set<SlotTag> preferredTags,
        StylePreference stylePreference,
        AgePreference agePreference,
        Set<String> tags,
        int maxPerVillage,
        Set<ColorSlot> colorSlots,
        float ruinationLevel) {

    private static final Logger LOGGER = LoggerFactory.getLogger(VariantManifest.class);

    /**
     * Manifest used when no {@code manifest.json} is present for a
     * variant. All non-id fields fall back to their defaults; the
     * variant remains placeable but with no scoring bonuses.
     */
    public static VariantManifest defaults(String variantId) {
        return new VariantManifest(
                variantId,
                variantId,
                VillageSizeTier.HAMLET, VillageSizeTier.CITY,
                1.0f,
                EnumSet.noneOf(SlotTag.class),
                StylePreference.ANY,
                AgePreference.ANY,
                Set.of(),
                0,
                EnumSet.of(ColorSlot.PRIMARY),
                0.0f);
    }

    /**
     * Parses a manifest JSON object. Missing fields fall back to
     * {@link #defaults(String) defaults(folderName)}; fields with
     * unrecognized values log a warning and fall back too.
     *
     * @param folderName the variant folder name — used as the {@code id}
     *                   fallback if the manifest omits the field
     */
    public static VariantManifest parse(JsonObject json, String folderName) {
        VariantManifest d = defaults(folderName);

        String id = optString(json, "id", d.id);
        String displayName = optString(json, "displayName", id);

        VillageSizeTier minTier = optEnum(json, "minTier",
                VillageSizeTier.class, d.minTier);
        VillageSizeTier maxTier = optEnum(json, "maxTier",
                VillageSizeTier.class, d.maxTier);

        float weight = optFloat(json, "weight", d.weight);
        if (weight < 0) weight = 0;

        Set<SlotTag> preferredTags = optEnumSet(json, "preferredTags",
                SlotTag.class);
        StylePreference stylePref = optEnum(json, "stylePreference",
                StylePreference.class, d.stylePreference);
        AgePreference agePref = optEnum(json, "agePreference",
                AgePreference.class, d.agePreference);

        Set<String> tags = optStringSet(json, "tags");

        int maxPerVillage = Math.max(0, optInt(json, "maxPerVillage",
                d.maxPerVillage));

        Set<ColorSlot> colorSlots = optEnumSet(json, "colorSlots",
                ColorSlot.class);
        if (colorSlots.isEmpty() && !json.has("colorSlots")) {
            colorSlots = EnumSet.copyOf(d.colorSlots);
        }

        float ruination = optFloat(json, "ruinationLevel", d.ruinationLevel);
        if (ruination < 0) ruination = 0;
        if (ruination > 1) ruination = 1;

        return new VariantManifest(
                id, displayName,
                minTier, maxTier,
                weight,
                preferredTags,
                stylePref, agePref,
                tags,
                maxPerVillage,
                colorSlots,
                ruination);
    }

    // ── JSON helpers ──────────────────────────────────────────────────────

    private static String optString(JsonObject o, String key, String fallback) {
        JsonElement e = o.get(key);
        return (e != null && e.isJsonPrimitive()) ? e.getAsString() : fallback;
    }

    private static int optInt(JsonObject o, String key, int fallback) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive()) return fallback;
        try { return e.getAsInt(); }
        catch (NumberFormatException ex) { return fallback; }
    }

    private static float optFloat(JsonObject o, String key, float fallback) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive()) return fallback;
        try { return e.getAsFloat(); }
        catch (NumberFormatException ex) { return fallback; }
    }

    private static <E extends Enum<E>> E optEnum(JsonObject o, String key,
                                                 Class<E> type, E fallback) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive()) return fallback;
        String raw = e.getAsString();
        try { return Enum.valueOf(type, raw); }
        catch (IllegalArgumentException ex) {
            LOGGER.warn("VariantManifest: unrecognized {} value '{}' "
                    + "for field '{}', using {}",
                    type.getSimpleName(), raw, key, fallback);
            return fallback;
        }
    }

    private static <E extends Enum<E>> Set<E> optEnumSet(JsonObject o,
                                                         String key,
                                                         Class<E> type) {
        Set<E> result = EnumSet.noneOf(type);
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonArray()) return result;
        for (JsonElement el : e.getAsJsonArray()) {
            if (!el.isJsonPrimitive()) continue;
            String raw = el.getAsString();
            try { result.add(Enum.valueOf(type, raw)); }
            catch (IllegalArgumentException ex) {
                LOGGER.warn("VariantManifest: unrecognized {} value '{}' "
                        + "in field '{}', skipping",
                        type.getSimpleName(), raw, key);
            }
        }
        return result;
    }

    private static Set<String> optStringSet(JsonObject o, String key) {
        Set<String> result = new LinkedHashSet<>();
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonArray()) return result;
        for (JsonElement el : e.getAsJsonArray()) {
            if (el.isJsonPrimitive()) result.add(el.getAsString());
        }
        return result;
    }
}
