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
        Map<DyeColor, Float> weights = weightsFor(slot);
        if (weights.isEmpty()) return null;

        double total = 0.0;
        for (Map.Entry<DyeColor, Float> e : weights.entrySet()) {
            if (excludedColors != null && excludedColors.contains(e.getKey())) continue;
            float w = e.getValue() == null ? 0f : e.getValue();
            if (w > 0) total += w;
        }
        if (total <= 0.0) return null;

        double roll = rng.nextDouble() * total;
        DyeColor last = null;
        for (Map.Entry<DyeColor, Float> e : weights.entrySet()) {
            if (excludedColors != null && excludedColors.contains(e.getKey())) continue;
            float w = e.getValue() == null ? 0f : e.getValue();
            if (w <= 0) continue;
            last = e.getKey();
            roll -= w;
            if (roll <= 0.0) return e.getKey();
        }
        return last; // floating-point safety fallback
    }
}
