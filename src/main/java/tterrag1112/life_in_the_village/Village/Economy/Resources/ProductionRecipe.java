// src/main/java/tterrag1112/life_in_the_village/Village/Economy/Production/ProductionRecipe.java
package tterrag1112.life_in_the_village.Village.Economy.Resources;

import net.minecraft.world.item.Item;

/**
 * A generic production recipe: consume {@code inputCount} of {@code input}
 * to produce {@code outputCount} of {@code output}, taking {@code ticks}
 * of work time per unit.
 *
 * <p>This replaces the scattered recipe record types ({@code SmeltingRecipe},
 * {@code CraftingRecipe}, {@code CraftingRecipeEntry}) used by individual
 * profession goals. New professions should use this record directly.</p>
 *
 * <h3>Batching</h3>
 * A batch of size {@code N} consumes {@code N × inputCount} of input and
 * produces {@code N × outputCount} of output, taking {@code N × ticks}
 * total work time.
 */
public record ProductionRecipe(
        Item input,
        int inputCount,
        Item output,
        int outputCount,
        int ticks
) {
    /** Convenience: 1 input → 1 output in 40 ticks. */
    public static ProductionRecipe simple(Item input, Item output) {
        return new ProductionRecipe(input, 1, output, 1, 40);
    }

    /** Convenience: N inputs → M outputs in 40 ticks. */
    public static ProductionRecipe of(Item input, int inputCount,
                                      Item output, int outputCount) {
        return new ProductionRecipe(input, inputCount, output, outputCount, 40);
    }
}