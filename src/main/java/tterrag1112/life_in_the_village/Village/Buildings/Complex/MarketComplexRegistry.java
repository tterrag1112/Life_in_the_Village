package tterrag1112.life_in_the_village.Village.Buildings.Complex;

import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Culture-keyed registry of {@link MarketComplexSpec} entries (merchant
 * arc Phase 2a). Mirrors {@link BuildingComplexRegistry}'s shape exactly
 * (outer culture map, inner {@link BuildingType} {@link EnumMap},
 * default-culture fallback, idempotent register) but is a separate
 * registry because the spec type differs — markets aren't farms.
 *
 * <p>Today: one entry, {@code default × MARKET}. A future per-domain
 * generalization of the two complex registries is possible, but is NOT
 * built speculatively here (project rule); flagged in PROGRESS.
 */
public final class MarketComplexRegistry {

    public static final String DEFAULT_CULTURE = "default";

    private static final Map<String, EnumMap<BuildingType, MarketComplexSpec>> SPECS =
            new LinkedHashMap<>();

    private MarketComplexRegistry() {}

    static {
        registerDefaults();
    }

    /** Registers a spec for {@code (culture, type)} if none exists yet.
     *  Idempotent; returns false if a prior entry blocked it. */
    public static boolean register(String culture, BuildingType type,
                                   MarketComplexSpec spec) {
        if (culture == null || type == null || spec == null) {
            throw new IllegalArgumentException("register: culture/type/spec all required");
        }
        EnumMap<BuildingType, MarketComplexSpec> perCulture =
                SPECS.computeIfAbsent(culture, k -> new EnumMap<>(BuildingType.class));
        if (perCulture.containsKey(type)) return false;
        perCulture.put(type, spec);
        return true;
    }

    /** Look up the spec for {@code (culture, type)}, falling back to
     *  {@link #DEFAULT_CULTURE}. Empty ⇒ "no market complex". */
    public static Optional<MarketComplexSpec> get(String culture, BuildingType type) {
        if (culture != null) {
            EnumMap<BuildingType, MarketComplexSpec> perCulture = SPECS.get(culture);
            if (perCulture != null) {
                MarketComplexSpec direct = perCulture.get(type);
                if (direct != null) return Optional.of(direct);
            }
        }
        EnumMap<BuildingType, MarketComplexSpec> def = SPECS.get(DEFAULT_CULTURE);
        if (def == null) return Optional.empty();
        return Optional.ofNullable(def.get(type));
    }

    public static boolean has(String culture, BuildingType type) {
        return get(culture, type).isPresent();
    }

    public static List<String> registeredCultures() {
        return List.copyOf(SPECS.keySet());
    }

    // =========================================================================
    // Defaults
    // =========================================================================

    private static void registerDefaults() {
        // default × MARKET — conservative bounded pad. padMargin 4 gives
        // a modest plaza ring; shrinks to 1 in a dense core, else no pad.
        MarketComplexSpec market = new MarketComplexSpec(
                /* padMargin    */ 4,
                /* minPadMargin */ 1,
                /* aisleModel   */ MarketAisleModel.PERIMETER,
                /* padBlockId   */ null,      // null ⇒ culture path palette
                /* stallPool    */ List.of()); // 2b populates
        register(DEFAULT_CULTURE, BuildingType.MARKET, market);
    }
}
