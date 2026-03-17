package tterrag1112.life_in_the_village.Village.Economy.Resources;

import net.minecraft.world.item.Item;
import java.util.List;

public class BlacksmithRecipeData {

    public record SmeltingRecipe(Item input, Item output, int count, int ticks) {}
    public record CraftingRecipe(Item input, int inputCount,
                                 Item output, int count, int ticks) {}

    private final List<SmeltingRecipe> smeltingRecipes;
    private final List<CraftingRecipe> craftingRecipes;

    public BlacksmithRecipeData(List<SmeltingRecipe> smeltingRecipes,
                                List<CraftingRecipe> craftingRecipes) {
        this.smeltingRecipes = List.copyOf(smeltingRecipes);
        this.craftingRecipes = List.copyOf(craftingRecipes);
    }

    public List<SmeltingRecipe> getSmeltingRecipes() { return smeltingRecipes; }
    public List<CraftingRecipe> getCraftingRecipes() { return craftingRecipes; }

    public java.util.Optional<SmeltingRecipe> findSmeltingRecipe(Item input) {
        return smeltingRecipes.stream()
                .filter(r -> r.input() == input)
                .findFirst();
    }

    public java.util.Optional<CraftingRecipe> findCraftingRecipe(
            Item input, int availableCount) {
        return craftingRecipes.stream()
                .filter(r -> r.input() == input
                        && availableCount >= r.inputCount())
                .findFirst();
    }
}