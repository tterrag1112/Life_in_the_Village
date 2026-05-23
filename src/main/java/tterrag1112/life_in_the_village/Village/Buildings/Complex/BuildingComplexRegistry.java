package tterrag1112.life_in_the_village.Village.Buildings.Complex;

import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Buildings.FarmPlot.CropType;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Detour A — culture-keyed registry of {@link BuildingComplexSpec}
 * entries.
 *
 * <p>Mirrors the shape of
 * {@code Village.Decoration.Adjunct.AdjunctPlotRegistry}: outer
 * {@link LinkedHashMap} keyed by culture id (insertion-order for
 * determinism), inner {@link EnumMap} keyed by {@link BuildingType}.
 * Lookups fall back to {@link #DEFAULT_CULTURE} when the requested
 * culture has no entry for a given type, so non-default cultures
 * can selectively override.
 *
 * <p>Today: one entry — default × FARMHOUSE. Future BLACKSMITH /
 * TEMPLE entries will register here the same way (the spec record
 * is intentionally generic across complex domains).
 *
 * <p>Idempotent: re-registering the same (culture, type) pair is
 * a no-op rather than an overwrite, so static block ordering
 * doesn't matter.
 */
public final class BuildingComplexRegistry {

    public static final String DEFAULT_CULTURE = "default";

    private static final Map<String, EnumMap<BuildingType, BuildingComplexSpec>> SPECS =
            new LinkedHashMap<>();

    private BuildingComplexRegistry() {}

    static {
        registerDefaults();
    }

    /** Registers a spec for {@code (culture, type)} if no entry
     *  exists yet. Idempotent; returns false if a prior entry
     *  blocked the registration. */
    public static boolean register(String culture, BuildingType type,
                                    BuildingComplexSpec spec) {
        if (culture == null || type == null || spec == null) {
            throw new IllegalArgumentException(
                    "register: culture/type/spec all required");
        }
        EnumMap<BuildingType, BuildingComplexSpec> perCulture =
                SPECS.computeIfAbsent(culture,
                        k -> new EnumMap<>(BuildingType.class));
        if (perCulture.containsKey(type)) return false;
        perCulture.put(type, spec);
        return true;
    }

    /** Look up the spec for {@code (culture, type)}, falling back
     *  to {@link #DEFAULT_CULTURE} when the requested culture has
     *  no entry. Returns empty when neither culture nor default
     *  has one — caller treats that as "no complex". */
    public static Optional<BuildingComplexSpec> get(String culture,
                                                     BuildingType type) {
        if (culture != null) {
            EnumMap<BuildingType, BuildingComplexSpec> perCulture = SPECS.get(culture);
            if (perCulture != null) {
                BuildingComplexSpec direct = perCulture.get(type);
                if (direct != null) return Optional.of(direct);
            }
        }
        EnumMap<BuildingType, BuildingComplexSpec> def = SPECS.get(DEFAULT_CULTURE);
        if (def == null) return Optional.empty();
        return Optional.ofNullable(def.get(type));
    }

    /** True iff a spec exists for {@code (culture, type)} (with
     *  default fallback). */
    public static boolean has(String culture, BuildingType type) {
        return get(culture, type).isPresent();
    }

    /** Snapshot of every registered culture id, in registration
     *  order. */
    public static List<String> registeredCultures() {
        return List.copyOf(SPECS.keySet());
    }

    // =========================================================================
    // Defaults
    // =========================================================================

    private static void registerDefaults() {
        // default × FARMHOUSE — initial farm-complex tuning per
        // Detour A spec. Numbers are deliberately conservative;
        // smoke-test feedback drives the next tuning pass.
        Map<CropType, Float> mix = new EnumMap<>(CropType.class);
        mix.put(CropType.WHEAT,     0.40f);
        mix.put(CropType.MIXED,     0.20f);
        mix.put(CropType.PASTURE,   0.20f);
        mix.put(CropType.ORCHARD,   0.10f);
        mix.put(CropType.VEGETABLE, 0.10f);

        BuildingComplexSpec farmhouse = new BuildingComplexSpec(
                /* radiusMultiplier   */ 3.0f,
                /* blockBudget        */ 600,
                /* minPlotSize        */ 16,
                /* targetPlotCount    */ 4,
                /* plotTypeMix        */ mix,
                /* borderStylePool    */ List.of(
                        BorderStyleId.HEDGE,
                        BorderStyleId.STONE_WALL,
                        BorderStyleId.POST_AND_RAIL,
                        BorderStyleId.DRYSTONE),
                /* pathStyle          */ PathTopology.SPINE_WITH_BRANCHES,
                /* floodFillSlopeLimit*/ 6,
                /* blockedBiomes      */ Set.of(
                        BiomeTag.OCEAN,
                        BiomeTag.DESERT,
                        BiomeTag.MUSHROOM,
                        BiomeTag.NETHER,
                        BiomeTag.END,
                        BiomeTag.UNINHABITABLE));

        register(DEFAULT_CULTURE, BuildingType.FARMHOUSE, farmhouse);
    }
}
