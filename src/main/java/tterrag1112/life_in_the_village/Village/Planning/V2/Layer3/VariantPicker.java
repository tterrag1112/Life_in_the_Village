package tterrag1112.life_in_the_village.Village.Planning.V2.Layer3;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Decoration.Variants.BuildingVariant;

/**
 * V2 variant id picker. For HOUSE only, picks among
 * {@code large_house / house / cottage} by distance from anchor —
 * larger variants near the centre, smaller variants on the outskirts.
 *
 * <p>For all other types in V1, returns
 * {@link BuildingVariant#defaultVariantId(BuildingType)} (the
 * type-name-lowercased default-variant). Other types only have one
 * authored variant; no choice to make.
 *
 * <p>The variant id is used by {@code StructureSizeCache} to look up
 * the actual NBT footprint, and is recorded on
 * {@link PlacedBuilding#variantId} so Layer 5 placement uses the
 * same NBT.
 */
public final class VariantPicker {

    private VariantPicker() {}

    /** HOUSE thresholds: distance fraction → variant. */
    private static final double LARGE_THRESHOLD = 0.3;
    private static final double MEDIUM_THRESHOLD = 0.7;

    public static String pick(BuildingType type, BlockPos pos, BlockPos anchor,
                              int villageRadius) {
        if (type == BuildingType.HOUSE) {
            double normDist = normalisedDistance(pos, anchor, villageRadius);
            if (normDist < LARGE_THRESHOLD) return "large_house";
            if (normDist < MEDIUM_THRESHOLD) return "house";
            return "cottage";
        }
        return BuildingVariant.defaultVariantId(type);
    }

    private static double normalisedDistance(BlockPos pos, BlockPos anchor,
                                              int villageRadius) {
        double dx = pos.getX() - anchor.getX();
        double dz = pos.getZ() - anchor.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        return Math.min(1.0, dist / Math.max(1, villageRadius));
    }
}
