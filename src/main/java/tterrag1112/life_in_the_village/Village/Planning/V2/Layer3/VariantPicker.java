package tterrag1112.life_in_the_village.Village.Planning.V2.Layer3;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Decoration.Variants.BuildingVariant;
import tterrag1112.life_in_the_village.Village.Decoration.Variants.Style;

import java.util.Set;
import java.util.TreeSet;

/**
 * V2 variant id picker. Returns a variant id whose NBT is actually
 * authored for the village's {@code (culture, style, type, level)} —
 * partial authoring degrades gracefully (a culture with only
 * {@code cottage} authored for HOUSE gets every HOUSE as a cottage).
 *
 * <p>For HOUSE, the preferred order rotates by distance band so the
 * largest authored size sits near the anchor and smaller sizes
 * spread outward. For other types, the {@link BuildingVariant
 * #defaultVariantId} is preferred; if absent, falls back to any
 * available variant in alphabetical order (deterministic).
 *
 * <p>The variant id is recorded on
 * {@link PlacedBuilding#variantId} so Layer 5 placement uses the
 * same NBT the cache measured.
 */
public final class VariantPicker {

    /** All V1 placement happens at level 1. */
    private static final int LEVEL = 1;

    /** HOUSE distance bands. */
    private static final double LARGE_THRESHOLD = 0.3;
    private static final double MEDIUM_THRESHOLD = 0.7;

    private VariantPicker() {}

    public static String pick(BuildingType type, BlockPos pos, BlockPos anchor,
                              int villageRadius, String culture, Style style) {
        return pick(type, pos, anchor, villageRadius, culture, style,
                StructureAvailabilityRegistry.INSTANCE);
    }

    /** Overload that takes an explicit {@link BuildingAvailability}. */
    public static String pick(BuildingType type, BlockPos pos, BlockPos anchor,
                              int villageRadius, String culture, Style style,
                              BuildingAvailability availability) {
        Set<String> available = availability.availableVariants(culture, style, type, LEVEL);
        // BuildingSelector ensured `available` is non-empty before this
        // building reached the solver. Defensive throw if a caller
        // bypasses that flow.
        if (available.isEmpty()) {
            throw new IllegalStateException(
                    "VariantPicker.pick called for unavailable type "
                            + type + " in culture " + culture);
        }

        if (type == BuildingType.HOUSE) {
            double normDist = normalisedDistance(pos, anchor, villageRadius);
            String[] preferred = orderForHouseDistance(normDist);
            for (String v : preferred) {
                if (available.contains(v)) return v;
            }
        }

        String defaultId = BuildingVariant.defaultVariantId(type);
        if (available.contains(defaultId)) return defaultId;

        // Last resort: any available variant, sorted for determinism.
        return new TreeSet<>(available).first();
    }

    /** HOUSE preference array — the head of the list is the variant
     *  this distance band wants; the tail is the graceful-degradation
     *  fallback if the preferred isn't authored. */
    private static String[] orderForHouseDistance(double normDist) {
        if (normDist < LARGE_THRESHOLD) {
            return new String[]{"large_house", "house", "cottage"};
        }
        if (normDist < MEDIUM_THRESHOLD) {
            return new String[]{"house", "large_house", "cottage"};
        }
        return new String[]{"cottage", "house", "large_house"};
    }

    private static double normalisedDistance(BlockPos pos, BlockPos anchor,
                                             int villageRadius) {
        double dx = pos.getX() - anchor.getX();
        double dz = pos.getZ() - anchor.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        return Math.min(1.0, dist / Math.max(1, villageRadius));
    }
}
