package tterrag1112.life_in_the_village.Village.Farms.Complex.Render.Borders;

import tterrag1112.life_in_the_village.Village.Buildings.Complex.BorderStyleId;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Culture-keyed lookup from {@link BorderStyleId} to a concrete
 * {@link BorderGenerator}.
 *
 * <p>Mirrors {@code BuildingComplexRegistry}'s shape: outer map
 * keyed by culture id, inner EnumMap keyed by style. Lookups fall
 * back to {@link #DEFAULT_CULTURE} when the requested culture has
 * no entry, so non-default cultures can selectively override
 * individual border styles without re-declaring the rest.
 *
 * <p>Today: only the default-culture entries are populated, one
 * per {@link BorderStyleId} value. Per-culture authoring is
 * deferred per Prompt B's scope.
 */
public final class BorderGeneratorRegistry {

    public static final String DEFAULT_CULTURE = "default";

    private static final Map<String, EnumMap<BorderStyleId, BorderGenerator>> REGISTRY =
            new HashMap<>();

    private BorderGeneratorRegistry() {}

    static {
        registerDefaults();
    }

    public static boolean register(String culture, BorderStyleId style,
                                    BorderGenerator generator) {
        if (culture == null || style == null || generator == null) {
            throw new IllegalArgumentException(
                    "register: culture/style/generator all required");
        }
        EnumMap<BorderStyleId, BorderGenerator> per = REGISTRY.computeIfAbsent(culture,
                k -> new EnumMap<>(BorderStyleId.class));
        if (per.containsKey(style)) return false;
        per.put(style, generator);
        return true;
    }

    /** Look up the generator for {@code (culture, style)}, falling
     *  back to {@link #DEFAULT_CULTURE}. Returns empty when neither
     *  registers anything for that style — caller can substitute a
     *  no-op or HEDGE as a final fallback. */
    public static Optional<BorderGenerator> get(String culture, BorderStyleId style) {
        if (culture != null) {
            var per = REGISTRY.get(culture);
            if (per != null) {
                BorderGenerator hit = per.get(style);
                if (hit != null) return Optional.of(hit);
            }
        }
        var def = REGISTRY.get(DEFAULT_CULTURE);
        if (def == null) return Optional.empty();
        return Optional.ofNullable(def.get(style));
    }

    // =========================================================================
    // Defaults
    // =========================================================================

    private static void registerDefaults() {
        register(DEFAULT_CULTURE, BorderStyleId.HEDGE,          new HedgeBorder());
        register(DEFAULT_CULTURE, BorderStyleId.STONE_WALL,     new StoneWallBorder());
        register(DEFAULT_CULTURE, BorderStyleId.POST_AND_RAIL,  new PostAndRailBorder());
        register(DEFAULT_CULTURE, BorderStyleId.DRYSTONE,       new DrystoneBorder());
    }
}
