package tterrag1112.life_in_the_village.Village.Farms.Complex;

import tterrag1112.life_in_the_village.Village.Buildings.FarmPlot.CropType;

import java.util.EnumMap;

/**
 * Per-{@link CropType} preferred-size data + a triangular fit
 * scorer for plot-by-crop matching.
 *
 * <p>Companion to the BSP plot assignment. The fit scorer
 * combines with the spec's {@code plotTypeMix} weight to pick
 * the best crop for each leaf:
 * <pre>{@code
 *   score(crop, leaf) = spec.plotTypeMix[crop]
 *                       × fitScore(crop, leaf.cellCount, floor)
 * }</pre>
 *
 * <p>Triangular curve: 1.0 at the preferred cell count, falling
 * linearly to {@code floor} at the min/max edges, clamped at
 * {@code floor} (not zero) outside the range so any leaf can
 * still receive any crop if other crops fit worse. A floor of
 * 0.05 means a crop totally out-of-range still has 5% of its
 * weight — enough to win if no preferred crop is available, but
 * heavily disfavoured.
 */
public final class CropSizePreferences {

    public record SizeRange(int preferred, int min, int max) {
        /** Triangular fit, clamped at {@code floor}. */
        public double fitScore(int cellCount, double floor) {
            if (cellCount < min || cellCount > max) return floor;
            double t;
            if (cellCount <= preferred) {
                double widthLow = preferred - min;
                t = widthLow <= 0 ? 1.0
                        : (cellCount - min) / widthLow;
            } else {
                double widthHigh = max - preferred;
                t = widthHigh <= 0 ? 1.0
                        : (max - cellCount) / widthHigh;
            }
            return Math.max(floor, t);
        }
    }

    /** Floor of the fit-score curve outside the preferred range.
     *  Tuned per the brief — high enough that a leaf with no
     *  perfect-fit crop still receives SOMETHING, low enough that
     *  size-matching dominates the assignment. */
    public static final double DEFAULT_FLOOR = 0.05;

    private static final EnumMap<CropType, SizeRange> RANGES =
            new EnumMap<>(CropType.class);
    static {
        RANGES.put(CropType.WHEAT,     new SizeRange(130, 80, 200));
        RANGES.put(CropType.GRAIN,     new SizeRange(130, 80, 200));
        RANGES.put(CropType.ORCHARD,   new SizeRange(110, 60, 180));
        RANGES.put(CropType.PASTURE,   new SizeRange(100, 50, 160));
        RANGES.put(CropType.MIXED,     new SizeRange( 80, 40, 120));
        RANGES.put(CropType.CARROTS,   new SizeRange( 60, 25, 100));
        RANGES.put(CropType.POTATOES,  new SizeRange( 60, 25, 100));
        RANGES.put(CropType.BEETROOT,  new SizeRange( 60, 25, 100));
        RANGES.put(CropType.VEGETABLE, new SizeRange( 40, 15,  70));
    }

    /** Fallback for any crop whose preference wasn't registered
     *  — accepts any size, mild centre at 80 cells. Lets a new
     *  CropType enum value still receive a plot without
     *  requiring a registry update. */
    private static final SizeRange DEFAULT_RANGE = new SizeRange(80, 15, 200);

    private CropSizePreferences() {}

    public static SizeRange forCrop(CropType crop) {
        if (crop == null) return DEFAULT_RANGE;
        return RANGES.getOrDefault(crop, DEFAULT_RANGE);
    }

    /** Convenience pass-through to
     *  {@link SizeRange#fitScore(int, double)}. */
    public static double fitScore(CropType crop, int cellCount, double floor) {
        return forCrop(crop).fitScore(cellCount, floor);
    }
}
