package tterrag1112.life_in_the_village.Village.Farms;

import tterrag1112.life_in_the_village.Cultures.Culture;
import tterrag1112.life_in_the_village.Village.Buildings.FarmPlot.CropType;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * B2.5 — picks a {@link CropType} for a planted plot using the
 * culture's per-type preference weights from
 * {@link tterrag1112.life_in_the_village.Cultures.CultureBundles.CulturePlanningBias#cropPreference}.
 *
 * <p>The picker honours the prompt's terrain rules:</p>
 * <ul>
 *   <li>Plots flagged {@code pasture} return {@link CropType#PASTURE}.</li>
 *   <li>Plots flagged {@code sloped} prefer {@link CropType#ORCHARD}.</li>
 *   <li>Plots flagged {@code forestEdge} prefer {@link CropType#ORCHARD}
 *       (saplings + apples fit the canopy).</li>
 *   <li>Otherwise — weighted draw from the culture's
 *       {@code cropPreference} map, restricted to cultivated
 *       cropTypes (PASTURE / ORCHARD excluded so the weighted bag
 *       doesn't accidentally pick them on flat terrain).</li>
 * </ul>
 */
public final class FarmCropPicker {

    /** Cultivated crop types — used for the weighted draw on flat
     *  fertile terrain. Excludes PASTURE / ORCHARD; those are
     *  picked only when the terrain hint forces them. */
    private static final List<CropType> CULTIVATED = List.of(
            CropType.WHEAT, CropType.CARROTS, CropType.POTATOES,
            CropType.BEETROOT, CropType.MIXED, CropType.GRAIN,
            CropType.VEGETABLE);

    private FarmCropPicker() {}

    /**
     * Picks a crop for a plot.
     *
     * @param culture     non-null; falls back to default-culture
     *                    weights if the bias bundle is missing
     * @param sloped      true when the plot is on noticeable slope
     *                    (from {@code Cell.localSlope})
     * @param forestEdge  true when the plot's centre cell has a
     *                    short {@code distToForest}
     * @param pasture     true when the planner allocated this plot
     *                    as pasture (drawn from
     *                    {@code culturePlanningBias.pastureRatio})
     * @param rng         per-plot RNG for the weighted draw
     */
    public static CropType pick(Culture culture, boolean sloped,
                                boolean forestEdge, boolean pasture,
                                Random rng) {
        if (pasture) return CropType.PASTURE;
        if (sloped || forestEdge) return CropType.ORCHARD;
        return weightedDraw(culture, CULTIVATED, rng);
    }

    /** Returns true when the per-plot RNG draw lands below the
     *  culture's {@code pastureRatio}. Callers seed an RNG per plot
     *  so save/reload is stable. */
    public static boolean rollPasture(Culture culture, Random rng) {
        if (culture == null) return false;
        double ratio = culture.planningBias().pastureRatio();
        return rng.nextDouble() < ratio;
    }

    private static CropType weightedDraw(Culture culture,
                                         List<CropType> pool, Random rng) {
        if (pool.isEmpty()) return CropType.WHEAT;
        // Resolve per-type weights, default 1.0 each.
        double total = 0.0;
        double[] weights = new double[pool.size()];
        for (int i = 0; i < pool.size(); i++) {
            double w = culture == null ? 1.0
                    : culture.planningBias().cropPreferenceFor(pool.get(i).name());
            if (w < 0) w = 0;
            weights[i] = w;
            total += w;
        }
        if (total <= 0.0) return CropType.WHEAT;
        double draw = rng.nextDouble() * total;
        double acc = 0.0;
        for (int i = 0; i < pool.size(); i++) {
            acc += weights[i];
            if (draw < acc) return pool.get(i);
        }
        return pool.get(pool.size() - 1);
    }
}
