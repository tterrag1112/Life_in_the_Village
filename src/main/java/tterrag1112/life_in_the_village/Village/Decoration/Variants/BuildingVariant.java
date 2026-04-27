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
 * {@code CultureResolver}, and the eventual matcher score function
 * need to act on a single placement candidate.</p>
 *
 * <p><b>Deviations from doc 15's "Data structures" snippet:</b></p>
 * <ul>
 *   <li>{@code culture}, {@link Style style}, and {@link BuildingType
 *       type} are added so callers don't need a parallel index map to
 *       reconstruct the NBT path. The doc's flat
 *       {@code Map<BuildingType, List<BuildingVariant>>} doesn't carry
 *       culture/style information any other way.</li>
 *   <li>{@code footprint} uses a nested {@link Footprint} record (XZ
 *       only) rather than {@code BoundingBox}. Manifests authoring
 *       only declares XZ extents; synthesizing a Y just to wrap in a
 *       3D box would be lossy and noisy at every site that reads it.</li>
 * </ul>
 *
 * <p>Both deviations are documented in doc 15's revision notes.</p>
 */
public record BuildingVariant(
        String culture,
        Style style,
        BuildingType type,
        String id,
        String displayName,
        Footprint footprint,
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
     * Authored XZ footprint. {@code 0} on either axis means "use the
     * NBT-declared size" — the manifest is overriding nothing for that
     * axis. {@link tterrag1112.life_in_the_village.Village.Planning
     * .StructureSizeCache} consults {@link #hasOverride()} before
     * loading the NBT.
     */
    public record Footprint(int x, int z) {
        public static final Footprint NONE = new Footprint(0, 0);
    }

    /** True iff the manifest declared an explicit footprint override. */
    public boolean hasFootprintOverride() {
        return footprint != null && footprint.x() > 0 && footprint.z() > 0;
    }

    /**
     * The default-variant id for a {@link BuildingType} — doc 15:
     * "the variant defaults to the type name, so a HOUSE becomes
     * {@code house/house/level_n}".
     */
    public static String defaultVariantId(BuildingType type) {
        return type.name().toLowerCase();
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
                new Footprint(manifest.footprintX(), manifest.footprintZ()),
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
