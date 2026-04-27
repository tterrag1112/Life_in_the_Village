package tterrag1112.life_in_the_village.Village.Decoration.Variants;

import java.util.Locale;
import java.util.Map;

/**
 * Doc 15 — fallback palette per culture, consulted when a
 * {@code VillageTypeData} doesn't declare its own
 * {@code colorPalette}. Used by {@link VillagePaletteResolver}'s
 * three-step resolution chain.
 *
 * <p>The default culture defaults to {@link
 * ColorPaletteRegistry#MUTED_EARTH} — same value the P0a-10
 * temporary bridge hardcoded. Future cultures land here as they're
 * authored; until then unknown culture names fall through to
 * {@link ColorPaletteRegistry#NONE}.</p>
 */
public final class CultureDefaultPalettes {

    private static final Map<String, String> DEFAULTS = Map.of(
            "default", ColorPaletteRegistry.MUTED_EARTH
    );

    private CultureDefaultPalettes() {}

    /**
     * Returns the named-preset id of the culture's default palette,
     * or {@link ColorPaletteRegistry#NONE} when the culture has no
     * registered default. Lookup is case-insensitive on culture name.
     */
    public static String paletteIdFor(String culture) {
        if (culture == null) return ColorPaletteRegistry.NONE;
        String key = culture.toLowerCase(Locale.ROOT);
        return DEFAULTS.getOrDefault(key, ColorPaletteRegistry.NONE);
    }

    /** Convenience: returns the resolved {@link ColorPalette}. */
    public static ColorPalette paletteFor(String culture) {
        return ColorPaletteRegistry.getOrNone(paletteIdFor(culture));
    }
}
