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
        // default × MARKET.
        //
        // Pad-band fit (Phase 2b bug-fix): the perimeter stall band usable
        // by StallAllocator.findSeat is (margin − AISLE_WIDTH) deep, and a
        // stall on a side needs box.minZ ≥ fp.maxZ + GAP. For the authored
        // 5×5×5 stall (and AISLE_WIDTH = GAP = 1) the band fits iff
        //     margin ≥ stallDepth + AISLE_WIDTH + GAP − 1  = 5 + 1 + 1 − 1 = 6.
        // The old padMargin = 6 / minPadMargin = 2 was a zero-slack fit that
        // any collision shrink dropped below 5 → no seat → MarketStallSeeder
        // seeded ZERO stalls (silently). Bumped so every accepted pad hosts
        // the stall pool with slack: minPadMargin = 7 (= min-fit 6 + 1
        // slack) so a too-small pad is rejected (NO_REGION, clean skip in a
        // dense core) rather than producing an empty market; padMargin = 10
        // gives the perimeter room to pack SEED_COUNT stalls.
        //
        // NOTE (flagged): these are hand-tuned to the single authored 5-deep
        // stall. Deriving the band from the stall pool's max footprint would
        // be knife-edge-proof if stalls are re-authored, but the footprint is
        // only known once the NBT loads at spawn (no ServerLevel at registry
        // static-init), so it can't be derived here cheaply — bumped now,
        // derive-later flagged in MERCHANT_PROGRESS.md.
        MarketComplexSpec market = new MarketComplexSpec(
                /* padMargin    */ 10,
                /* minPadMargin */ 7,
                /* aisleModel   */ MarketAisleModel.PERIMETER,
                /* padBlockId   */ null,      // null ⇒ culture path palette
                /* stallPool    */ java.util.List.of(
                        new tterrag1112.life_in_the_village.Village.Markets.Complex.StallVariant(
                                "stall_1",
                                net.minecraft.resources.Identifier.fromNamespaceAndPath(
                                        tterrag1112.life_in_the_village.Life_in_the_village.MODID,
                                        "default/rural/market/stall/stall_1"),
                                1)));
        register(DEFAULT_CULTURE, BuildingType.MARKET, market);
    }
}
