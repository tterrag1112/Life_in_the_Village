package tterrag1112.life_in_the_village.Village.Decoration.Variants;

import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Decoration.VillageSizeTier;
import tterrag1112.life_in_the_village.Village.Planning.Zoning.SlotTag;

import java.util.Set;

/**
 * Doc 15 — one concrete authored variant of a building type.
 *
 * <p>Constructed by {@link VariantRegistry} from a {@link VariantKey}
 * (folder location: culture, style, type, variantId) plus the parsed
 * {@link VariantManifest}. Holds everything {@code BuildingPlacer},
 * {@code CultureResolver}, and the matcher's score function need to
 * act on a single placement candidate.</p>
 *
 * <p><b>Deviation from doc 15's "Data structures" snippet:</b>
 * {@code culture}, {@link Style style}, and {@link BuildingType type}
 * are added so callers don't need a parallel index map to
 * reconstruct the NBT path. The doc's flat
 * {@code Map<BuildingType, List<BuildingVariant>>} doesn't carry
 * culture/style information any other way.</p>
 *
 * <p>Footprint is intentionally <em>not</em> on the variant — see doc
 * 15 §"Footprint resolution". {@code StructureSizeCache} measures it
 * from the NBT and is the single source of truth.</p>
 */
public record BuildingVariant(
        String culture,
        Style style,
        BuildingType type,
        String id,
        String displayName,
        VillageSizeTier minTier,
        VillageSizeTier maxTier,
        float weight,
        Set<SlotTag> preferredTags,
        StylePreference stylePref,
        AgePreference agePref,
        Set<String> tags,
        int maxPerVillage,
        Set<ColorSlot> colorSlots,
        float ruinationLevel) {

    /**
     * The default-variant id for a {@link BuildingType} — doc 15:
     * "the variant defaults to the type name, so a HOUSE becomes
     * {@code house/house/level_n}".
     */
    public static String defaultVariantId(BuildingType type) {
        return type.name().toLowerCase();
    }

    // ── Piece variant ids (A1 stage 2 — terrace segments) ────────────────

    /**
     * Delimiter for PIECE variant ids of the form
     * {@code <variantFolder>:<piece>} (e.g. {@code row_house:left}).
     * A piece id names the authored file
     * {@code .../<type>/<variantFolder>/<piece>_level_<n>.nbt} instead
     * of the plain {@code level_<n>.nbt}. The colon cannot occur in a
     * resource path segment, so a piece id is never mistaken for a
     * plain variant folder. {@code CultureResolver} translates the id
     * to the piece file path; registry lookups normalise through
     * {@link #baseId} so manifest metadata (tint colour slots, tags)
     * comes from the piece's variant folder.
     */
    public static final char PIECE_DELIMITER = ':';

    /** Composes a piece variant id ({@code folder:piece}). */
    public static String pieceVariantId(String variantFolder, String piece) {
        return variantFolder + PIECE_DELIMITER + piece;
    }

    /** The variant-folder part of {@code variantId} — strips a
     *  {@code :piece} suffix when present, otherwise returns the id
     *  unchanged (also for null). */
    public static String baseId(String variantId) {
        if (variantId == null) return null;
        int sep = variantId.indexOf(PIECE_DELIMITER);
        return sep > 0 ? variantId.substring(0, sep) : variantId;
    }

    /** The piece part of {@code variantId}, or null when the id has no
     *  {@code :piece} suffix. */
    public static String pieceOf(String variantId) {
        if (variantId == null) return null;
        int sep = variantId.indexOf(PIECE_DELIMITER);
        return sep > 0 ? variantId.substring(sep + 1) : null;
    }

    /**
     * Builds a {@link BuildingVariant} from a folder key and parsed
     * manifest. Style/culture/type come from the folder; everything
     * else from the manifest fields.
     */
    public static BuildingVariant from(VariantKey key, VariantManifest manifest) {
        return new BuildingVariant(
                key.culture(),
                Style.fromFolder(key.style())
                        .orElseThrow(() -> new IllegalArgumentException(
                                "BuildingVariant: unknown style folder '"
                                        + key.style() + "' for "
                                        + key.type() + "/" + key.variantId())),
                key.type(),
                manifest.id(),
                manifest.displayName(),
                manifest.minTier(),
                manifest.maxTier(),
                manifest.weight(),
                manifest.preferredTags(),
                manifest.stylePreference(),
                manifest.agePreference(),
                manifest.tags(),
                manifest.maxPerVillage(),
                manifest.colorSlots(),
                manifest.ruinationLevel());
    }
}
