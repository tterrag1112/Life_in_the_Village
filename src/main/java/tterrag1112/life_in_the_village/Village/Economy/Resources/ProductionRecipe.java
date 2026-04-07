package tterrag1112.life_in_the_village.Village.Economy.Resources;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Map;

/**
 * Describes what an NPC produces in one production run.
 *
 * <h3>Inputs</h3>
 * A map of item → count-per-batch-unit supports multi-ingredient recipes
 * (e.g. fletcher: sticks + flint + feather). Single-ingredient professions
 * use the {@link #of} factory methods.
 *
 * <h3>Byproducts</h3>
 * Fixed additional outputs produced once per production run regardless of
 * batch size (e.g. bone meal left over from tanning, empty bottles from
 * brewing). These are deposited alongside the primary output.
 *
 * <h3>Ticks</h3>
 * Base ticks per-batch-unit BEFORE the {@code productionSpeedMultiplier}
 * is applied. {@code buildSteps} in the goal is responsible for scaling.
 */
public record ProductionRecipe(
        Map<Item, Integer> inputs,       // per-batch-unit
        Item               output,
        int                outputCount,  // per-batch-unit
        int                ticks,        // per-batch-unit, unscaled
        List<ItemStack>    byproducts    // fixed per production run
) {
    // ── Convenience factories ─────────────────────────────────────────────

    /** Single input, no byproducts. */
    public static ProductionRecipe of(Item input, int inputCount,
                                      Item output, int outputCount, int ticks) {
        return new ProductionRecipe(
                Map.of(input, inputCount), output, outputCount, ticks, List.of());
    }

    /** Single input, with byproducts. */
    public static ProductionRecipe of(Item input, int inputCount,
                                      Item output, int outputCount, int ticks,
                                      List<ItemStack> byproducts) {
        return new ProductionRecipe(
                Map.of(input, inputCount), output, outputCount, ticks, byproducts);
    }

    /** Multiple inputs, no byproducts. */
    public static ProductionRecipe of(Map<Item, Integer> inputs,
                                      Item output, int outputCount, int ticks) {
        return new ProductionRecipe(inputs, output, outputCount, ticks, List.of());
    }

    /** Multiple inputs, with byproducts. */
    public static ProductionRecipe of(Map<Item, Integer> inputs,
                                      Item output, int outputCount, int ticks,
                                      List<ItemStack> byproducts) {
        return new ProductionRecipe(inputs, output, outputCount, ticks, byproducts);
    }
}