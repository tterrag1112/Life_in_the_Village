package tterrag1112.life_in_the_village.Village.Decoration.Variants;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.world.item.DyeColor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Doc 15 — registry of named {@link ColorPalette} presets plus a
 * parser for the inline JSON form village types use.
 *
 * <p>Pre-registered palettes (see doc 15 §"Color palettes"):
 * {@link #BERGEN_FJORD}, {@link #MEDITERRANEAN}, {@link #MUTED_EARTH},
 * {@link #MEDIEVAL_PRIMARY}, {@link #NORDIC_SUBDUED}, {@link #NONE}.</p>
 */
public final class ColorPaletteRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(ColorPaletteRegistry.class);

    /** Doc 15: red, yellow, white, mustard, light_blue — heavy red. */
    public static final String BERGEN_FJORD = "BERGEN_FJORD";
    /** Doc 15: orange, red, yellow, white, light_blue — even with bright accents. */
    public static final String MEDITERRANEAN = "MEDITERRANEAN";
    /** Doc 15: brown, light_gray, gray, white, light_brown — most muted. */
    public static final String MUTED_EARTH = "MUTED_EARTH";
    /** Doc 15: red, blue, yellow, white, brown — heavy red+brown. */
    public static final String MEDIEVAL_PRIMARY = "MEDIEVAL_PRIMARY";
    /** Doc 15: gray, white, brown, red, dark_gray — subdued with rare red. */
    public static final String NORDIC_SUBDUED = "NORDIC_SUBDUED";
    /** Doc 15: no palette; buildings remain untinted. */
    public static final String NONE = "NONE";

    private static final Map<String, ColorPalette> REGISTRY = new LinkedHashMap<>();

    static {
        register(noOp(NONE));
        register(bergenFjord());
        register(mediterranean());
        register(mutedEarth());
        register(medievalPrimary());
        register(nordicSubdued());
    }

    private ColorPaletteRegistry() {}

    // ── Lookup ────────────────────────────────────────────────────────────

    /** Returns the named palette or empty when no preset matches. */
    public static Optional<ColorPalette> get(String id) {
        if (id == null) return Optional.empty();
        return Optional.ofNullable(REGISTRY.get(id.toUpperCase(Locale.ROOT)));
    }

    /** Returns the named palette or {@link #NONE} when no preset matches. */
    public static ColorPalette getOrNone(String id) {
        return get(id).orElse(REGISTRY.get(NONE));
    }

    /** Read-only snapshot of all registered palettes (for debug commands). */
    public static Set<String> ids() {
        return java.util.Collections.unmodifiableSet(REGISTRY.keySet());
    }

    // ── JSON parsing ──────────────────────────────────────────────────────

    /**
     * Parses a {@code colorPalette} JSON value as documented in doc 15
     * §"Color palettes". Two shapes accepted:
     * <ul>
     *   <li>String — looked up as a named preset id; falls back to
     *       {@link #NONE} with a warning when the id is unknown.</li>
     *   <li>Object — inline weight tables for the three slots:
     *       {@code { "primary": {...}, "accent": {...}, "roof": {...} }}.
     *       Missing slot keys become empty weight maps. The synthesised
     *       palette's id is {@code "INLINE"}.</li>
     * </ul>
     */
    public static ColorPalette parse(JsonElement element) {
        if (element == null || element.isJsonNull()) return getOrNone(NONE);
        if (element.isJsonPrimitive()) {
            String id = element.getAsString();
            return get(id).orElseGet(() -> {
                LOGGER.warn("ColorPaletteRegistry: unknown palette id "
                        + "'{}' — falling back to NONE", id);
                return REGISTRY.get(NONE);
            });
        }
        if (!element.isJsonObject()) return getOrNone(NONE);

        JsonObject obj = element.getAsJsonObject();
        return new ColorPalette(
                "INLINE",
                parseWeights(obj.get("primary")),
                parseWeights(obj.get("accent")),
                parseWeights(obj.get("roof")));
    }

    private static Map<DyeColor, Float> parseWeights(JsonElement el) {
        if (el == null || !el.isJsonObject()) return Map.of();
        Map<DyeColor, Float> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : el.getAsJsonObject().entrySet()) {
            DyeColor c;
            try { c = DyeColor.valueOf(e.getKey().toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException ex) {
                LOGGER.warn("ColorPaletteRegistry: unknown DyeColor '{}' in inline palette",
                        e.getKey());
                continue;
            }
            float w;
            try { w = e.getValue().getAsFloat(); }
            catch (Exception ex) { w = 0f; }
            if (w > 0) out.put(c, w);
        }
        return out;
    }

    // ── Preset construction ───────────────────────────────────────────────

    private static void register(ColorPalette palette) {
        REGISTRY.put(palette.id(), palette);
    }

    private static ColorPalette noOp(String id) {
        return new ColorPalette(id, Map.of(), Map.of(), Map.of());
    }

    private static ColorPalette bergenFjord() {
        return new ColorPalette(
                BERGEN_FJORD,
                Map.of(DyeColor.RED, 5f,
                        DyeColor.YELLOW, 3f,
                        DyeColor.WHITE, 2f,
                        DyeColor.ORANGE, 2f,        // "mustard" — ORANGE proxy
                        DyeColor.LIGHT_BLUE, 1f),
                Map.of(DyeColor.WHITE, 5f,
                        DyeColor.BROWN, 3f,
                        DyeColor.BLACK, 1f),
                Map.of(DyeColor.BROWN, 5f,
                        DyeColor.GRAY, 3f,
                        DyeColor.RED, 1f));
    }

    private static ColorPalette mediterranean() {
        return new ColorPalette(
                MEDITERRANEAN,
                Map.of(DyeColor.ORANGE, 2f,
                        DyeColor.RED, 2f,
                        DyeColor.YELLOW, 2f,
                        DyeColor.WHITE, 2f,
                        DyeColor.LIGHT_BLUE, 2f),
                Map.of(DyeColor.WHITE, 4f,
                        DyeColor.BLUE, 2f,
                        DyeColor.YELLOW, 1f),
                Map.of(DyeColor.ORANGE, 4f,
                        DyeColor.RED, 3f,
                        DyeColor.BROWN, 2f));
    }

    private static ColorPalette mutedEarth() {
        return new ColorPalette(
                MUTED_EARTH,
                Map.of(DyeColor.BROWN, 5f,
                        DyeColor.LIGHT_GRAY, 4f,
                        DyeColor.GRAY, 3f,
                        DyeColor.WHITE, 2f,
                        DyeColor.YELLOW, 1f),       // "light_brown" — YELLOW proxy
                Map.of(DyeColor.WHITE, 4f,
                        DyeColor.GRAY, 2f,
                        DyeColor.BROWN, 2f),
                Map.of(DyeColor.BROWN, 5f,
                        DyeColor.GRAY, 3f,
                        DyeColor.LIGHT_GRAY, 2f));
    }

    private static ColorPalette medievalPrimary() {
        return new ColorPalette(
                MEDIEVAL_PRIMARY,
                Map.of(DyeColor.RED, 4f,
                        DyeColor.BROWN, 4f,
                        DyeColor.BLUE, 2f,
                        DyeColor.YELLOW, 1f,
                        DyeColor.WHITE, 1f),
                Map.of(DyeColor.BLUE, 4f,
                        DyeColor.WHITE, 3f,
                        DyeColor.YELLOW, 2f),
                Map.of(DyeColor.BROWN, 5f,
                        DyeColor.GRAY, 3f,
                        DyeColor.RED, 1f));
    }

    private static ColorPalette nordicSubdued() {
        return new ColorPalette(
                NORDIC_SUBDUED,
                Map.of(DyeColor.GRAY, 5f,
                        DyeColor.WHITE, 4f,
                        DyeColor.BROWN, 3f,
                        DyeColor.LIGHT_GRAY, 2f,
                        DyeColor.RED, 1f),
                Map.of(DyeColor.WHITE, 5f,
                        DyeColor.LIGHT_GRAY, 3f,
                        DyeColor.RED, 1f),
                Map.of(DyeColor.GRAY, 5f,
                        DyeColor.BLACK, 3f,
                        DyeColor.BROWN, 2f));
    }
}
