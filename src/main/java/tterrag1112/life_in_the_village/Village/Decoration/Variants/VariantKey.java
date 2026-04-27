package tterrag1112.life_in_the_village.Village.Decoration.Variants;

import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;

import java.util.Objects;

/**
 * Composite key for indexing variant manifests. Mirrors the path
 * layout introduced by P0a-01:
 *
 * <pre>structures/{culture}/{style}/{type}/{variantId}/manifest.json</pre>
 *
 * <p>{@link #style} is the folder string ({@code "rural"} or
 * {@code "urban"}) — kept as a String rather than {@link
 * StylePreference} because the folder slot is the source of truth and
 * may grow new values (coastal, etc.) before the enum does.</p>
 */
public record VariantKey(String culture, String style, BuildingType type, String variantId) {
    public VariantKey {
        Objects.requireNonNull(culture, "culture");
        Objects.requireNonNull(style, "style");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(variantId, "variantId");
    }
}
