package tterrag1112.life_in_the_village.Village.Economy.Resources;

import tterrag1112.life_in_the_village.Village.Economy.Resources.ProductionRecipe;

import java.util.List;

/**
 * Phase 6.6.5.2b — JSON-loaded BLACKSMITH recipe DTO. Pre-6.6.5 this
 * class carried per-flavor {@code SmeltingRecipe} and {@code CraftingRecipe}
 * records; post-6.6.5 it holds two {@code List<ProductionRecipe>} bins,
 * one per flavor. The smelt/craft distinction is preserved by which
 * bin a recipe lives in — {@code BlacksmithProductionBehavior} uses
 * this to decide furnace vs anvil workstation, coal fuel, and ore-
 * source resolution.
 *
 * <p>Single-input JSON recipes deserialise into single-entry
 * {@code Map<Item, Integer>} inputs on the ProductionRecipe. Multi-
 * input masterpieces (NETHERITE_INGOT, diamond tools with sticks)
 * live inline in {@code BlacksmithProductionBehavior} per the
 * 6.6.5 sign-off (Option A1 hybrid).</p>
 */
public class BlacksmithRecipeData {

    private final List<ProductionRecipe> smeltingRecipes;
    private final List<ProductionRecipe> craftingRecipes;

    public BlacksmithRecipeData(List<ProductionRecipe> smeltingRecipes,
                                List<ProductionRecipe> craftingRecipes) {
        this.smeltingRecipes = List.copyOf(smeltingRecipes);
        this.craftingRecipes = List.copyOf(craftingRecipes);
    }

    public List<ProductionRecipe> getSmeltingRecipes() { return smeltingRecipes; }
    public List<ProductionRecipe> getCraftingRecipes() { return craftingRecipes; }
}