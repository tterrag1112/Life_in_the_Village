package tterrag1112.life_in_the_village.Village.Decoration.Variants;

import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Decoration.VillageSizeTier;
import tterrag1112.life_in_the_village.Village.VillageTypeData;
import tterrag1112.life_in_the_village.Village.VillageTypeData.ShapeType;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Doc 15 §"Style determination" — turns a {@link VillageTypeData}'s
 * raw {@code style} declaration ({@code "rural"} / {@code "urban"} /
 * {@code "auto"}) plus the village's tier and shape into a concrete
 * {@link StyleSelection} the matcher can act on.
 *
 * <p>Auto-derivation rules:</p>
 * <ul>
 *   <li>{@code TOWN} or {@code CITY} tier → URBAN.</li>
 *   <li>{@code HAMLET}/{@code VILLAGE} on
 *       {@code RADIAL}/{@code PLAZA}/{@code CROSSROADS} → URBAN-leaning
 *       (40% urban, 60% rural per building).</li>
 *   <li>{@code HAMLET}/{@code VILLAGE} on
 *       {@code CLUSTERED}/{@code SPRAWL}/{@code RIVERINE}/{@code GROVE}
 *       → RURAL.</li>
 *   <li>{@code ENCLAVE} → URBAN regardless of tier.</li>
 *   <li>{@code TERRACED}, {@code HILLTOP} → RURAL.</li>
 *   <li>{@code COASTAL} (currently {@code DOCKSIDE}) → tag-driven;
 *       defaults to RURAL until coastal-specific variants exist.</li>
 *   <li>Anything else → RURAL.</li>
 * </ul>
 *
 * <p>Per doc 15: "Auto fallback never picks a style with zero
 * authored content; it defaults to the available style when only one
 * exists." The {@link #resolve(VillageTypeData, BuildingType,
 * VillageSizeTier)} overload consults {@link VariantRegistry} for
 * authored-content presence and downgrades the decision if the
 * derived style has no variants for the requested
 * {@link BuildingType}.</p>
 */
public final class StyleAutoDeriver {

    public static final float URBAN_LEANING_URBAN_PROBABILITY = 0.40f;

    private StyleAutoDeriver() {}

    /** Concrete shape categories from doc 15. */
    private static final Set<ShapeType> URBAN_LEANING_SHAPES = EnumSet.of(
            ShapeType.RADIAL, ShapeType.PLAZA, ShapeType.CROSSROADS);
    private static final Set<ShapeType> RURAL_SHAPES = EnumSet.of(
            ShapeType.CLUSTERED, ShapeType.SPRAWL,
            ShapeType.RIVERINE, ShapeType.GROVE);
    private static final Set<ShapeType> ALWAYS_URBAN = EnumSet.of(
            ShapeType.ENCLAVE);
    private static final Set<ShapeType> ALWAYS_RURAL = EnumSet.of(
            ShapeType.TERRACED, ShapeType.HILLTOP);

    /**
     * Resolves the style decision <em>without</em> per-type authored-
     * content fallback. Useful when the caller doesn't yet know which
     * {@link BuildingType} it'll be selecting.
     */
    public static StyleSelection resolve(VillageTypeData typeData,
                                         VillageSizeTier tier) {
        String declared = typeData.getStyle() == null
                ? "auto" : typeData.getStyle().toLowerCase(Locale.ROOT);
        switch (declared) {
            case "rural": return StyleSelection.fixed(Style.RURAL);
            case "urban": return StyleSelection.fixed(Style.URBAN);
            case "auto": default: break;
        }

        ShapeType shape = typeData.getShapeProfile().shapeType();

        if (ALWAYS_URBAN.contains(shape)) return StyleSelection.fixed(Style.URBAN);
        if (ALWAYS_RURAL.contains(shape)) return StyleSelection.fixed(Style.RURAL);

        if (tier == VillageSizeTier.TOWN || tier == VillageSizeTier.CITY) {
            return StyleSelection.fixed(Style.URBAN);
        }

        if (URBAN_LEANING_SHAPES.contains(shape)) {
            return StyleSelection.urbanLeaning(URBAN_LEANING_URBAN_PROBABILITY);
        }
        if (RURAL_SHAPES.contains(shape)) {
            return StyleSelection.fixed(Style.RURAL);
        }

        // Coastal / dockside / fall-through cases: doc 15 says coastal
        // is tag-driven and defaults to RURAL with preferred tags.
        // Tag-driven scoring belongs to variant selection, not style
        // resolution — return RURAL here and let scoring handle the rest.
        return StyleSelection.fixed(Style.RURAL);
    }

    /**
     * Resolves the style decision and downgrades it when the picked
     * style has zero authored variants for {@code type}. The
     * authored-content fallback only applies to {@code Fixed}
     * decisions — for {@code UrbanLeaning} the per-building roll is
     * trusted and individual rolls fall back via {@link
     * VariantRegistry}'s default-variant lookup at scoring time.
     */
    public static StyleSelection resolve(VillageTypeData typeData,
                                         BuildingType type,
                                         VillageSizeTier tier) {
        StyleSelection sel = resolve(typeData, tier);
        if (sel instanceof StyleSelection.Fixed fixed) {
            Style picked = fixed.style();
            if (hasAuthoredFor(type, picked)) return sel;
            Style other = picked == Style.URBAN ? Style.RURAL : Style.URBAN;
            if (hasAuthoredFor(type, other)) return StyleSelection.fixed(other);
        }
        return sel;
    }

    private static boolean hasAuthoredFor(BuildingType type, Style style) {
        for (var v : VariantRegistry.INSTANCE.all(type)) {
            if (v.style() == style) return true;
        }
        return false;
    }

    /**
     * Per-slot style pick that consults authored-content presence for
     * {@code type} before consuming the matcher's RNG. Used by the
     * placement matcher so a village whose declared style is "auto"
     * but whose type only has RURAL variants doesn't shift the RNG
     * stream relative to the pre-P0a-06 code path. Only when both
     * styles have content does {@code UrbanLeaning} actually roll.
     */
    public static Style pickStyleForType(StyleSelection selection,
                                         BuildingType type,
                                         java.util.Random rng) {
        if (selection instanceof StyleSelection.Fixed fixed) {
            Style picked = fixed.style();
            if (hasAuthoredFor(type, picked)) return picked;
            Style other = picked == Style.URBAN ? Style.RURAL : Style.URBAN;
            return hasAuthoredFor(type, other) ? other : picked;
        }
        // UrbanLeaning — only roll when both styles are authored for
        // this type. Otherwise pick the only available style without
        // consuming RNG.
        boolean rural = hasAuthoredFor(type, Style.RURAL);
        boolean urban = hasAuthoredFor(type, Style.URBAN);
        if (urban && !rural) return Style.URBAN;
        if (rural && !urban) return Style.RURAL;
        if (!rural && !urban) return Style.RURAL; // resolver fallback chain handles
        return selection.pickStyle(rng);
    }
}
