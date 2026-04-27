package tterrag1112.life_in_the_village.Village.Decoration.Variants;

import net.minecraft.world.item.DyeColor;

import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Doc 15 — three weighted dye-colour distributions, one per
 * {@link ColorSlot}. Each map's value is the relative weight of the
 * key colour within that slot; the {@link ColorPaletteRegistry}
 * sampler normalises the weights at sample time so authors don't
 * need to write percentages.
 *
 * <p>An empty weight map for a slot means "no colour rolls for that
 * slot" — the building's matching colour field stays null and the
 * tint pass leaves the slot's tintable blocks untouched.</p>
 */
public record ColorPalette(
        String id,
        Map<DyeColor, Float> primaryWeights,
        Map<DyeColor, Float> accentWeights,
        Map<DyeColor, Float> roofWeights) {

    public ColorPalette {
        if (id == null) throw new IllegalArgumentException("ColorPalette id");
        primaryWeights = primaryWeights == null
                ? Map.of() : Map.copyOf(primaryWeights);
        accentWeights = accentWeights == null
                ? Map.of() : Map.copyOf(accentWeights);
        roofWeights = roofWeights == null
                ? Map.of() : Map.copyOf(roofWeights);
    }

    /** True iff every slot's weights are empty — an explicit "no
     *  tinting" palette such as {@link ColorPaletteRegistry#NONE}. */
    public boolean isNoOp() {
        return primaryWeights.isEmpty()
                && accentWeights.isEmpty()
                && roofWeights.isEmpty();
    }

    /** Returns the weight map for {@code slot}. */
    public Map<DyeColor, Float> weightsFor(ColorSlot slot) {
        return switch (slot) {
            case PRIMARY -> primaryWeights;
            case ACCENT  -> accentWeights;
            case ROOF    -> roofWeights;
        };
    }

    /**
     * Weighted-random pick from {@code weightsFor(slot)}, skipping any
     * colour in {@code excludedColors}. Returns {@code null} when the
     * surviving weight sum is zero (empty slot, every colour excluded,
     * or all weights zero / negative).
     */
    public DyeColor sample(ColorSlot slot, Random rng,
                           Set<DyeColor> excludedColors) {
        return sample(slot, rng, excludedColors, java.util.Set.of());
    }

    /**
     * Doc 15 §"Color assignment" — weighted-random pick with both a
     * hard exclusion ({@code excludedColors}, removed entirely) and a
     * soft exclusion ({@code softExcluded}, multiplied by
     * {@value #SOFT_EXCLUSION_WEIGHT}) applied on top. The soft set
     * implements the "downweight neighbouring colours × 0.2" step;
     * the hard set is what accent / roof use to exclude the chosen
     * primary.
     *
     * <p>Returns {@code null} when the surviving weighted sum is zero
     * — typically only happens with an empty slot or every colour in
     * the hard-exclusion set. The caller is expected to retry with an
     * empty soft set in that case (see
     * {@link VillagePaletteResolver}).</p>
     */
    public DyeColor sample(ColorSlot slot, Random rng,
                           Set<DyeColor> excludedColors,
                           Set<DyeColor> softExcluded) {
        Map<DyeColor, Float> weights = weightsFor(slot);
        if (weights.isEmpty()) return null;

        double total = 0.0;
        for (Map.Entry<DyeColor, Float> e : weights.entrySet()) {
            if (excludedColors != null && excludedColors.contains(e.getKey())) continue;
            double w = effective(e.getValue(), softExcluded, e.getKey());
            if (w > 0) total += w;
        }
        if (total <= 0.0) return null;

        double roll = rng.nextDouble() * total;
        DyeColor last = null;
        for (Map.Entry<DyeColor, Float> e : weights.entrySet()) {
            if (excludedColors != null && excludedColors.contains(e.getKey())) continue;
            double w = effective(e.getValue(), softExcluded, e.getKey());
            if (w <= 0) continue;
            last = e.getKey();
            roll -= w;
            if (roll <= 0.0) return e.getKey();
        }
        return last; // floating-point safety fallback
    }

    /** Doc 15: soft-exclusion multiplier applied to colours that
     *  appear in the local-neighbour exclusion set. */
    public static final double SOFT_EXCLUSION_WEIGHT = 0.2;

    private static double effective(Float base, Set<DyeColor> soft, DyeColor c) {
        if (base == null) return 0.0;
        double w = base.doubleValue();
        if (w <= 0) return 0.0;
        if (soft != null && soft.contains(c)) w *= SOFT_EXCLUSION_WEIGHT;
        return w;
    }
}
