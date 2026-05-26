package tterrag1112.life_in_the_village.Village.Economy.Resources;

import net.minecraft.world.item.Item;
import java.util.List;

public class BlacksmithRecipeData {

    /**
     * Phase 6.6.2.2 — recipe records gained a {@code minSkillLevel} field
     * for tier-based skill gating. The relevant skill is inferred from the
     * output via {@link
     * tterrag1112.life_in_the_village.Entities.Goals.Profession.Blacksmith
     * .BlacksmithSpecialization#categorize}: TOOLSMITHING for tools,
     * WEAPONSMITHING for weapons, ARMORSMITHING for armor, BLACKSMITHING
     * for ingots / smelting. Default 0 = no gate (preserves backward
     * compat with JSON files that omit the field).
     */
    public record SmeltingRecipe(Item input, Item output, int count,
                                 int ticks, int minSkillLevel) {
        public SmeltingRecipe(Item input, Item output, int count, int ticks) {
            this(input, output, count, ticks, 0);
        }
    }
    public record CraftingRecipe(Item input, int inputCount,
                                 Item output, int count, int ticks,
                                 int minSkillLevel) {
        public CraftingRecipe(Item input, int inputCount, Item output,
                              int count, int ticks) {
            this(input, inputCount, output, count, ticks, 0);
        }
    }

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